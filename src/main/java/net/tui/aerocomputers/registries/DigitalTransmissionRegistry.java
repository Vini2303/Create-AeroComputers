package net.tui.aerocomputers.registries;

import net.tui.aerocomputers.block.entity.DigitalTransmissionBlockEntity;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DigitalTransmissionRegistry {
    private DigitalTransmissionRegistry() {}

    private record FreqPair(String freqOne, String freqTwo) {}

    private static final Map<FreqPair, Set<DigitalTransmissionBlockEntity>> TRANSMISSIONS =
            new ConcurrentHashMap<>();

    public static void register(String freq_one, String freq_two, DigitalTransmissionBlockEntity be) {
        if (isInvalid(freq_one) || isInvalid(freq_two)) return;

        TRANSMISSIONS
                .computeIfAbsent(new FreqPair(freq_one, freq_two), k -> ConcurrentHashMap.newKeySet())
                .add(be);
    }

    public static void unregister(String freq_one, String freq_two, DigitalTransmissionBlockEntity be) {
        if (isInvalid(freq_one) || isInvalid(freq_two)) return;

        FreqPair pair = new FreqPair(freq_one, freq_two);
        Set<DigitalTransmissionBlockEntity> transmissions = TRANSMISSIONS.get(pair);

        if (transmissions == null) return;

        transmissions.remove(be);

        if (transmissions.isEmpty()) {
            TRANSMISSIONS.remove(pair, transmissions);
        }
    }

    public static Set<DigitalTransmissionBlockEntity> get(String freq_one, String freq_two) {
        if (isInvalid(freq_one) || isInvalid(freq_two)) return Set.of();

        Set<DigitalTransmissionBlockEntity> transmissions =
                TRANSMISSIONS.get(new FreqPair(freq_one, freq_two));

        return transmissions == null ? Set.of() : Set.copyOf(transmissions);
    }

    public static void clear() {
        TRANSMISSIONS.clear();
    }

    private static boolean isInvalid(@Nullable String freq) {
        return freq == null || freq.isBlank();
    }
}