package net.tui.aerocomputers.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceLocation;
import net.tui.aerocomputers.AeroComputers;

/** One chunk for the camera world of the camera block at 'cam'. */
public record CameraChunkPayload(BlockPos cam, ClientboundLevelChunkWithLightPacket packet) implements CustomPacketPayload {

    public static final Type<CameraChunkPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AeroComputers.MOD_ID, "camera_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CameraChunkPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, CameraChunkPayload::cam,
                    ClientboundLevelChunkWithLightPacket.STREAM_CODEC, CameraChunkPayload::packet,
                    CameraChunkPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}