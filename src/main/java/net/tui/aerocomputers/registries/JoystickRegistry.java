package net.tui.aerocomputers.registries;

import net.tui.aerocomputers.block.entity.JoystickBlockEntity;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class JoystickRegistry {
    private JoystickRegistry() {}

    private record FreqPair(String freqOne, String freqTwo) {}

    private static final Map<FreqPair, Set<JoystickBlockEntity>> JOYSTICK =
            new ConcurrentHashMap<>();

    public static void register(String freq_one, String freq_two, JoystickBlockEntity be) {
        if (isInvalid(freq_one) || isInvalid(freq_two)) return;

        JOYSTICK
                .computeIfAbsent(new FreqPair(freq_one, freq_two), k -> ConcurrentHashMap.newKeySet())
                .add(be);
    }

    public static void unregister(String freq_one, String freq_two, JoystickBlockEntity be) {
        if (isInvalid(freq_one) || isInvalid(freq_two)) return;

        FreqPair pair = new FreqPair(freq_one, freq_two);
        Set<JoystickBlockEntity> joystick = JOYSTICK.get(pair);

        if (joystick == null) return;

        joystick.remove(be);

        if (joystick.isEmpty()) {
            JOYSTICK.remove(pair, joystick);
        }
    }

    public static Set<JoystickBlockEntity> get(String freq_one, String freq_two) {
        if (isInvalid(freq_one) || isInvalid(freq_two)) return Set.of();

        Set<JoystickBlockEntity> joystick =
                JOYSTICK.get(new FreqPair(freq_one, freq_two));

        return joystick == null ? Set.of() : Set.copyOf(joystick);
    }

    public static void clear() {
        JOYSTICK.clear();
    }

    private static boolean isInvalid(@Nullable String freq) {
        return freq == null || freq.isBlank();
    }
}