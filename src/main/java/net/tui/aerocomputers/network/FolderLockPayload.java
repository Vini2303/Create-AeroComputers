package net.tui.aerocomputers.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.screen.custom.BoardComputerMenu;
import net.tui.aerocomputers.script.ComputerFiles;
import net.tui.aerocomputers.script.FolderLocks;
import net.tui.aerocomputers.script.SandboxFileSystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** path = folder (relative to the computer root), password = plain text typed by the player. */
public record FolderLockPayload(BlockPos pos, Action action, String path, String password)
        implements CustomPacketPayload {

    public enum Action { SET, UNLOCK, RELOCK, REMOVE }

    private static final int MIN_PASSWORD_LENGTH = 3;

    public static final Type<FolderLockPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AeroComputers.MOD_ID, "folder_lock"));

    public static final StreamCodec<FriendlyByteBuf, FolderLockPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, FolderLockPayload::pos,
                    ByteBufCodecs.idMapper(i -> Action.values()[i], Action::ordinal), FolderLockPayload::action,
                    ByteBufCodecs.stringUtf8(512), FolderLockPayload::path,
                    ByteBufCodecs.stringUtf8(64), FolderLockPayload::password,
                    FolderLockPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(FolderLockPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            // The player must have THIS computer's menu open (also works on sub-levels).
            if (!(player.containerMenu instanceof BoardComputerMenu menu)
                    || !menu.blockEntity.getBlockPos().equals(p.pos())) return;

            try {
                FolderLocks locks = FolderLocks.get(player.server);
                FolderLocks.Session id = FolderLocks.sessionOf(player);

                String rel = p.path().replace('\\', '/');
                while (rel.startsWith("/")) rel = rel.substring(1);

                // must be a real folder inside the sandbox (and not the root itself)
                Path root = SandboxFileSystem.rootFor(player.server);
                Path dir = root.resolve(rel).normalize();
                boolean validFolder = !rel.isEmpty() && dir.startsWith(root)
                        && !dir.equals(root) && Files.isDirectory(dir);

                boolean ok = validFolder && switch (p.action()) {
                    case SET -> p.password().length() >= MIN_PASSWORD_LENGTH
                            && locks.mayTouch(id, rel)             // locks above it must be open
                            && locks.setPassword(id, rel, p.password());
                    case UNLOCK -> locks.unlock(id, rel, p.password());
                    case REMOVE -> locks.removePassword(id, rel, p.password());
                    case RELOCK -> { locks.relock(id, rel); yield true; }
                };

                if (!ok) {
                    player.displayClientMessage(
                            Component.literal("§cWrong password or action not allowed"), true);
                }

                // resend the tree so the client sees the new lock state / contents
                ComputerFiles.perform(player, FileActionPayload.Action.LIST, "", "");
            } catch (Exception e) {
                ComputerFiles.report(player, e);
            }
        });
    }
}