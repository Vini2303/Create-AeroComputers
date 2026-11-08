package net.tui.aerocomputers.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.client.ClientFileState;
import net.tui.aerocomputers.script.FolderLocks;

import java.util.ArrayList;
import java.util.List;

public record FileTreePayload(List<Entry> entries) implements CustomPacketPayload {

    /** lockState: 0 = not locked, 1 = locked, 2 = locked but unlocked for this player */
    public record Entry(String path, boolean dir, int lockState) {
        /** Keeps existing `new Entry(path, dir)` calls compiling. */
        public Entry(String path, boolean dir) { this(path, dir, 0); }

        public static final StreamCodec<io.netty.buffer.ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(1024), Entry::path,
                ByteBufCodecs.BOOL, Entry::dir,
                ByteBufCodecs.VAR_INT, Entry::lockState,
                Entry::new);
    }

    /** Hides the contents of folders this player has not unlocked and adds each folder's lock state. */
    public static FileTreePayload forPlayer(ServerPlayer player, List<Entry> all) {
        FolderLocks locks = FolderLocks.get(player.server);
        var id = FolderLocks.sessionOf(player);
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (!locks.isVisible(id, e.path())) continue;
            out.add(new Entry(e.path(), e.dir(), e.dir() ? locks.stateOf(id, e.path()) : 0));
        }
        return new FileTreePayload(out);
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