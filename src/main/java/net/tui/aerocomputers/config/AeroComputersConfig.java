
package net.tui.aerocomputers.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class AeroComputersConfig {

    private static final ModConfigSpec.Builder BUILDER =
            new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ALLOW_NATIVE_ACCESS;

    public static final ModConfigSpec.ConfigValue<List<? extends String>>
            ALLOWED_LIBRARIES;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.push("python");

        ALLOW_NATIVE_ACCESS = BUILDER
                .comment(
                    "Allows Python scripts to access native functionality.",
                    "WARNING: Enabling this weakens the script sandbox.",
                    "A game restart is required after changing this setting."
                )
                .gameRestart()
                .define("allowNativeAccess", false);

        ALLOWED_LIBRARIES = BUILDER
                .comment(
                    "Python module root names allowed in script imports.",
                    "This does not install libraries.",
                    "Examples: math, json, random, pygame"
                )
                .defineListAllowEmpty(
                    "allowedLibraries",
                    List.of(
                        "math",
                        "random",
                        "json",
                        "pygame"
                    ),
                    () -> "math",
                    value -> value instanceof String name
                            && name.matches("[A-Za-z_][A-Za-z0-9_]*")
                );

        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private AeroComputersConfig() {
    }
}
