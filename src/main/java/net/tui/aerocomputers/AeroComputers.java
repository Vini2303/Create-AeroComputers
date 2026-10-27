package net.tui.aerocomputers;

import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.tui.aerocomputers.block.ModBlocks;
import net.tui.aerocomputers.block.entity.ComputerRegistry;
import net.tui.aerocomputers.block.entity.ModBlockEntitys;
import net.tui.aerocomputers.config.AeroComputersConfig;
import net.tui.aerocomputers.item.ModCreativeModeTabs;
import net.tui.aerocomputers.item.ModItems;
import net.tui.aerocomputers.network.ModNetworking;
import net.tui.aerocomputers.screen.ModMenuTypes;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(AeroComputers.MOD_ID)
public class AeroComputers {
    public static final String MOD_ID = "createaerocomputers";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AeroComputers(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        NeoForge.EVENT_BUS.register(this);

        ModItems.register(modEventBus);
        ModBlocks.register(modEventBus);
        ModCreativeModeTabs.register(modEventBus);
        ModBlockEntitys.register(modEventBus);
        ModMenuTypes.register(modEventBus);

        modEventBus.addListener(ModNetworking::register);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, AeroComputersConfig.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {

    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event) {

    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {

    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        ComputerRegistry.clear();
    }
}
