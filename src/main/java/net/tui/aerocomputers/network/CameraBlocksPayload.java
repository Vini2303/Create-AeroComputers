package net.tui.aerocomputers.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.tui.aerocomputers.AeroComputers;

import java.util.List;

/** Block changes inside the camera area, sent once per server tick when something changed. */
public record CameraBlocksPayload(List<Change> changes) implements CustomPacketPayload {

    public record Change(BlockPos pos, int stateId) {}

    public static final Type<CameraBlocksPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AeroComputers.MOD_ID, "camera_blocks"));

    private static final StreamCodec<ByteBuf, Change> CHANGE_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, Change::pos,
            ByteBufCodecs.VAR_INT, Change::stateId,
            Change::new);

    public static final StreamCodec<ByteBuf, CameraBlocksPayload> STREAM_CODEC =
            CHANGE_CODEC.apply(ByteBufCodecs.list()).map(CameraBlocksPayload::new, CameraBlocksPayload::changes);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}