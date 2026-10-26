package net.tui.aerocomputers.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceLocation;
import net.tui.aerocomputers.AeroComputers;

public record CameraChunkPayload(ClientboundLevelChunkWithLightPacket packet) implements CustomPacketPayload {
    public static final Type<CameraChunkPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AeroComputers.MOD_ID, "camera_chunk"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CameraChunkPayload> STREAM_CODEC =
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.map(CameraChunkPayload::new, CameraChunkPayload::packet);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}