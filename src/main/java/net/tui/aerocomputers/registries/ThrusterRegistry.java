package net.tui.aerocomputers.registries;

import net.tui.aerocomputers.block.entity.ThrusterBlockEntity;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ThrusterRegistry {
    private ThrusterRegistry() {}

    private record FreqPair(String freqOne, String freqTwo) {}

    private static final Map<FreqPair, Set<ThrusterBlockEntity>> THRUSTER =
            new ConcurrentHashMap<>();

    public static void register(String freq_one, String freq_two, ThrusterBlockEntity be) {
        if (isInvalid(freq_one) || isInvalid(freq_two)) return;

        THRUSTER
                .computeIfAbsent(new FreqPair(freq_one, freq_two), k -> ConcurrentHashMap.newKeySet())
                .add(be);
    }

    public static void unregister(String freq_one, String freq_two, ThrusterBlockEntity be) {
        if (isInvalid(freq_one) || isInvalid(freq_two)) return;

        FreqPair pair = new FreqPair(freq_one, freq_two);
        Set<ThrusterBlockEntity> thruster = THRUSTER.get(pair);

        if (thruster == null) return;

        thruster.remove(be);

        if (thruster.isEmpty()) {
            THRUSTER.remove(pair, thruster);
        }
    }

    public static Set<ThrusterBlockEntity> get(String freq_one, String freq_two) {
        if (isInvalid(freq_one) || isInvalid(freq_two)) return Set.of();

        Set<ThrusterBlockEntity> thruster =
                THRUSTER.get(new FreqPair(freq_one, freq_two));

        return thruster == null ? Set.of() : Set.copyOf(thruster);
    }

    public static void clear() {
        THRUSTER.clear();
    }

    private static boolean isInvalid(@Nullable String freq) {
        return freq == null || freq.isBlank();
    }
}