package net.tui.aerocomputers.registries;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HudRegistry {

    private HudRegistry() {}

    public record DrawEntry(String type, double x, double y, double x2, double y2,
                            double w, double h, String text, double fontSize,
                            double width, double borderWidth, boolean renderCenter, boolean renderBorder,
                            List<Integer> color, List<Integer> centerColor, List<Integer> borderColor,
                            List<Double> xs, List<Double> ys) {}

    public record FreqPair(String freqOne, String freqTwo) {}

    private static final Map<FreqPair, List<DrawEntry>>
            HUDS = new ConcurrentHashMap<>();

    /**
     * Replace all HUD entries for this frequency pair.
     */
    public static void update(
            String freqOne,
            String freqTwo,
            List<DrawEntry> entries
    ) {
        if (isInvalid(freqOne) || isInvalid(freqTwo) || entries == null) {
            return;
        }

        HUDS.put(
                new FreqPair(freqOne, freqTwo),
                List.copyOf(entries)
        );
    }

    public static void replaceAll(
            Map<FreqPair, List<DrawEntry>> entries
    ) {
        HUDS.clear();

        entries.forEach((key, value) ->
                HUDS.put(key, List.copyOf(value))
        );
    }

    /**
     * Get all HUD entries assigned to this frequency pair.
     */
    public static List<DrawEntry> get(
            String freqOne,
            String freqTwo
    ) {
        if (isInvalid(freqOne) || isInvalid(freqTwo)) {
            return List.of();
        }

        return HUDS.getOrDefault(
                new FreqPair(freqOne, freqTwo),
                List.of()
        );
    }

    /**
     * Remove all entries for one frequency pair.
     */
    public static void clear(String freqOne, String freqTwo) {
        if (isInvalid(freqOne) || isInvalid(freqTwo)) {
            return;
        }

        HUDS.remove(new FreqPair(freqOne, freqTwo));
    }

    /**
     * Remove all registered HUD entries.
     */
    public static void clear() {
        HUDS.clear();
    }

    private static boolean isInvalid(@Nullable String freq) {
        return freq == null || freq.isBlank();
    }
}