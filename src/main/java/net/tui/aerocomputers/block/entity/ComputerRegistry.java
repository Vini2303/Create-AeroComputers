package net.tui.aerocomputers.block.entity;

import org.jetbrains.annotations.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ComputerRegistry {
    private ComputerRegistry() {}

    private static final Map<String, BoardComputerBlockEntity> BY_NAME = new ConcurrentHashMap<>();

    public static void register(String name, BoardComputerBlockEntity be) {
        if (name == null || name.isBlank()) return;
        BY_NAME.put(name, be);
    }

    public static void unregister(String name, BoardComputerBlockEntity be) {
        if (name == null || name.isBlank()) return;
        BY_NAME.remove(name, be);   // only removes if it is still this block entity
    }

    public static @Nullable BoardComputerBlockEntity get(String name) {
        return (name == null || name.isBlank()) ? null : BY_NAME.get(name);
    }

    public static void clear() {
        BY_NAME.clear();
    }
}