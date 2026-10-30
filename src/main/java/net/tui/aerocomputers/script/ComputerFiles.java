package net.tui.aerocomputers.script;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.tui.aerocomputers.network.FileActionPayload;
import net.tui.aerocomputers.network.FileContentPayload;
import net.tui.aerocomputers.network.FileTreePayload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Server-side file operations. Every path is resolved against the world's aerocomputers folder. */
public final class ComputerFiles {
    /** Serverbound packets are capped at ~32 KB, so editable files are too. */
    public static final int MAX_TEXT_BYTES = 32_000;
    private static final int MAX_ENTRIES = 2000;
    private static final int MAX_DEPTH = 8;

    private ComputerFiles() {}

    public static void perform(ServerPlayer player, FileActionPayload.Action action,
                               String path, String extra) throws IOException {
        Path root = root(player.server);

        switch (action) {
            case LIST -> { }
            case MKDIR -> Files.createDirectory(dir(root, path).resolve(validName(extra)));
            case NEW_FILE -> Files.createFile(dir(root, path).resolve(validName(extra)));
            case DELETE -> delete(root, path);
            case RENAME -> {
                Path src = resolve(root, path);
                if (src.equals(root)) throw new IllegalArgumentException("Cannot rename the root");
                Files.move(src, src.resolveSibling(validName(extra)));
            }
            case MOVE -> {
                Path src = resolve(root, path);
                Path destDir = dir(root, extra);
                if (src.equals(root) || destDir.startsWith(src))
                    throw new IllegalArgumentException("Cannot move a folder into itself");
                Path dst = destDir.resolve(src.getFileName());
                if (dst.equals(src)) return;
                Files.move(src, dst);
            }
            case READ -> {
                Path file = resolve(root, path);
                if (!Files.isRegularFile(file)) throw new NoSuchFileException(path);
                if (Files.size(file) > MAX_TEXT_BYTES)
                    throw new IOException("File too large to edit (max 32 KB)");
                PacketDistributor.sendToPlayer(player, new FileContentPayload(path, Files.readString(file)));
                return;
            }
            case WRITE -> {
                Path file = resolve(root, path);
                if (!Files.isRegularFile(file)) throw new NoSuchFileException(path);
                if (extra.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES)
                    throw new IOException("File too large (max 32 KB)");
                Files.writeString(file, extra);
                return;
            }
        }
        sendTree(player, root);
    }

    public static void report(ServerPlayer player, Exception e) {
        String msg = switch (e) {
            case FileAlreadyExistsException ex -> "Already exists";
            case NoSuchFileException ex -> "No such file: " + ex.getMessage();
            case SecurityException ex -> "Path not allowed";
            default -> e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        };
        player.displayClientMessage(Component.literal(msg), true);
    }

    private static Path root(MinecraftServer server) throws IOException {
        Path r = SandboxFileSystem.rootFor(server);
        Files.createDirectories(r);
        return r.toRealPath();
    }

    private static Path resolve(Path root, String rel) {
        String clean = rel == null ? "" : rel.replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        Path p = root.resolve(clean).normalize();
        if (!p.startsWith(root)) throw new SecurityException("Outside sandbox");
        return p;
    }

    private static Path dir(Path root, String rel) throws IOException {
        Path p = resolve(root, rel);
        if (!Files.isDirectory(p)) throw new NotDirectoryException(rel);
        return p;
    }

    private static String validName(String n) {
        String name = n == null ? "" : n.trim();
        if (name.isEmpty() || name.length() > 64 || name.equals(".") || name.equals("..")
                || name.matches(".*[\\\\/:*?\"<>|].*")) {
            throw new IllegalArgumentException("Invalid name");
        }
        return name;
    }

    private static void delete(Path root, String rel) throws IOException {
        Path target = resolve(root, rel);
        if (target.equals(root)) throw new IllegalArgumentException("Cannot delete the root");
        try (Stream<Path> s = Files.walk(target)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }

    private static void sendTree(ServerPlayer player, Path root) throws IOException {
        List<FileTreePayload.Entry> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root, MAX_DEPTH)) {
            s.filter(p -> !p.equals(root)).limit(MAX_ENTRIES).forEach(p ->
                    out.add(new FileTreePayload.Entry(
                            root.relativize(p).toString().replace('\\', '/'),
                            Files.isDirectory(p))));
        }
        PacketDistributor.sendToPlayer(player, new FileTreePayload(out));
    }
}