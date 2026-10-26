package net.tui.aerocomputers.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.client.CameraConfig;

import java.util.*;

/**
 * SERVER side of the camera feed. No client imports in here.
 * - keeps a 5x5 area around the camera loaded (so the inner 3x3 can be rendered)
 * - sends already-loaded chunks within SEND_RADIUS to every player in the level
 * - sends entity snapshots every tick and block changes every tick
 *
 * Location: net/tui/aerocomputers/network/CameraChunkManager.java
 * Called from BoardComputerBlockEntity.tick() (once per game tick, extra calls are ignored).
 */
@EventBusSubscriber(modid = AeroComputers.MOD_ID)
public final class CameraChunkManager {
    private CameraChunkManager() {}

    public static final TicketType<ChunkPos> TICKET =
            TicketType.create("aerocomputers_cam", Comparator.comparingLong(ChunkPos::toLong), 60);

    private static final int CHUNKS_PER_TICK = 4;     // spreads the initial burst over a few seconds
    private static final int REFRESH_TICKS = 100;     // inner 5x5 is re-sent every 5 s
    private static final int MAX_BLOCK_CHANGES = 2000;

    private static final Map<UUID, Set<Long>> sentChunks = new HashMap<>();
    private static final Set<GlobalPos> changedBlocks = new LinkedHashSet<>();
    private static long lastTick = -1;
    private static long lastRefresh = 0;

    public static void tick(ServerLevel level) {
        long time = level.getGameTime();
        if (time == lastTick) return;   // several computers may call this in the same tick
        lastTick = time;

        ChunkPos c = CameraConfig.chunk();

        if (time % 20 == 0)
            level.getChunkSource().addRegionTicket(TICKET, c, CameraConfig.LOAD_DISTANCE, c);

        boolean refresh = time - lastRefresh >= REFRESH_TICKS;
        if (refresh) lastRefresh = time;

        List<CameraBlocksPayload.Change> changes = collectBlockChanges(level);
        CameraEntitiesPayload entities = collectEntities(level);

        for (ServerPlayer player : level.players()) {
            sendChunks(level, player, c, refresh);
            if (!changes.isEmpty())
                PacketDistributor.sendToPlayer(player, new CameraBlocksPayload(changes));
            PacketDistributor.sendToPlayer(player, entities);
        }
    }

    private static void sendChunks(ServerLevel level, ServerPlayer player, ChunkPos c, boolean refresh) {
        Set<Long> done = sentChunks.computeIfAbsent(player.getUUID(), k -> new HashSet<>());

        if (refresh) {   // forget the inner 5x5 so it gets queued again
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    done.remove(ChunkPos.asLong(c.x + dx, c.z + dz));
        }

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

                    PacketDistributor.sendToPlayer(player, new CameraChunkPayload(
                            new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null)));
                    done.add(key);
                    budget--;
                }
            }
        }
    }

    private static CameraEntitiesPayload collectEntities(ServerLevel level) {
        Vec3 pos = CameraConfig.POS;
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
        return new CameraEntitiesPayload(list);
    }

    private static List<CameraBlocksPayload.Change> collectBlockChanges(ServerLevel level) {
        List<CameraBlocksPayload.Change> out = new ArrayList<>();
        Iterator<GlobalPos> it = changedBlocks.iterator();
        while (it.hasNext() && out.size() < MAX_BLOCK_CHANGES) {
            GlobalPos gp = it.next();
            if (!gp.dimension().equals(level.dimension())) continue;   // belongs to another dimension
            it.remove();
            out.add(new CameraBlocksPayload.Change(gp.pos(), Block.getId(level.getBlockState(gp.pos()))));
        }
        return out;
    }

    // ---------------------------------------------------------
    // events
    // ---------------------------------------------------------

    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = event.getPos();
        ChunkPos c = CameraConfig.chunk();
        int r = CameraConfig.SEND_RADIUS;
        if (Math.abs((pos.getX() >> 4) - c.x) > r || Math.abs((pos.getZ() >> 4) - c.z) > r) return;
        changedBlocks.add(GlobalPos.of(level.dimension(), pos.immutable()));
    }

    // the client's camera world is rebuilt in these cases, so everything must be sent again
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        sentChunks.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        sentChunks.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        sentChunks.remove(event.getEntity().getUUID());
    }

    private static List<SynchedEntityData.DataValue<?>> allData(Entity e) {
        List<SynchedEntityData.DataValue<?>> out = new ArrayList<>();
        for (SynchedEntityData.DataItem<?> item : e.getEntityData().itemsById) {
            if (item != null) out.add(item.value());   // or DataValue.create(item.getAccessor(), item.getValue())
        }
        return out;
    }
}