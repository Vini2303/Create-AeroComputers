package net.tui.aerocomputers.network;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.block.entity.CameraBlockEntity;
import net.tui.aerocomputers.client.CameraConfig;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.*;

/**
 * SERVER side of the camera feed. No client imports in here.
 *
 * Nothing is streamed until a client says "I am watching the camera block at X"
 * (CameraInterestPayload, sent every 10 ticks while the camera is on screen).
 * For every watched camera this class then
 *   - works out where the camera really is (Sable sub-levels are projected to world coordinates),
 *   - keeps the chunks around it loaded (LOAD_DISTANCE),
 *   - sends already-loaded chunks within SEND_RADIUS (nearest first, a few per tick),
 *   - sends entity snapshots every tick, block changes every tick, block entity tags 4x per second.
 * Cameras next to the player and cameras thousands of blocks away are handled identically.
 *
 * Location: net/tui/aerocomputers/network/CameraChunkManager.java
 * Nothing has to call this from the computers any more.
 */
@EventBusSubscriber(modid = AeroComputers.MOD_ID)
public final class CameraChunkManager {
    private CameraChunkManager() {}

    public static final TicketType<ChunkPos> TICKET =
            TicketType.create("aerocomputers_cam", Comparator.comparingLong(ChunkPos::toLong), 60);

    private static final int CHUNKS_PER_TICK = 4;          // per viewer and camera
    private static final int VIEWER_TIMEOUT = 100;         // ticks without heartbeat -> viewer is dropped
    private static final int BE_SYNC_INTERVAL = 5;         // 4 times per second
    private static final int BE_RADIUS = 4;                // chunks around the camera
    private static final int MAX_BLOCK_CHANGES = 2000;
    private static final int MAX_CAMERAS_PER_PLAYER = 8;

    private static final class Watched {
        final BlockPos id;
        final Map<UUID, Long> viewers = new HashMap<>();            // player -> game time of last heartbeat
        final Map<UUID, Set<Long>> sentChunks = new HashMap<>();    // player -> chunks already sent
        final Set<BlockPos> changed = new LinkedHashSet<>();        // block changes since the last tick
        final Map<BlockPos, CompoundTag> lastBeTags = new HashMap<>();
        final Map<UUID, Set<UUID>> trackedSubLevels = new HashMap<>();   // player -> sub-levels already sent
        Map<UUID, ServerSubLevel> near = new LinkedHashMap<>();            // sub-levels in range this tick
        Vec3 worldPos = Vec3.ZERO;
        ChunkPos chunk = new ChunkPos(0, 0);

        Watched(BlockPos id) { this.id = id; }
    }

    private static final Map<GlobalPos, Watched> watched = new HashMap<>();

    // =========================================================
    // client heartbeat
    // =========================================================

    public static void onInterest(ServerPlayer player, BlockPos id, boolean fresh) {
        ServerLevel level = player.serverLevel();
        // reject only what is known to be something else; an unloaded camera block gets loaded below
        if (level.hasChunkAt(id) && !(level.getBlockEntity(id) instanceof CameraBlockEntity)) return;

        UUID uuid = player.getUUID();
        GlobalPos key = GlobalPos.of(level.dimension(), id.immutable());
        Watched w = watched.get(key);
        boolean already = w != null && w.viewers.containsKey(uuid);

        if (!already) {
            long mine = watched.values().stream().filter(x -> x.viewers.containsKey(uuid)).count();
            if (mine >= MAX_CAMERAS_PER_PLAYER) return;
        }
        if (w == null) {
            w = new Watched(id.immutable());
            watched.put(key, w);
        }

        w.viewers.put(uuid, level.getGameTime());
        if (!already || fresh) {              // new viewer / new client world: send everything again
            w.sentChunks.remove(uuid);
            w.trackedSubLevels.remove(uuid);
            w.lastBeTags.clear();
        }
    }

    // =========================================================
    // tick
    // =========================================================

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) tick(level);
    }

    private static void tick(ServerLevel level) {
        if (watched.isEmpty()) return;

        long time = level.getGameTime();
        ResourceKey<Level> dim = level.dimension();

        for (Iterator<Map.Entry<GlobalPos, Watched>> it = watched.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<GlobalPos, Watched> entry = it.next();
            if (!entry.getKey().dimension().equals(dim)) continue;
            Watched w = entry.getValue();

            w.viewers.values().removeIf(t -> time - t > VIEWER_TIMEOUT);
            w.sentChunks.keySet().retainAll(w.viewers.keySet());
            w.trackedSubLevels.keySet().retainAll(w.viewers.keySet());

            if (w.viewers.isEmpty()) {
                it.remove();
                continue;
            }

            if (!level.hasChunkAt(w.id)) {
                // the camera block itself is not loaded (nobody is near it): load it, stream once it is there
                if (time % 20 == 0) {
                    ChunkPos own = new ChunkPos(w.id);
                    level.getChunkSource().addRegionTicket(TICKET, own, CameraConfig.LOAD_DISTANCE, own);
                }
                continue;
            }

            if (!(level.getBlockEntity(w.id) instanceof CameraBlockEntity)) {   // the camera was removed
                it.remove();
                continue;
            }
            tickCamera(level, w, time);
        }
    }

    private static void tickCamera(ServerLevel level, Watched w, long time) {
        // block position -> real world position (the camera may sit on a Sable sub-level)
        Vector3d p = SableCompanion.INSTANCE.projectOutOfSubLevel(
                level,
                new Vector3d(w.id.getX() + 0.5, w.id.getY() + 0.5, w.id.getZ() + 0.5),
                new Vector3d());
        w.worldPos = new Vec3(p.x, p.y, p.z);
        w.chunk = new ChunkPos(BlockPos.containing(w.worldPos));
        w.near = findSubLevels(level, w);

        if (CameraConfig.DEBUG && time % 100 == 0) debug(level, w);

        if (time % 20 == 0) {
            level.getChunkSource().addRegionTicket(TICKET, w.chunk, CameraConfig.LOAD_DISTANCE, w.chunk);

            // a moving camera: forget chunks it has left behind, so they are sent again when it comes back
            int keep = CameraConfig.SEND_RADIUS + 1;
            for (Set<Long> sent : w.sentChunks.values()) {
                sent.removeIf(k -> Math.max(Math.abs(ChunkPos.getX(k) - w.chunk.x),
                        Math.abs(ChunkPos.getZ(k) - w.chunk.z)) > keep);
            }
        }

        List<CameraBlocksPayload.Change> changes = collectBlockChanges(level, w);
        CameraEntitiesPayload entities = collectEntities(level, w);
        List<CameraBlockEntitiesPayload.Entry> beUpdates =
                time % BE_SYNC_INTERVAL == 0 ? collectBlockEntities(level, w, time) : List.of();

        for (ServerPlayer player : level.players()) {
            if (!w.viewers.containsKey(player.getUUID())) continue;

            sendChunks(level, player, w);
            sendSubLevels(level, player, w);
            if (!changes.isEmpty())
                PacketDistributor.sendToPlayer(player, new CameraBlocksPayload(w.id, changes));
            if (!beUpdates.isEmpty())
                PacketDistributor.sendToPlayer(player, new CameraBlockEntitiesPayload(w.id, beUpdates));
            PacketDistributor.sendToPlayer(player, entities);
        }
    }

    // =========================================================
    // chunks
    // =========================================================

    private static void sendChunks(ServerLevel level, ServerPlayer player, Watched w) {
        Set<Long> done = w.sentChunks.computeIfAbsent(player.getUUID(), k -> new HashSet<>());
        ChunkPos c = w.chunk;

        int budget = CHUNKS_PER_TICK;
        int radius = CameraConfig.SEND_RADIUS;
        for (int r = 0; r <= radius && budget > 0; r++) {            // nearest first
            for (int dx = -r; dx <= r && budget > 0; dx++) {
                for (int dz = -r; dz <= r && budget > 0; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;

                    long key = ChunkPos.asLong(c.x + dx, c.z + dz);
                    if (done.contains(key)) continue;

                    LevelChunk chunk = level.getChunkSource().getChunkNow(c.x + dx, c.z + dz);
                    if (chunk == null) continue;   // not loaded (by anybody), try again later

                    PacketDistributor.sendToPlayer(player, new CameraChunkPayload(w.id,
                            new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null)));
                    done.add(key);
                    budget--;
                }
            }
        }
    }

    // =========================================================
    // entities
    // =========================================================

    private static CameraEntitiesPayload collectEntities(ServerLevel level, Watched w) {
        Vec3 pos = w.worldPos;
        double r = CameraConfig.ENTITY_RADIUS;
        AABB box = new AABB(pos.x - r, pos.y - r, pos.z - r, pos.x + r, pos.y + r, pos.z + r);

        List<CameraEntitiesPayload.Snap> list = new ArrayList<>();
        for (Entity e : level.getEntities((Entity) null, box, ent -> !ent.isSpectator())) {
            float body = e.getYRot(), attack = 0;
            boolean off = false;
            int hurt = 0, death = 0;
            if (e instanceof LivingEntity le) {
                body = le.yBodyRot;
                attack = le.attackAnim;
                off = le.swingingArm == InteractionHand.OFF_HAND;
                hurt = le.hurtTime;
                death = le.deathTime;
            }

            list.add(new CameraEntitiesPayload.Snap(
                    e.getId(), e.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()),
                    e instanceof Player p ? p.getGameProfile().getName() : "",
                    e.getX(), e.getY(), e.getZ(),
                    e.getYRot(), e.getXRot(), e.getYHeadRot(),
                    body, attack, off, hurt, death,
                    allData(e)));
        }
        return new CameraEntitiesPayload(w.id, w.chunk.x, w.chunk.z, list);
    }

    private static List<SynchedEntityData.DataValue<?>> allData(Entity e) {
        List<SynchedEntityData.DataValue<?>> out = new ArrayList<>();
        for (SynchedEntityData.DataItem<?> item : e.getEntityData().itemsById) {
            if (item != null) out.add(item.value());   // or DataValue.create(item.getAccessor(), item.getValue())
        }
        return out;
    }

    // =========================================================
    // blocks and block entities
    // =========================================================

    private static List<CameraBlocksPayload.Change> collectBlockChanges(ServerLevel level, Watched w) {
        List<CameraBlocksPayload.Change> out = new ArrayList<>();
        Iterator<BlockPos> it = w.changed.iterator();
        while (it.hasNext() && out.size() < MAX_BLOCK_CHANGES) {
            BlockPos pos = it.next();
            it.remove();
            out.add(new CameraBlocksPayload.Change(pos, Block.getId(level.getBlockState(pos))));
        }
        return out;
    }

    private static List<CameraBlockEntitiesPayload.Entry> collectBlockEntities(ServerLevel level, Watched w, long time) {
        if (time % 100 == 0) w.lastBeTags.clear();   // forget everything now and then, so it is all re-sent once
        List<CameraBlockEntitiesPayload.Entry> out = new ArrayList<>();
        ChunkPos c = w.chunk;

        for (int dx = -BE_RADIUS; dx <= BE_RADIUS; dx++) {
            for (int dz = -BE_RADIUS; dz <= BE_RADIUS; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(c.x + dx, c.z + dz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    CompoundTag tag = be.getUpdateTag(level.registryAccess());
                    if (tag.isEmpty()) continue;   // nothing to sync for this block entity
                    BlockPos pos = be.getBlockPos().immutable();
                    CompoundTag old = w.lastBeTags.put(pos, tag);
                    if (!tag.equals(old)) out.add(new CameraBlockEntitiesPayload.Entry(pos, tag));
                }
            }
        }

        // block entities inside the plots of mirrored sub-levels (shafts, cogs, ... on an aircraft)
        for (ServerSubLevel s : w.near.values()) {
            for (PlotChunkHolder holder : s.getPlot().getLoadedChunks()) {
                for (BlockEntity be : holder.getChunk().getBlockEntities().values()) {
                    CompoundTag tag = be.getUpdateTag(level.registryAccess());
                    if (tag.isEmpty()) continue;
                    BlockPos pos = be.getBlockPos().immutable();
                    CompoundTag old = w.lastBeTags.put(pos, tag);
                    if (!tag.equals(old)) out.add(new CameraBlockEntitiesPayload.Entry(pos, tag));
                }
            }
        }
        return out;
    }

    // =========================================================
    // Sable sub-levels (aircraft / contraptions)
    // =========================================================

    private static Map<UUID, ServerSubLevel> findSubLevels(ServerLevel level, Watched w) {
        Map<UUID, ServerSubLevel> out = new LinkedHashMap<>();
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return out;

        double r = CameraConfig.SUBLEVEL_RADIUS;
        ChunkPos camPlotChunk = new ChunkPos(w.id);
        for (SubLevel sl : container.getAllSubLevels()) {
            if (sl.isRemoved() || !(sl instanceof ServerSubLevel s)) continue;
            Vector3dc p = s.logicalPose().position();
            boolean near = p.distanceSquared(w.worldPos.x, w.worldPos.y, w.worldPos.z) <= r * r;
            boolean hostsCamera = s.getPlot().contains(camPlotChunk);   // the camera block itself sits on it
            if (near || hostsCamera) out.put(s.getUniqueId(), s);
        }
        return out;
    }

    /** Same as Sable's own tracking: start packet, all plot chunks, finalize; afterwards only poses. */
    private static void sendSubLevels(ServerLevel level, ServerPlayer player, Watched w) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;

        Set<UUID> tracked = w.trackedSubLevels.computeIfAbsent(player.getUUID(), k -> new HashSet<>());
        int tick = (int) level.getGameTime();

        List<CameraSubLevelsPayload.Track> tracks = new ArrayList<>();
        List<ServerSubLevel> fresh = new ArrayList<>();
        for (ServerSubLevel s : w.near.values()) {
            boolean isNew = tracked.add(s.getUniqueId());
            if (isNew) fresh.add(s);
            tracks.add(new CameraSubLevelsPayload.Track(
                    s.getUniqueId(), plotLong(s, container), isNew,
                    new Pose3d(s.lastPose()), new Pose3d(s.logicalPose()),   // copies: encoding happens later
                    isNew ? s.getPlot().getBoundingBox() : null,
                    s.getName()));
        }

        List<UUID> removed = new ArrayList<>();
        for (Iterator<UUID> it = tracked.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (!w.near.containsKey(id)) {
                removed.add(id);
                it.remove();
            }
        }

        if (!tracks.isEmpty() || !removed.isEmpty())
            PacketDistributor.sendToPlayer(player, new CameraSubLevelsPayload(w.id, tick, tracks, List.of(), removed));

        if (!fresh.isEmpty()) {
            List<UUID> done = new ArrayList<>();
            for (ServerSubLevel s : fresh) {
                LevelPlot plot = s.getPlot();
                for (PlotChunkHolder holder : plot.getLoadedChunks()) {
                    PacketDistributor.sendToPlayer(player, new CameraChunkPayload(w.id,
                            new ClientboundLevelChunkWithLightPacket(holder.getChunk(), plot.getLightEngine(), null, null)));
                }
                done.add(s.getUniqueId());
            }
            PacketDistributor.sendToPlayer(player, new CameraSubLevelsPayload(w.id, tick, List.of(), done, List.of()));
        }
    }

    private static long plotLong(ServerSubLevel s, SubLevelContainer container) {
        Vector2i origin = container.getOrigin();
        ChunkPos plotPos = s.getPlot().plotPos;
        return ChunkPos.asLong(plotPos.x - origin.x, plotPos.z - origin.y);
    }

    private static void debug(ServerLevel level, Watched w) {
        int loaded = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (level.getChunkSource().getChunkNow(w.chunk.x + dx, w.chunk.z + dz) != null) loaded++;
        int sent = w.sentChunks.values().stream().mapToInt(Set::size).sum();
        AeroComputers.LOGGER.info("[cam-server {}] viewers={} worldPos={} chunk={},{} loaded3x3={}/9 chunksSent={} subLevels={}",
                w.id, w.viewers.size(), w.worldPos, w.chunk.x, w.chunk.z, loaded, sent, w.near.size());
    }

    // =========================================================
    // events
    // =========================================================

    /**
     * Called by ServerLevelMixin from ServerLevel.sendBlockUpdated: every block change that has to reach
     * clients, including block placements by sub-level assembly / disassembly (which never fire neighbour events).
     */
    public static void onBlockChanged(ServerLevel level, BlockPos pos) {
        if (watched.isEmpty()) return;

        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        int r = CameraConfig.SEND_RADIUS;

        // a block inside the plot of a sub-level?
        UUID plotOwner = null;
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null && container.inBounds(pos)) {
            LevelPlot plot = container.getPlot(new ChunkPos(pos));
            if (plot != null) plotOwner = plot.getSubLevel().getUniqueId();
        }

        for (Map.Entry<GlobalPos, Watched> e : watched.entrySet()) {
            if (!e.getKey().dimension().equals(level.dimension())) continue;
            if (plotOwner != null) {
                if (e.getValue().near.containsKey(plotOwner)) e.getValue().changed.add(pos.immutable());
                continue;
            }
            ChunkPos c = e.getValue().chunk;
            if (Math.abs(cx - c.x) <= r && Math.abs(cz - c.z) <= r) {
                e.getValue().changed.add(pos.immutable());
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        for (Watched w : watched.values()) {
            w.viewers.remove(uuid);
            w.sentChunks.remove(uuid);
            w.trackedSubLevels.remove(uuid);
        }
    }
}