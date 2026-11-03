package net.tui.aerocomputers.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tui.aerocomputers.AeroComputers;

/**
 * Client -> server heartbeat: "I am currently looking at the camera block at this position".
 * 'fresh' is true for the first heartbeat after the client (re)created that camera's world:
 * the server then sends everything (chunks, sub-levels, block entities) again.
 */
public record CameraInterestPayload(BlockPos cam, boolean fresh) implements CustomPacketPayload {

    public static final Type<CameraInterestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AeroComputers.MOD_ID, "camera_interest"));

    public static final StreamCodec<ByteBuf, CameraInterestPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, CameraInterestPayload::cam,
            ByteBufCodecs.BOOL, CameraInterestPayload::fresh,
            CameraInterestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(CameraInterestPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                CameraChunkManager.onInterest(player, payload.cam(), payload.fresh());
            }
        });
    }
}