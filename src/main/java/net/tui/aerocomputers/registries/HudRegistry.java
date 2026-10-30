package net.tui.aerocomputers.registries;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HudRegistry {

    private HudRegistry() {}

    public record DrawEntry(
            String type,
            double x,
            double y,
            double x2,
            double y2,
            double w,
            double h,
            String text,
            double fontSize,
            double width,
            double borderWidth,
            boolean renderCenter,
            boolean renderBorder,
            List<Integer> color,
            List<Integer> centerColor,
            List<Integer> borderColor,
            List<Double> xs,
            List<Double> ys
    ) {}

    public record FreqPair(String freqOne, String freqTwo) {}

    /*
     * Frequency pair
     *     -> Board computer position
     *         -> HUD entries
     */
    private static final Map<FreqPair, Map<BlockPos, List<DrawEntry>>> HUDS =
            new ConcurrentHashMap<>();

    /**
     * Updates the HUD entries belonging to one board computer.
     */
    public static void update(
            String freqOne,
            String freqTwo,
            BlockPos computerPos,
            List<DrawEntry> entries
    ) {
        if (isInvalid(freqOne) || isInvalid(freqTwo)
                || computerPos == null || entries == null) {
            return;
        }

        FreqPair pair = new FreqPair(freqOne, freqTwo);

        HUDS.computeIfAbsent(pair, key -> new ConcurrentHashMap<>())
                .put(computerPos.immutable(), List.copyOf(entries));
    }

    /**
     * Gets all HUD entries from all board computers
     * using this frequency pair.
     */
    public static List<DrawEntry> get(
            String freqOne,
            String freqTwo
    ) {
        if (isInvalid(freqOne) || isInvalid(freqTwo)) {
            return List.of();
        }

        Map<BlockPos, List<DrawEntry>> computers =
                HUDS.get(new FreqPair(freqOne, freqTwo));

        if (computers == null) {
            return List.of();
        }

        return computers.values().stream()
                .flatMap(List::stream)
                .toList();
    }

    /**
     * Removes all HUD entries belonging to one board computer.
     */
    public static void clearComputer(BlockPos computerPos) {
        if (computerPos == null) {
            return;
        }

        BlockPos immutablePos = computerPos.immutable();

        HUDS.forEach((pair, computers) -> {
            computers.remove(immutablePos);

            if (computers.isEmpty()) {
                HUDS.remove(pair, computers);
            }
        });
    }

    /**
     * Removes all entries for one frequency pair.
     */
    public static void clear(String freqOne, String freqTwo) {
        if (isInvalid(freqOne) || isInvalid(freqTwo)) {
            return;
        }

        HUDS.remove(new FreqPair(freqOne, freqTwo));
    }

    /**
     * Removes every registered HUD entry.
     */
    public static void clear() {
        HUDS.clear();
    }

    private static boolean isInvalid(@Nullable String freq) {
        return freq == null || freq.isBlank();
    }
}