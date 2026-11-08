package net.tui.aerocomputers.registries;

import net.tui.aerocomputers.block.entity.ProjectorBlockEntity;
import net.tui.aerocomputers.block.entity.ThrusterBlockEntity;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ProjectorRegistry {
    private ProjectorRegistry() {}

    private record FreqPair(String freqOne, String freqTwo) {}

    private static final Map<FreqPair, Set<ProjectorBlockEntity>> PROJECTOR =
            new ConcurrentHashMap<>();

    public static void register(String freq_one, String freq_two, ProjectorBlockEntity be) {
        if (isInvalid(freq_one) || isInvalid(freq_two)) return;

        PROJECTOR
                .computeIfAbsent(new FreqPair(freq_one, freq_two), k -> ConcurrentHashMap.newKeySet())
                .add(be);
    }

    public static void unregister(String freq_one, String freq_two, ProjectorBlockEntity be) {
        if (isInvalid(freq_one) || isInvalid(freq_two)) return;

        FreqPair pair = new FreqPair(freq_one, freq_two);
        Set<ProjectorBlockEntity> projector = PROJECTOR.get(pair);

        if (projector == null) return;

        projector.remove(be);

        if (projector.isEmpty()) {
            PROJECTOR.remove(pair, projector);
        }
    }

    public static Set<ProjectorBlockEntity> get(String freq_one, String freq_two) {
        if (isInvalid(freq_one) || isInvalid(freq_two)) return Set.of();

        Set<ProjectorBlockEntity> projector =
                PROJECTOR.get(new FreqPair(freq_one, freq_two));

        return projector == null ? Set.of() : Set.copyOf(projector);
    }

    public static void clear() {
        PROJECTOR.clear();
    }

    private static boolean isInvalid(@Nullable String freq) {
        return freq == null || freq.isBlank();
    }
}