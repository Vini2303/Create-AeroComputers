package net.tui.aerocomputers.registries;

import net.tui.aerocomputers.block.entity.CameraBlockEntity;
import net.tui.aerocomputers.block.entity.SensorBlockEntity;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CameraRegistry {
    private CameraRegistry() {}

    private record FreqPair(String freqOne, String freqTwo) {}
    private static final Map<FreqPair, CameraBlockEntity> SENSORS = new ConcurrentHashMap<>();

    public static void register(String freq_one, String freq_two, CameraBlockEntity be) {
        if (isInvalid(freq_one) || isInvalid(freq_two)) return;

        SENSORS.put(new FreqPair(freq_one, freq_two), be);
    }

    public static void unregister(String freq_one, String freq_two, CameraBlockEntity be) {
        if (isInvalid(freq_one) || isInvalid(freq_two)) return;

        SENSORS.remove(new FreqPair(freq_one, freq_two), be);
    }

    public static @Nullable CameraBlockEntity get(String freq_one, String freq_two) {
        if (isInvalid(freq_one) || isInvalid(freq_two)) return null;

        return SENSORS.get(new FreqPair(freq_one, freq_two));
    }

    public static void clear() {
        SENSORS.clear();
    }

    private static boolean isInvalid(@Nullable String freq) {
        return freq == null || freq.isBlank();
    }
}
