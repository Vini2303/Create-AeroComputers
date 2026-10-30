package net.tui.aerocomputers.screen;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.network.IContainerFactory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.screen.custom.*;

public class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, AeroComputers.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<BoardComputerMenu>> BOARDCOMPUTER_MENU =
            registerMenuType("boardcomputer_menu", BoardComputerMenu::new);

    public static final DeferredHolder<MenuType<?>, MenuType<HudDisplayMenu>> HUDDISPLAY_MENU =
            registerMenuType("hud_diplay_menu", HudDisplayMenu::new);

    public static final DeferredHolder<MenuType<?>, MenuType<HudMenu>> HUD_MENU =
            registerMenuType("hud_menu", HudMenu::new);

    public static final DeferredHolder<MenuType<?>, MenuType<WeaponLinkerMenu>> WEAPONLINKER_MENU =
            registerMenuType("weapon_linker_menu", WeaponLinkerMenu::new);

    public static final DeferredHolder<MenuType<?>, MenuType<SensorMenu>> SENSOR_MENU =
            registerMenuType("sensor_menu", SensorMenu::new);

    public static final DeferredHolder<MenuType<?>, MenuType<DigitalTransmissionMenu>> DIGITALTRANSMISSION_MENU =
            registerMenuType("digital_transmission_menu", DigitalTransmissionMenu::new);

    public static final DeferredHolder<MenuType<?>, MenuType<CameraMenu>> CAMERA_MENU =
            registerMenuType("camera_menu", CameraMenu::new);

    private static <T extends AbstractContainerMenu>DeferredHolder<MenuType<?>, MenuType<T>> registerMenuType(String name, IContainerFactory<T> factory) {
        return MENUS.register(name, () -> IMenuTypeExtension.create(factory));
    }

    public static void register(IEventBus eventBus) {
        MENUS.register(eventBus);
    }
}
