package net.tui.aerocomputers.network;

import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.util.SableBufferUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.tui.aerocomputers.AeroComputers;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sable sub-levels (aircraft, contraptions) near one camera.
 * Mirrors what Sable's own start-tracking / finalize / stop-tracking packets do, but for the camera world:
 *  - tracks:    poses (every tick); for new sub-levels also the plot coordinate, bounds and name
 *  - finalized: sub-levels whose plot chunks have all been sent, they can build their render data now
 *  - removed:   sub-levels that left the camera's range
 */
public record CameraSubLevelsPayload(BlockPos cam, int gameTick, List<Track> tracks,
                                     List<UUID> finalized, List<UUID> removed) implements CustomPacketPayload {

    public record Track(UUID uuid, long plot, boolean isNew, Pose3d lastPose, Pose3d pose,
                        @Nullable BoundingBox3ic bounds, @Nullable String name) {}

    public static final Type<CameraSubLevelsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AeroComputers.MOD_ID, "camera_sub_levels"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CameraSubLevelsPayload> STREAM_CODEC =
            StreamCodec.of(CameraSubLevelsPayload::write, CameraSubLevelsPayload::read);

    private static void write(RegistryFriendlyByteBuf b, CameraSubLevelsPayload p) {
        b.writeBlockPos(p.cam);
        b.writeInt(p.gameTick);

        b.writeVarInt(p.tracks.size());
        for (Track t : p.tracks) {
            b.writeUUID(t.uuid);
            b.writeLong(t.plot);
            b.writeBoolean(t.isNew);
            SableBufferUtils.write(b, t.lastPose);
            SableBufferUtils.write(b, t.pose);
            if (t.isNew) {
                SableBufferUtils.write(b, t.bounds);
                b.writeBoolean(t.name != null);
                if (t.name != null) b.writeUtf(t.name);
            }
        }

        b.writeVarInt(p.finalized.size());
        for (UUID id : p.finalized) b.writeUUID(id);

        b.writeVarInt(p.removed.size());
        for (UUID id : p.removed) b.writeUUID(id);
    }

    private static CameraSubLevelsPayload read(RegistryFriendlyByteBuf b) {
        BlockPos cam = b.readBlockPos();
        int tick = b.readInt();

        int n = b.readVarInt();
        List<Track> tracks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            UUID uuid = b.readUUID();
            long plot = b.readLong();
            boolean isNew = b.readBoolean();
            Pose3d last = SableBufferUtils.read(b, new Pose3d());
            Pose3d pose = SableBufferUtils.read(b, new Pose3d());
            BoundingBox3ic bounds = null;
            String name = null;
            if (isNew) {
                bounds = SableBufferUtils.read(b, new BoundingBox3i());
                if (b.readBoolean()) name = b.readUtf();
            }
            tracks.add(new Track(uuid, plot, isNew, last, pose, bounds, name));
        }

        int f = b.readVarInt();
        List<UUID> finalized = new ArrayList<>(f);
        for (int i = 0; i < f; i++) finalized.add(b.readUUID());

        int r = b.readVarInt();
        List<UUID> removed = new ArrayList<>(r);
        for (int i = 0; i < r; i++) removed.add(b.readUUID());

        return new CameraSubLevelsPayload(cam, tick, tracks, finalized, removed);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}