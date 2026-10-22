package net.tui.aerocomputers;

import com.simibubi.create.api.registry.CreateRegistries;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterNamedRenderTypesEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.tui.aerocomputers.block.entity.BoardComputerBlockEntity;
import net.tui.aerocomputers.block.entity.ModBlockEntitys;
import net.tui.aerocomputers.client.HudOverlay;
import net.tui.aerocomputers.renderer.BoardComputerRenderer;
import net.tui.aerocomputers.renderer.BoardComputerVisual;
import net.tui.aerocomputers.renderer.HudDisplayRenderer;
import net.tui.aerocomputers.screen.ModMenuTypes;
import net.tui.aerocomputers.screen.custom.BoardComputerScreen;
import net.tui.aerocomputers.screen.custom.HudDisplayScreen;
import net.tui.aerocomputers.screen.custom.HudScreen;
import net.tui.aerocomputers.screen.custom.WeaponLinkerScreen;

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
        event.register(ModMenuTypes.WEAPONLINKER_MENU.get(), WeaponLinkerScreen::new);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntitys.BOARDCOMPUTER_BE.get(), BoardComputerRenderer::new);
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
                return true;
            }
        });
    }
}
