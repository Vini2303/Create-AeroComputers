package net.tui.aerocomputers.compat;

import net.neoforged.fml.ModList;

public final class ModCompat {

    private ModCompat() {}

    public static boolean hasBigCannons() {
        return ModList.get().isLoaded("createbigcannons");
    }

    public static boolean hasCreateArms() {
        return ModList.get().isLoaded("createarms");
    }
}