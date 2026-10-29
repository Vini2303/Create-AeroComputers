package net.tui.aerocomputers;

import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.tui.aerocomputers.block.entity.BoardComputerBlockEntity;
import net.tui.aerocomputers.block.entity.ModBlockEntitys;
import net.tui.aerocomputers.block.entity.SensorBlockEntity;
import net.tui.aerocomputers.client.CameraFeed;
import net.tui.aerocomputers.client.HudOverlay;
import net.tui.aerocomputers.renderer.*;
import net.tui.aerocomputers.screen.ModMenuTypes;
import net.tui.aerocomputers.screen.custom.*;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = AeroComputers.MOD_ID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = AeroComputers.MOD_ID, value = Dist.CLIENT)
public class AeroComputersClient {
    public AeroComputersClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        AeroComputers.LOGGER.info("HELLO FROM CLIENT SETUP");
        AeroComputers.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());

    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.BOARDCOMPUTER_MENU.get(), BoardComputerScreen::new);
        event.register(ModMenuTypes.HUDDISPLAY_MENU.get(), HudDisplayScreen::new);
        event.register(ModMenuTypes.HUD_MENU.get(), HudScreen::new);
        event.register(ModMenuTypes.WEAPONLINKER_MENU.get(), WeaponLinkerScreen::new);
        event.register(ModMenuTypes.SENSOR_MENU.get(), SensorScreen::new);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntitys.BOARDCOMPUTER_BE.get(), BoardComputerRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntitys.SENSOR_BE.get(), SensorRenderer::new);

        event.registerBlockEntityRenderer(ModBlockEntitys.HUDDISPLAY_BE.get(), HudDisplayRenderer::new);
    }

    @SubscribeEvent
    public static void registerOverlays(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(AeroComputers.MOD_ID, "hud_overlay"),
                new HudOverlay()
        );
    }



    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        VisualizerRegistry.setVisualizer(ModBlockEntitys.BOARDCOMPUTER_BE.get(), new BlockEntityVisualizer<BoardComputerBlockEntity>() {
            @Override
            public BlockEntityVisual<? super BoardComputerBlockEntity> createVisual(VisualizationContext ctx, BoardComputerBlockEntity blockEntity, float partialTick) {
                return new BoardComputerVisual(ctx, blockEntity, partialTick);
            }

            @Override
            public boolean skipVanillaRender(BoardComputerBlockEntity blockEntity) {
                return !CameraFeed.isCameraLevel(blockEntity.getLevel());
            }
        });

        VisualizerRegistry.setVisualizer(ModBlockEntitys.SENSOR_BE.get(), new BlockEntityVisualizer<SensorBlockEntity>() {
            @Override
            public BlockEntityVisual<? super SensorBlockEntity> createVisual(VisualizationContext ctx, SensorBlockEntity blockEntity, float partialTick) {
                return new SensorVisual(ctx, blockEntity, partialTick);
            }

            @Override
            public boolean skipVanillaRender(SensorBlockEntity blockEntity) {
                return !CameraFeed.isCameraLevel(blockEntity.getLevel());
            }
        });
    }

    @SubscribeEvent
    public static void onFramePre(RenderFrameEvent.Pre event) {
        CameraFeed.renderIfRequested();
    }

    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov event) {
        if (CameraFeed.rendering) event.setFOV(CameraFeed.fov);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        CameraFeed.onClientTick();
    }
}
