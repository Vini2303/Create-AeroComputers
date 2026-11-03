package net.tui.aerocomputers.client;

import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.WeakHashMap;

public final class HudDisplayRenderCache {

    private HudDisplayRenderCache() {}

    private static final Map<Level, Long> REVISIONS =
            new WeakHashMap<>();

    public static synchronized long getRevision(Level level) {
        return REVISIONS.getOrDefault(level, 0L);
    }

    public static synchronized void invalidate(Level level) {
        if (level == null)
            return;

        REVISIONS.put(
                level,
                REVISIONS.getOrDefault(level, 0L) + 1L
        );
    }
}