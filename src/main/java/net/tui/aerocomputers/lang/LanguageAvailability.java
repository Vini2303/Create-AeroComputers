package net.tui.aerocomputers.lang;

import net.neoforged.fml.ModList;

import java.util.List;
import java.util.Map;

public final class LanguageAvailability {

    private LanguageAvailability() {}

    public static boolean isModuleAvailable(String module) {
        return switch (module) {
            case "bigc" -> ModList.get().isLoaded("createbigcannons");
            case "slrw" -> ModList.get().isLoaded("createarms");
            default -> true;
        };
    }

    public static List<String> moduleNames() {
        return LanguageSpec.MODULE_NAMES.stream()
                .filter(LanguageAvailability::isModuleAvailable)
                .toList();
    }

    public static List<String> preloadedModules() {
        return LanguageSpec.PRELOADED_MODULES.stream()
                .filter(LanguageAvailability::isModuleAvailable)
                .toList();
    }

    public static List<String> moduleMembers(String module) {
        if (!isModuleAvailable(module)) {
            return List.of();
        }

        return LanguageSpec.moduleMembers(module);
    }

    public static Map<String, List<String>> builtinFunctions() {
        return LanguageSpec.BUILTIN_FUNCTIONS.entrySet().stream()
                .filter(entry -> {
                    String name = entry.getKey();
                    int dot = name.indexOf('.');

                    if (dot < 0) {
                        return true;
                    }

                    return isModuleAvailable(name.substring(0, dot));
                })
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));
    }
}