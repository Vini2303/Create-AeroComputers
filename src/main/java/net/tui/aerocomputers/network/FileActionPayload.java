package net.tui.aerocomputers.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.script.ComputerFiles;
import net.tui.aerocomputers.screen.custom.BoardComputerMenu;

public record FileActionPayload(BlockPos pos, Action action, String path, String extra)
        implements CustomPacketPayload {

    /** path/extra meaning:
     *  MKDIR/NEW_FILE: path = parent folder, extra = name | DELETE/READ: path
     *  RENAME: path = item, extra = new name | MOVE: path = item, extra = destination folder
     *  WRITE: path = file, extra = content */
    public enum Action { LIST, MKDIR, NEW_FILE, DELETE, RENAME, MOVE, READ, WRITE }

    public static final Type<FileActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AeroComputers.MOD_ID, "file_action"));

    public static final StreamCodec<FriendlyByteBuf, FileActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, FileActionPayload::pos,
                    ByteBufCodecs.idMapper(i -> Action.values()[i], Action::ordinal), FileActionPayload::action,
                    ByteBufCodecs.stringUtf8(1024), FileActionPayload::path,
                    ByteBufCodecs.stringUtf8(32767), FileActionPayload::extra,
                    FileActionPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(FileActionPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            // The player must have THIS computer's menu open. (Works on sub-levels too,
            // unlike a raw distance check on plot coordinates.)
            if (!(player.containerMenu instanceof BoardComputerMenu menu)
                    || !menu.blockEntity.getBlockPos().equals(p.pos())) return;
            try {
                ComputerFiles.perform(player, p.action(), p.path(), p.extra());

                var be = menu.blockEntity;
                String path = clean(p.path());
                switch (p.action()) {
                    case READ -> be.setProgramPath(path);          // geöffnete Datei wird zum Programm
                    case WRITE -> be.onFileWritten(path);
                    case DELETE -> be.onPathDeleted(path);
                    case RENAME -> {
                        int i = path.lastIndexOf('/');
                        be.onPathMoved(path, (i < 0 ? "" : path.substring(0, i + 1)) + p.extra().trim());
                    }
                    case MOVE -> {
                        String dest = clean(p.extra());
                        String name = path.substring(path.lastIndexOf('/') + 1);
                        be.onPathMoved(path, dest.isEmpty() ? name : dest + "/" + name);
                    }
                    default -> { }
                }
            } catch (Exception e) {
                ComputerFiles.report(player, e);
            }
        });
    }

    private static String clean(String s) {
        String c = s == null ? "" : s.replace('\\', '/');
        while (c.startsWith("/")) c = c.substring(1);
        return c;
    }
}