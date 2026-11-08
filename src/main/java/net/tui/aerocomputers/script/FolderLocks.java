package net.tui.aerocomputers.script;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.screen.custom.BoardComputerMenu;

import javax.annotation.Nullable;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/** Server-side folder passwords. Stored outside the sandbox root so Python can never read them. */
public final class FolderLocks {
    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 120_000;
    private static final long FAIL_COOLDOWN_MS = 1500;
    private static final Map<MinecraftServer, FolderLocks> INSTANCES = new WeakHashMap<>();

    private final Path file;
    /** normalized folder path -> {saltBase64, hashBase64} */
    private final Map<String, String[]> locks = new LinkedHashMap<>();
    /** player -> earliest time the next password attempt is accepted */
    private final Map<UUID, Long> nextAttempt = new HashMap<>();

    /** One unlock session = one player at one specific board computer. */
    public record Session(UUID player, @Nullable BlockPos pos) {}

    /** The session of the computer whose menu this player currently has open. */
    public static Session sessionOf(ServerPlayer p) {
        BlockPos pos = p.containerMenu instanceof BoardComputerMenu m
                ? m.blockEntity.getBlockPos().immutable() : null;
        return new Session(p.getUUID(), pos);
    }

    /** session -> lock keys unlocked in this session */
    private final Map<Session, Set<String>> sessions = new HashMap<>();

    public static synchronized FolderLocks get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, s -> new FolderLocks(
                s.getWorldPath(LevelResource.ROOT).resolve("aerocomputers_locks.json")
                        .toAbsolutePath().normalize()));
    }

    private FolderLocks(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            Map<String, String[]> m = GSON.fromJson(r, new TypeToken<Map<String, String[]>>() {}.getType());
            if (m != null) locks.putAll(m);
        } catch (Exception e) {
            AeroComputers.LOGGER.error("Could not read folder locks", e);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Normalizes ".." and "." and lower-cases, so neither "a/../secret" nor "SECRET" dodge a lock. */
    static String key(String p) {
        ArrayDeque<String> parts = new ArrayDeque<>();
        for (String s : (p == null ? "" : p).replace('\\', '/').split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
                continue;
            }
            parts.addLast(s);
        }
        return String.join("/", parts).toLowerCase(Locale.ROOT);
    }

    private static boolean covers(String lock, String path) {
        return path.equals(lock) || path.startsWith(lock + "/");
    }

    private static String parentOf(String p) {
        int i = p.lastIndexOf('/');
        return i < 0 ? "" : p.substring(0, i);
    }

    private static byte[] hash(String pw, byte[] salt) throws Exception {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(pw.toCharArray(), salt, ITERATIONS, 256)).getEncoded();
    }

    private boolean verify(String lock, String pw) {
        String[] s = locks.get(lock);
        if (s == null) return false;
        try {
            byte[] expected = Base64.getDecoder().decode(s[1]);
            return MessageDigest.isEqual(expected, hash(pw, Base64.getDecoder().decode(s[0])));
        } catch (Exception e) {
            return false;
        }
    }

    private void save() {
        try {
            Files.writeString(file, GSON.toJson(locks));
        } catch (Exception e) {
            AeroComputers.LOGGER.error("Could not save folder locks", e);
        }
    }

    /**
     * scriptPath = file the script runs from (relative to the root), or null for the computer's own program.
     * A path under a locked folder is only allowed if the script itself lives in that folder (or a subfolder).
     */
    public synchronized boolean pythonMayAccess(String relPath, String scriptPath) {
        String path = key(relPath);
        String scriptDir = scriptPath == null ? null : parentOf(key(scriptPath));
        for (String lock : locks.keySet()) {
            if (!covers(lock, path)) continue;
            if (scriptDir == null || !covers(lock, scriptDir)) return false;
        }
        return true;
    }


    private boolean checkPassword(Session player, String lock, String pw) {
        long now = System.currentTimeMillis();
        if (now < nextAttempt.getOrDefault(player.player(), 0L)) return false;
        boolean ok = verify(lock, pw);
        if (!ok) nextAttempt.put(player.player(), now + FAIL_COOLDOWN_MS);
        return ok;
    }

    private Set<String> session(Session id) {
        return sessions.computeIfAbsent(id, k -> new HashSet<>());
    }

    public synchronized boolean mayTouch(Session player, String relPath) {
        String path = key(relPath);
        Set<String> open = sessions.getOrDefault(player, Set.of());
        for (String lock : locks.keySet()) {
            if (covers(lock, path) && !open.contains(lock)) return false;
        }
        return true;
    }

    public synchronized boolean mayDelete(Session player, String relPath) {
        String path = key(relPath);
        Set<String> open = sessions.getOrDefault(player, Set.of());
        for (String lock : locks.keySet()) {
            if ((covers(lock, path) || covers(path, lock)) && !open.contains(lock)) return false;
        }
        return true;
    }

    public synchronized boolean isVisible(Session player, String relPath) {
        String path = key(relPath);
        Set<String> open = sessions.getOrDefault(player, Set.of());
        for (String lock : locks.keySet()) {
            if (!lock.equals(path) && covers(lock, path) && !open.contains(lock)) return false;
        }
        return true;
    }

    public synchronized int stateOf(Session player, String relPath) {
        String path = key(relPath);
        if (!locks.containsKey(path)) return 0;
        return sessions.getOrDefault(player, Set.of()).contains(path) ? 2 : 1;
    }

    public synchronized boolean setPassword(Session player, String relPath, String pw) {
        String path = key(relPath);
        if (player.pos() == null || path.isEmpty() || locks.containsKey(path)) return false;
        try {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            locks.put(path, new String[]{
                    Base64.getEncoder().encodeToString(salt),
                    Base64.getEncoder().encodeToString(hash(pw, salt))});
        } catch (Exception e) {
            return false;
        }
        session(player).add(path);   // creator keeps access on this computer
        save();
        return true;
    }

    public synchronized boolean unlock(Session player, String relPath, String pw) {
        String path = key(relPath);
        if (player.pos() == null || !checkPassword(player, path, pw)) return false;
        session(player).add(path);
        return true;
    }

    /** Locks the folder again on this computer, and every unlocked folder below it. */
    public synchronized void relock(Session player, String relPath) {
        String path = key(relPath);
        session(player).removeIf(l -> covers(path, l));
    }

    /** Logout: locks everything again on every computer for this player. */
    public synchronized void relockAll(UUID player) {
        sessions.keySet().removeIf(s -> s.player().equals(player));
        nextAttempt.remove(player);
    }

    public synchronized boolean removePassword(Session player, String relPath, String pw) {
        String path = key(relPath);
        if (!checkPassword(player, path, pw)) return false;
        locks.remove(path);
        sessions.values().forEach(s -> s.remove(path));
        save();
        return true;
    }

    public synchronized void onDeleted(String relPath) {
        String path = key(relPath);
        locks.keySet().removeIf(l -> covers(path, l));
        sessions.values().forEach(s -> s.removeIf(l -> covers(path, l)));
        save();
    }

    /** Call after a successful rename/move so locks follow their folder. */
    public synchronized void onMoved(String from, String to) {
        String f = key(from), t = key(to);
        Map<String, String[]> moved = new LinkedHashMap<>();
        for (var e : locks.entrySet()) moved.put(remap(e.getKey(), f, t), e.getValue());
        locks.clear();
        locks.putAll(moved);
        for (Set<String> s : sessions.values()) {
            Set<String> n = new HashSet<>();
            for (String l : s) n.add(remap(l, f, t));
            s.clear();
            s.addAll(n);
        }
        save();
    }

    private static String remap(String k, String from, String to) {
        if (k.equals(from)) return to;
        if (k.startsWith(from + "/")) return to + k.substring(from.length());
        return k;
    }
}