package net.tui.aerocomputers.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.client.ClientFileState;

import java.util.List;

public record FileTreePayload(List<Entry> entries) implements CustomPacketPayload {

    public record Entry(String path, boolean dir) {
        public static final StreamCodec<io.netty.buffer.ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(1024), Entry::path,
                ByteBufCodecs.BOOL, Entry::dir,
                Entry::new);
    }

    public static final Type<FileTreePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AeroComputers.MOD_ID, "file_tree"));

    public static final StreamCodec<FriendlyByteBuf, FileTreePayload> STREAM_CODEC =
            StreamCodec.composite(
                    Entry.STREAM_CODEC.apply(ByteBufCodecs.list(2000)), FileTreePayload::entries,
                    FileTreePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(FileTreePayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ClientFileState.entries = p.entries();
            ClientFileState.treeVersion++;
        });
    }
}