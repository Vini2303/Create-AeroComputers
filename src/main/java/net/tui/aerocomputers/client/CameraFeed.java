package net.tui.aerocomputers.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.network.CameraBlockEntitiesPayload;
import net.tui.aerocomputers.network.CameraBlocksPayload;
import net.tui.aerocomputers.network.CameraEntitiesPayload;
import net.tui.aerocomputers.network.CameraInterestPayload;
import net.tui.aerocomputers.network.CameraSubLevelsPayload;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-only. One camera world per camera block:
 * own ClientLevel + LevelRenderer + chunk cache + entities + render target + texture.
 * The world is rendered through GameRenderer.renderLevel with level / renderer / camera / target
 * swapped for the duration of the render. The server streams the chunks around the camera
 * (CameraChunkManager) after the heartbeat sent from here.
 */
public class CameraFeed {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int UNUSED_TICKS = 200;       // cameras nobody requests for 10 s are released
    private static final int INTEREST_INTERVAL = 10;   // heartbeat to the server, in ticks
    private static final int CAM_SECTION_BUFFERS = 2;  // each camera compiles with at most 2 builders at once

    // =========================================================
    // One world per camera
    // =========================================================

    private static final class CameraWorld {
        final BlockPos id;
        final ClientLevel parent;              // the real level this was created for
        final RenderBuffers buffers;
        final LevelRenderer renderer;
        final ClientLevel level;
        final TextureTarget target;
        final Marker marker;
        final Int2ObjectMap<Entity> entities = new Int2ObjectOpenHashMap<>();
        final ArrayDeque<ClientboundLevelChunkWithLightPacket> chunkQueue = new ArrayDeque<>();
        boolean centerSet = false;
        boolean needsFresh = true;   // first heartbeat asks the server to send everything
        int centerX, centerZ;
        long lastUsed;
        long lastInterest = Long.MIN_VALUE / 2;

        CameraWorld(Minecraft mc, ClientLevel main, BlockPos id, long now) {
            this.id = id;
            this.parent = main;
            this.lastUsed = now;

            // create the renderer under FANCY so it never builds the Fabulous post-processing chain
            GraphicsStatus oldGfx = setGraphics(mc, GraphicsStatus.FANCY);
            try {
                buffers = new RenderBuffers(CAM_SECTION_BUFFERS);
                renderer = new LevelRenderer(mc, mc.getEntityRenderDispatcher(),
                        mc.getBlockEntityRenderDispatcher(), buffers);
                renderer.onResourceManagerReload(mc.getResourceManager());
            } finally {
                setGraphics(mc, oldGfx);
            }

            int dist = CameraConfig.SEND_RADIUS;
            level = new ClientLevel(mc.getConnection(), main.getLevelData(), main.dimension(),
                    main.dimensionTypeRegistration(), dist, dist, mc::getProfiler, renderer, false, 0L);
            renderer.setLevel(level);

            target = new TextureTarget(WIDTH, HEIGHT, true, Minecraft.ON_OSX);
            target.setClearColor(0f, 0f, 0f, 1f);

            marker = new Marker(EntityType.MARKER, level);
            cameraLevels.add(level);
        }

        void close() {
            cameraLevels.remove(level);
            chunkQueue.clear();
            entities.clear();
            try {
                ClientSubLevelContainer c = SubLevelContainer.getContainer(level);
                if (c != null) {
                    for (ClientSubLevel sl : new ArrayList<>(c.getAllSubLevels())) c.removeSubLevel(sl, SubLevelRemovalReason.REMOVED);
                    c.processSubLevelRemovals();
                }
            } catch (Throwable t) {
                AeroComputers.LOGGER.warn("Could not free camera sub-levels", t);
            }
            renderer.close();
            buffers.fixedBufferPack().close();   // if this does not resolve in your version, delete the line
            target.destroyBuffers();
        }
    }

    private static final Map<BlockPos, CameraWorld> worlds = new LinkedHashMap<>();
    private static final Map<BlockPos, ResourceLocation> textureIds = new HashMap<>();

    /** Read from other threads too (Flywheel mixin, chunk compile threads), hence concurrent. */
    private static final Set<LevelAccessor> cameraLevels = ConcurrentHashMap.newKeySet();

    private record CameraRequest(Vec3 pos, float pitch, float yaw, float fov) {}
    private static final Map<BlockPos, CameraRequest> pendingRequests = new LinkedHashMap<>();

    public static boolean rendering = false;       // read by the FOV event
    public static float fov = CameraConfig.FOV;    // set per camera right before it renders

    private static boolean tickPending = false;
    private static long tickCounter = 0;

    // =========================================================
    // Requests
    // =========================================================

    /**
     * Request a render of one camera.
     * cameraId MUST be the BlockPos of the camera block: the server uses it to find the camera.
     */
    public static void request(BlockPos cameraId, Vec3 pos, float pitch, float yaw, float fovDeg) {
        if (cameraId == null || pos == null) return;
        pendingRequests.put(cameraId.immutable(), new CameraRequest(pos, pitch, yaw, fovDeg));
    }

    public static TextureTarget getTarget(BlockPos cameraId) {
        CameraWorld w = worlds.get(cameraId);
        return w == null ? null : w.target;
    }

    /** Texture to sample in RenderType.text(...). Registered lazily, one per camera. */
    public static ResourceLocation getTextureId(BlockPos cameraId) {
        return textureIds.computeIfAbsent(cameraId.immutable(), p -> {
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                    AeroComputers.MOD_ID, "camera_feed/" + Long.toHexString(p.asLong()));
            Minecraft.getInstance().getTextureManager().register(loc, new TargetTexture(p));
            return loc;
        });
    }

    /** Called from ClientTickEvent.Post. */
    public static void onClientTick() {
        tickPending = true;
        tickCounter++;

        // apply at most one chunk per camera and tick so the mesh compiles don't pile up
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        for (CameraWorld w : worlds.values()) {
            ClientSubLevelContainer sub = SubLevelContainer.getContainer(w.level);
            // Sable only ticks the container of the real level. Run it with the camera world swapped in,
            // because Sable reads Minecraft.level / Minecraft.levelRenderer inside.
            if (sub != null) inWorld(mc, w, sub::tick);
            if (CameraConfig.DEBUG && tickCounter % 40 == 0) debug(w);

            if (!w.centerSet) continue;   // view centre is set by the first entity packet
            ClientboundLevelChunkWithLightPacket next = w.chunkQueue.poll();
            if (next != null) applyChunk(w, next);
        }
    }

    // =========================================================
    // Rendering
    // =========================================================

    /** Accepts the old call with a partial tick, so existing event code still compiles. */
    public static void renderIfRequested(float ignoredPartialTick) {
        renderIfRequested();
    }

    /** Call from RenderFrameEvent.Pre (before the player's own frame). */
    public static void renderIfRequested() {
        if (pendingRequests.isEmpty() || !tickPending || rendering) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getConnection() == null) {
            pendingRequests.clear();
            tickPending = false;
            return;
        }

        Map<BlockPos, CameraRequest> requests = new LinkedHashMap<>(pendingRequests);
        pendingRequests.clear();
        tickPending = false;

        ClientLevel realLevel = mc.level;
        LocalPlayer player = mc.player;
        double playerX = player.getX(), playerY = player.getY(), playerZ = player.getZ();

        // create / validate the worlds first, while mc.level is still the real level
        Map<BlockPos, CameraWorld> active = new LinkedHashMap<>();
        for (BlockPos id : requests.keySet()) {
            active.put(id, getWorld(mc, realLevel, id));
        }

        // remember everything that is about to be swapped
        RenderTarget oldTarget = mc.getMainRenderTarget();
        Entity oldCamera = mc.getCameraEntity();
        boolean oldHand = mc.gameRenderer.renderHand;
        LevelRenderer oldRenderer = mc.levelRenderer;
        CameraType oldType = mc.options.getCameraType();
        HitResult oldHit = mc.hitResult;
        Entity oldPick = mc.crosshairPickEntity;

        // The main Camera is shared with the player and keeps a smoothed eye-height offset
        // that follows the PLAYER (sneak / swim), so neutralise it for these renders.
        Camera mainCam = mc.gameRenderer.getMainCamera();
        float oldEye = mainCam.eyeHeight;
        float oldEyeOld = mainCam.eyeHeightOld;

        // Fabulous composites translucent blocks into the real main framebuffer, not into our targets.
        GraphicsStatus oldGfx = setGraphics(mc, GraphicsStatus.FANCY);

        mainCam.eyeHeight = 0f;
        mainCam.eyeHeightOld = 0f;
        mc.options.setCameraType(CameraType.FIRST_PERSON);   // third person would detach the camera
        mc.gameRenderer.renderHand = false;
        rendering = true;

        try {
            for (Map.Entry<BlockPos, CameraRequest> entry : requests.entrySet()) {
                CameraWorld w = active.get(entry.getKey());
                CameraRequest r = entry.getValue();
                w.lastUsed = tickCounter;

                // heartbeat: tells the server to stream this camera to us
                if (tickCounter - w.lastInterest >= INTEREST_INTERVAL) {
                    PacketDistributor.sendToServer(new CameraInterestPayload(w.id, w.needsFresh));
                    w.needsFresh = false;
                    w.lastInterest = tickCounter;
                }

                fov = r.fov();   // read by the FOV event during renderLevel

                Vec3 pos = r.pos();
                Marker m = w.marker;
                m.setPos(pos);
                m.setYRot(r.yaw());
                m.setXRot(r.pitch());
                // no interpolation, otherwise the camera "swims"
                m.xo = m.xOld = pos.x;
                m.yo = m.yOld = pos.y;
                m.zo = m.zOld = pos.z;
                m.yRotO = r.yaw();
                m.xRotO = r.pitch();

                mc.level = w.level;
                mc.levelRenderer = w.renderer;
                mc.setCameraEntity(m);
                mc.mainRenderTarget = w.target;
                w.target.clear(Minecraft.ON_OSX);
                w.target.bindWrite(true);

                // Vanilla's LevelRenderer.setupRender centres its grid of render sections on the PLAYER, not on the
                // camera. A camera farther away than the render distance would get no sections at all.
                // So the player is placed at the camera for the duration of this render and put back afterwards.
                player.setPosRaw(pos.x, pos.y, pos.z);

                mc.gameRenderer.renderLevel(mc.getTimer());
            }
        } finally {
            player.setPosRaw(playerX, playerY, playerZ);
            rendering = false;
            mc.gameRenderer.renderHand = oldHand;
            mc.mainRenderTarget = oldTarget;
            mc.setCameraEntity(oldCamera);
            mc.levelRenderer = oldRenderer;
            mc.level = realLevel;
            mc.options.setCameraType(oldType);
            mc.hitResult = oldHit;
            mc.crosshairPickEntity = oldPick;
            setGraphics(mc, oldGfx);
            mainCam.eyeHeight = oldEye;
            mainCam.eyeHeightOld = oldEyeOld;
            oldTarget.bindWrite(true);
        }

        releaseUnused();
    }

    private static CameraWorld getWorld(Minecraft mc, ClientLevel realLevel, BlockPos id) {
        CameraWorld w = worlds.get(id);
        if (w != null && w.parent != realLevel) {   // dimension / world changed
            w.close();
            worlds.remove(id);
            w = null;
        }
        if (w == null) {
            w = new CameraWorld(mc, realLevel, id, tickCounter);
            worlds.put(id, w);
        }
        return w;
    }

    private static GraphicsStatus setGraphics(Minecraft mc, GraphicsStatus status) {
        GraphicsStatus old = mc.options.graphicsMode().get();
        mc.options.graphicsMode().value = status;   // direct write: set() would trigger a full renderer reload
        return old;
    }

    private static void releaseUnused() {
        for (Iterator<CameraWorld> it = worlds.values().iterator(); it.hasNext(); ) {
            CameraWorld w = it.next();
            if (tickCounter - w.lastUsed > UNUSED_TICKS) {
                w.close();
                it.remove();
            }
        }
    }

    // =========================================================
    // Chunks
    // =========================================================

    /** Called when a camera chunk payload arrives: only queues it. */
    public static void acceptChunk(BlockPos cam, ClientboundLevelChunkWithLightPacket pkt) {
        CameraWorld w = worlds.get(cam);
        if (w == null) return;   // nobody is watching this camera on this client -> ignore

        ClientSubLevelContainer sub = SubLevelContainer.getContainer(w.level);
        if (sub != null && sub.inBounds(pkt.getX(), pkt.getZ())) {
            inWorld(Minecraft.getInstance(), w, () -> applyChunk(w, pkt));   // plot chunk: apply at once, finalize follows
        } else {
            w.chunkQueue.add(pkt);
        }
    }

    private static void applyChunk(CameraWorld w, ClientboundLevelChunkWithLightPacket pkt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null) return;

        ClientLevel level = w.level;
        int x = pkt.getX(), z = pkt.getZ();
        var data = pkt.getChunkData();
        level.getChunkSource().replaceWithPacketData(
                x, z, data.getReadBuffer(), data.getHeightmaps(), data.getBlockEntitiesTagsConsumer(x, z));

        var light = pkt.getLightData();
        Runnable lightTask = () -> {
            LevelLightEngine engine = level.getLightEngine();
            readLight(level, x, z, engine, LightLayer.SKY,
                    light.getSkyYMask(), light.getEmptySkyYMask(), light.getSkyUpdates().iterator());
            readLight(level, x, z, engine, LightLayer.BLOCK,
                    light.getBlockYMask(), light.getEmptyBlockYMask(), light.getBlockUpdates().iterator());
            level.onChunkLoaded(new ChunkPos(x, z));

            LevelChunk chunk = level.getChunkSource().getChunk(x, z, false);
            if (chunk != null) {
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    int sy = level.getSectionYFromSectionIndex(i);
                    engine.updateSectionStatus(SectionPos.of(x, sy, z), sections[i].hasOnlyAir());
                    level.setSectionDirtyWithNeighbors(x, sy, z);
                }
                engine.setLightEnabled(chunk.getPos(), true);
            }
        };

        ClientSubLevelContainer sub = SubLevelContainer.getContainer(level);
        if (sub != null && sub.inBounds(x, z)) {
            lightTask.run();
            level.getLightEngine().runLightUpdates();
        } else {
            level.queueLightUpdate(lightTask);
        }
    }

    private static void readLight(ClientLevel level, int x, int z, LevelLightEngine engine, LightLayer layer,
                                  BitSet mask, BitSet emptyMask, Iterator<byte[]> updates) {
        for (int i = 0; i < engine.getLightSectionCount(); i++) {
            int sy = engine.getMinLightSection() + i;
            boolean has = mask.get(i), empty = emptyMask.get(i);
            if (has || empty) {
                engine.queueSectionData(layer, SectionPos.of(x, sy, z),
                        has ? new DataLayer(updates.next().clone()) : new DataLayer());
                level.setSectionDirtyWithNeighbors(x, sy, z);
            }
        }
    }

    // =========================================================
    // Blocks and block entities
    // =========================================================

    public static void acceptBlocks(BlockPos cam, List<CameraBlocksPayload.Change> changes) {
        CameraWorld w = worlds.get(cam);
        if (w == null) return;

        inWorld(Minecraft.getInstance(), w, () -> {
            for (CameraBlocksPayload.Change c : changes) {
                w.level.setServerVerifiedBlockState(c.pos(), Block.stateById(c.stateId()), 19);
            }
        });
    }

    public static void acceptBlockEntities(BlockPos cam, List<CameraBlockEntitiesPayload.Entry> entries) {
        CameraWorld w = worlds.get(cam);
        if (w == null) return;

        for (CameraBlockEntitiesPayload.Entry e : entries) {
            BlockEntity be = w.level.getBlockEntity(e.pos());
            if (be != null) be.handleUpdateTag(e.tag(), w.level.registryAccess());
        }
    }

    // =========================================================
    // Entities (also moves the view centre of the camera world)
    // =========================================================

    public static void acceptEntities(BlockPos cam, int chunkX, int chunkZ, List<CameraEntitiesPayload.Snap> snaps) {
        CameraWorld w = worlds.get(cam);
        if (w == null) return;

        if (!w.centerSet || w.centerX != chunkX || w.centerZ != chunkZ) {
            w.level.getChunkSource().updateViewCenter(chunkX, chunkZ);
            w.centerX = chunkX;
            w.centerZ = chunkZ;
            w.centerSet = true;
        }

        ClientLevel level = w.level;
        IntSet seen = new IntOpenHashSet();

        for (CameraEntitiesPayload.Snap s : snaps) {
            seen.add(s.id());
            Entity e = w.entities.get(s.id());
            boolean created = false;

            if (e == null || e.level() != level) {
                if (EntityType.PLAYER.equals(BuiltInRegistries.ENTITY_TYPE.get(s.type()))) {
                    e = new RemotePlayer(level, new GameProfile(s.uuid(), s.name()));
                } else {
                    e = BuiltInRegistries.ENTITY_TYPE.get(s.type()).create(level);
                }
                if (e == null) continue;
                e.setId(s.id());
                e.setUUID(s.uuid());
                created = true;
            } else {
                e.setOldPosAndRot();
                e.tickCount++;
                if (e instanceof LivingEntity le) {
                    le.yBodyRotO = le.yBodyRot;
                    le.yHeadRotO = le.yHeadRot;
                    le.oAttackAnim = le.attackAnim;
                }
            }

            e.setPos(s.x(), s.y(), s.z());
            e.setYRot(s.yRot());
            e.setXRot(s.xRot());
            e.setYHeadRot(s.headYRot());
            if (!s.data().isEmpty()) e.getEntityData().assignValues(s.data());

            if (e instanceof LivingEntity le) {
                le.yBodyRot = s.bodyRot();
                le.attackAnim = s.attackAnim();
                le.swingingArm = s.offHand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                le.hurtTime = s.hurtTime();
                le.deathTime = s.deathTime();

                if (created) {
                    le.yBodyRotO = le.yBodyRot;
                    le.yHeadRotO = le.yHeadRot;
                    le.oAttackAnim = le.attackAnim;
                } else {
                    le.calculateEntityAnimation(le instanceof FlyingAnimal);
                }
            }

            if (created) {
                e.setOldPosAndRot();
                level.addEntity(e);
                w.entities.put(s.id(), e);
            }
        }

        for (Iterator<Int2ObjectMap.Entry<Entity>> it = w.entities.int2ObjectEntrySet().iterator(); it.hasNext(); ) {
            Int2ObjectMap.Entry<Entity> en = it.next();
            if (!seen.contains(en.getIntKey())) {
                level.removeEntity(en.getIntKey(), Entity.RemovalReason.DISCARDED);
                it.remove();
            }
        }
    }

    // =========================================================
    // Sable sub-levels (what ClientboundStart/Finalize/StopTrackingSubLevelPacket do, for the camera world)
    // =========================================================

    public static void acceptSubLevels(CameraSubLevelsPayload p) {
        CameraWorld w = worlds.get(p.cam());
        if (w == null) return;

        // Sable creates the render sections of a sub-level with Minecraft.levelRenderer's SectionRenderDispatcher
        // (VanillaSubLevelRenderDispatcher.createRenderData). Outside of this swap that is the MAIN renderer and
        // the MAIN level: the sections would be compiled from the wrong world, and Flywheel would take the block
        // entities away from them. Inside the swap it is the camera world's own dispatcher.
        inWorld(Minecraft.getInstance(), w, () -> applySubLevels(w, p));
    }

    private static void applySubLevels(CameraWorld w, CameraSubLevelsPayload p) {
        ClientSubLevelContainer container = SubLevelContainer.getContainer(w.level);
        if (container == null) return;

        for (UUID id : p.removed()) {
            SubLevel sl = container.getSubLevel(id);
            if (sl != null) container.removeSubLevel(sl, SubLevelRemovalReason.REMOVED);
        }

        for (CameraSubLevelsPayload.Track t : p.tracks()) {
            SubLevel existing = container.getSubLevel(t.uuid());

            if (existing == null) {
                if (!t.isNew()) continue;   // missed the start (world was rebuilt): the next fresh heartbeat resyncs

                ClientSubLevel sl = (ClientSubLevel) container.allocateSubLevel(
                        t.uuid(), ChunkPos.getX(t.plot()), ChunkPos.getZ(t.plot()), new Pose3d(t.lastPose()));

                SubLevelSnapshotInterpolator interpolator = sl.getInterpolator();
                interpolator.receiveSnapshot(p.gameTick() - 1, t.lastPose());
                interpolator.receiveSnapshot(p.gameTick(), t.pose());
                if (!container.getInterpolation().isStopped()) sl.setInitialPosesFrom(container.getInterpolation());
                interpolator.setFirstPoses(t.pose(), t.lastPose());

                sl.getPlot().setBoundingBox(t.bounds());
                sl.forceUpdateBounds();
                sl.updateRenderData();
                if (t.name() != null) sl.setName(t.name());

            } else if (existing instanceof ClientSubLevel sl) {
                sl.getInterpolator().receiveSnapshot(p.gameTick(), t.pose());
            }
        }

        if (!p.tracks().isEmpty()) container.getInterpolation().receiveInfo(50, p.gameTick(), false);

        for (UUID id : p.finalized()) {
            if (container.getSubLevel(id) instanceof ClientSubLevel sl) {
                sl.setFinalized();
                sl.updateRenderData();
            }
        }
    }

    /** Runs 'action' with Minecraft.level / Minecraft.levelRenderer pointing at the camera world. */
    private static void inWorld(Minecraft mc, CameraWorld w, Runnable action) {
        ClientLevel oldLevel = mc.level;
        LevelRenderer oldRenderer = mc.levelRenderer;
        mc.level = w.level;
        mc.levelRenderer = w.renderer;
        try {
            action.run();
        } finally {
            mc.levelRenderer = oldRenderer;
            mc.level = oldLevel;
        }
    }

    // =========================================================
    // Debug (CameraConfig.DEBUG)
    // =========================================================

    private static void debug(CameraWorld w) {
        if (!w.centerSet) {
            AeroComputers.LOGGER.info("[cam-client {}] no entity packet yet (view centre not set), queued chunks={}", w.id, w.chunkQueue.size());
            return;
        }
        int loaded = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (w.level.getChunkSource().getChunk(w.centerX + dx, w.centerZ + dz, false) != null) loaded++;

        int bx = w.centerX * 16 + 8, bz = w.centerZ * 16 + 8;
        int y = w.level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
        BlockPos p = new BlockPos(bx, y, bz);
        ClientSubLevelContainer sub = SubLevelContainer.getContainer(w.level);

        AeroComputers.LOGGER.info("[cam-client {}] centre {},{} chunks3x3 {}/9 queued {} surfaceY {} below {} sky {} block {} subLevels {} renderer {}",
                w.id, w.centerX, w.centerZ, loaded, w.chunkQueue.size(), y, w.level.getBlockState(p.below()),
                w.level.getBrightness(LightLayer.SKY, p), w.level.getBrightness(LightLayer.BLOCK, p),
                sub == null ? -1 : sub.getAllSubLevels().size(), w.renderer.getSectionStatistics());
    }

    // =========================================================
    // Cleanup / utilities
    // =========================================================

    public static void removeCamera(BlockPos cameraId) {
        CameraWorld w = worlds.remove(cameraId);
        if (w != null) w.close();
        pendingRequests.remove(cameraId);
    }

    public static void clear() {
        for (CameraWorld w : worlds.values()) w.close();
        worlds.clear();
        pendingRequests.clear();
        tickPending = false;
        rendering = false;
    }

    /** True for the level of ANY camera world. Safe to call from any thread. */
    public static boolean isCameraLevel(LevelAccessor level) {
        return level != null && cameraLevels.contains(level);
    }
}