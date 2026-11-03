package net.tui.aerocomputers.registries;

import net.tui.aerocomputers.block.entity.WeaponLinkerBlockEntity;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WeaponLinkerRegistry {
    private WeaponLinkerRegistry() {}

    private record FreqPair(String freqOne, String freqTwo) {}

    private static final Map<FreqPair, Set<WeaponLinkerBlockEntity>> WEAPONLINKER =
            new ConcurrentHashMap<>();

    public static void register(String freq_one, String freq_two, WeaponLinkerBlockEntity be) {
        if (isInvalid(freq_one) || isInvalid(freq_two)) return;

        WEAPONLINKER
                .computeIfAbsent(new FreqPair(freq_one, freq_two), k -> ConcurrentHashMap.newKeySet())
                .add(be);
    }

    public static void unregister(String freq_one, String freq_two, WeaponLinkerBlockEntity be) {
        if (isInvalid(freq_one) || isInvalid(freq_two)) return;

        FreqPair pair = new FreqPair(freq_one, freq_two);
        Set<WeaponLinkerBlockEntity> weaponlinker = WEAPONLINKER.get(pair);

        if (weaponlinker == null) return;

        weaponlinker.remove(be);

        if (weaponlinker.isEmpty()) {
            WEAPONLINKER.remove(pair, weaponlinker);
        }
    }

    public static Set<WeaponLinkerBlockEntity> get(String freq_one, String freq_two) {
        if (isInvalid(freq_one) || isInvalid(freq_two)) return Set.of();

        Set<WeaponLinkerBlockEntity> weaponlinker =
                WEAPONLINKER.get(new FreqPair(freq_one, freq_two));

        return weaponlinker == null ? Set.of() : Set.copyOf(weaponlinker);
    }

    public static void clear() {
        WEAPONLINKER.clear();
    }

    private static boolean isInvalid(@Nullable String freq) {
        return freq == null || freq.isBlank();
    }
}