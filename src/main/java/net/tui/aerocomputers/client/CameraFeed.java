package net.tui.aerocomputers.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.LevelRenderer;
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
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.Vec3;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.network.CameraBlocksPayload;
import net.tui.aerocomputers.network.CameraEntitiesPayload;

import java.util.BitSet;
import java.util.Iterator;
import java.util.List;

/**
 * Client-only. Renders a separate "camera world" (its own ClientLevel + LevelRenderer)
 * into a texture, so the player's own renderer is never touched (no white flashes).
 * Chunks / entities / block changes arrive via payloads -> accept*().
 */
public class CameraFeed {
    public static final ResourceLocation TEXTURE_ID =
            ResourceLocation.fromNamespaceAndPath(AeroComputers.MOD_ID, "camera_feed");

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    private static TextureTarget target;
    private static Marker camEntity;
    private static boolean textureRegistered = false;

    // the separate camera world
    private static LevelRenderer camRenderer;
    private static ClientLevel camLevel;
    private static ClientLevel camParent;   // the main level camLevel was created from
    private static final Int2ObjectMap<Entity> camEntities = new Int2ObjectOpenHashMap<>();

    public static boolean rendering = false;   // read by the FOV event
    public static float fov = CameraConfig.FOV;

    // set by consumers (block renderer) every frame -> only render when someone looks at it
    private static boolean requested = false;
    private static boolean tickPending = false;
    private static Vec3 pos = CameraConfig.POS;
    private static float yaw = CameraConfig.YAW, pitch = CameraConfig.PITCH;

    public static void request(Vec3 p, float yawDeg, float pitchDeg, float fovDeg) {
        requested = true;
        pos = p;
        yaw = yawDeg;
        pitch = pitchDeg;
        fov = fovDeg;
    }

    /** Fixed camera (see CameraConfig). */
    public static void requestFixed() {
        request(CameraConfig.POS, CameraConfig.YAW, CameraConfig.PITCH, CameraConfig.FOV);
    }

    public static TextureTarget getTarget() { return target; }

    /** Called from ClientTickEvent.Post -> one render per tick (20 FPS feed). */
    public static void onClientTick() { tickPending = true; }

    /** Called from RenderFrameEvent.Pre */
    public static void renderIfRequested() {
        if (!requested || !tickPending || rendering) return;
        requested = false;
        tickPending = false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getConnection() == null) return;

        ensureWorld(mc);

        if (target == null) {
            target = new TextureTarget(WIDTH, HEIGHT, true, Minecraft.ON_OSX);
            target.setClearColor(0f, 0f, 0f, 1f);
        }

        if (!textureRegistered) {
            mc.getTextureManager().register(TEXTURE_ID, new TargetTexture());
            textureRegistered = true;
        }

        // the camera entity must live in the camera world
        if (camEntity == null || camEntity.level() != camLevel) {
            camEntity = new Marker(EntityType.MARKER, camLevel);
        }
        camEntity.setPos(pos);
        camEntity.setYRot(yaw);
        camEntity.setXRot(pitch);
        // no interpolation, otherwise the camera "swims" between old and new position
        camEntity.xo = camEntity.xOld = pos.x;
        camEntity.yo = camEntity.yOld = pos.y;
        camEntity.zo = camEntity.zOld = pos.z;
        camEntity.yRotO = yaw;
        camEntity.xRotO = pitch;

        // remember everything we are about to swap
        RenderTarget oldTarget = mc.getMainRenderTarget();
        Entity oldCamera = mc.getCameraEntity();
        boolean oldHand = mc.gameRenderer.renderHand;
        ClientLevel oldLevel = mc.level;
        LevelRenderer oldRenderer = mc.levelRenderer;
        CameraType oldType = mc.options.getCameraType();

        // The main Camera object is shared with the player. It keeps a smoothed eye-height
        // offset that follows the PLAYER (sneak/swim change it), so neutralise it for this render.
        Camera mainCam = mc.gameRenderer.getMainCamera();
        float oldEye = mainCam.eyeHeight;
        float oldEyeOld = mainCam.eyeHeightOld;
        mainCam.eyeHeight = 0f;
        mainCam.eyeHeightOld = 0f;
        mc.options.setCameraType(CameraType.FIRST_PERSON);   // third person would detach the camera
        GraphicsStatus oldGfx = setGraphics(mc, GraphicsStatus.FANCY);

        mc.level = camLevel;
        mc.levelRenderer = camRenderer;
        mc.mainRenderTarget = target;
        mc.setCameraEntity(camEntity);
        mc.gameRenderer.renderHand = false;
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        rendering = true;
        try {
            mc.gameRenderer.renderLevel(mc.getTimer());
        } finally {
            rendering = false;
            mc.gameRenderer.renderHand = oldHand;
            mc.mainRenderTarget = oldTarget;
            mc.setCameraEntity(oldCamera);
            setGraphics(mc, oldGfx);
            mc.levelRenderer = oldRenderer;
            mc.level = oldLevel;
            mc.options.setCameraType(oldType);
            mainCam.eyeHeight = oldEye;
            mainCam.eyeHeightOld = oldEyeOld;
            oldTarget.bindWrite(true);
        }
    }

    // =========================================================
    // Camera world
    // =========================================================

    private static void ensureWorld(Minecraft mc) {
        if (camLevel != null && camParent == mc.level) return;
        if (camRenderer != null) camRenderer.close();

        GraphicsStatus oldGfx = setGraphics(mc, GraphicsStatus.FANCY);
        try {
            camRenderer = new LevelRenderer(mc, mc.getEntityRenderDispatcher(),
                    mc.getBlockEntityRenderDispatcher(), mc.renderBuffers());
            camRenderer.onResourceManagerReload(mc.getResourceManager());
        } finally {
            setGraphics(mc, oldGfx);
        }

        ClientLevel main = mc.level;
        int dist = CameraConfig.SEND_RADIUS;
        camLevel = new ClientLevel(mc.getConnection(), main.getLevelData(), main.dimension(),
                main.dimensionTypeRegistration(), dist, dist, mc::getProfiler, camRenderer, false, 0L);
        camRenderer.setLevel(camLevel);
        camLevel.getChunkSource().updateViewCenter(CameraConfig.chunk().x, CameraConfig.chunk().z);
        camParent = main;
        camEntity = null;
        camEntities.clear();
    }

    // =========================================================
    // Chunks
    // =========================================================

    /** Called (on the client thread) when a camera chunk payload arrives. */
    public static void acceptChunk(ClientboundLevelChunkWithLightPacket pkt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null) return;
        ensureWorld(mc);

        int x = pkt.getX(), z = pkt.getZ();
        var data = pkt.getChunkData();
        camLevel.getChunkSource().replaceWithPacketData(
                x, z, data.getReadBuffer(), data.getHeightmaps(), data.getBlockEntitiesTagsConsumer(x, z));

        var light = pkt.getLightData();
        camLevel.queueLightUpdate(() -> {
            LevelLightEngine engine = camLevel.getLightEngine();
            readLight(x, z, engine, LightLayer.SKY,
                    light.getSkyYMask(), light.getEmptySkyYMask(), light.getSkyUpdates().iterator());
            readLight(x, z, engine, LightLayer.BLOCK,
                    light.getBlockYMask(), light.getEmptyBlockYMask(), light.getBlockUpdates().iterator());
            camLevel.onChunkLoaded(new ChunkPos(x, z));

            LevelChunk chunk = camLevel.getChunkSource().getChunk(x, z, false);
            if (chunk != null) {
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    int sy = camLevel.getSectionYFromSectionIndex(i);
                    engine.updateSectionStatus(SectionPos.of(x, sy, z), sections[i].hasOnlyAir());
                    camLevel.setSectionDirtyWithNeighbors(x, sy, z);
                }
                engine.setLightEnabled(chunk.getPos(), true);
            }
        });
    }

    private static void readLight(int x, int z, LevelLightEngine engine, LightLayer layer,
                                  BitSet mask, BitSet emptyMask, Iterator<byte[]> updates) {
        for (int i = 0; i < engine.getLightSectionCount(); i++) {
            int sy = engine.getMinLightSection() + i;
            boolean has = mask.get(i), empty = emptyMask.get(i);
            if (has || empty) {
                engine.queueSectionData(layer, SectionPos.of(x, sy, z),
                        has ? new DataLayer(updates.next().clone()) : new DataLayer());
                camLevel.setSectionDirtyWithNeighbors(x, sy, z);
            }
        }
    }

    // =========================================================
    // Blocks
    // =========================================================

    public static void acceptBlocks(List<CameraBlocksPayload.Change> changes) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null) return;
        ensureWorld(mc);

        for (CameraBlocksPayload.Change c : changes) {
            camLevel.setServerVerifiedBlockState(c.pos(), Block.stateById(c.stateId()), 19);
        }
    }

    // =========================================================
    // Entities
    // =========================================================

    /** Full snapshot every tick: create / move what is listed, remove what is not. */
    public static void acceptEntities(List<CameraEntitiesPayload.Snap> snaps) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null) return;
        ensureWorld(mc);

        IntSet seen = new IntOpenHashSet();

        for (CameraEntitiesPayload.Snap s : snaps) {
            seen.add(s.id());
            Entity e = camEntities.get(s.id());
            boolean created = false;

            if (e == null || e.level() != camLevel) {
                if (EntityType.PLAYER.equals(BuiltInRegistries.ENTITY_TYPE.get(s.type()))) {
                    e = new RemotePlayer(camLevel, new GameProfile(s.uuid(), s.name()));
                } else {
                    e = BuiltInRegistries.ENTITY_TYPE.get(s.type()).create(camLevel);
                }
                if (e == null) continue;
                e.setId(s.id());
                e.setUUID(s.uuid());
                created = true;
            } else {
                e.setOldPosAndRot();
                e.tickCount++;
                if (e instanceof LivingEntity le) {   // remember the previous values for interpolation
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

                if (created) {   // no interpolation from default values
                    le.yBodyRotO = le.yBodyRot;
                    le.yHeadRotO = le.yHeadRot;
                    le.oAttackAnim = le.attackAnim;
                } else {
                    le.calculateEntityAnimation(le instanceof FlyingAnimal);   // walk / limb swing
                }
            }

            if (created) {
                e.setOldPosAndRot();
                camLevel.addEntity(e);
                camEntities.put(s.id(), e);
            }
        }

        for (Iterator<Int2ObjectMap.Entry<Entity>> it = camEntities.int2ObjectEntrySet().iterator(); it.hasNext(); ) {
            Int2ObjectMap.Entry<Entity> en = it.next();
            if (!seen.contains(en.getIntKey())) {
                camLevel.removeEntity(en.getIntKey(), Entity.RemovalReason.DISCARDED);
                it.remove();
            }
        }
    }

    private static GraphicsStatus setGraphics(Minecraft mc, GraphicsStatus status) {
        GraphicsStatus old = mc.options.graphicsMode().get();
        mc.options.graphicsMode().value = status;   // direct write: set() would trigger a full renderer reload
        return old;
    }

    public static boolean isCameraLevel(net.minecraft.world.level.Level level) {
        return level != null && level == camLevel;
    }
}