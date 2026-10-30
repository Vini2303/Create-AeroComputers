package net.tui.aerocomputers.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.client.ClientFileState;

public record FileContentPayload(String path, String content) implements CustomPacketPayload {

    public static final Type<FileContentPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AeroComputers.MOD_ID, "file_content"));

    public static final StreamCodec<FriendlyByteBuf, FileContentPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(1024), FileContentPayload::path,
                    ByteBufCodecs.stringUtf8(32767), FileContentPayload::content,
                    FileContentPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(FileContentPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ClientFileState.openPath = p.path();
            ClientFileState.openContent = p.content();
            ClientFileState.contentVersion++;
        });
    }
}