package net.tui.aerocomputers.item;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.block.ModBlocks;

import java.util.function.Supplier;

public class ModCreativeModeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TAB =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AeroComputers.MOD_ID);

    public static final Supplier<CreativeModeTab> AEROCOMPUTERSTAB = CREATIVE_MODE_TAB.register("aerocomputers_tab",
            () -> CreativeModeTab.builder().icon(() -> new ItemStack(ModBlocks.BOARDCOMPUTER.asItem()))
                    .title(Component.translatable("creativetab.createaerocomputers.aerocomputers_tab"))
                    .displayItems((ItemDisplayParameters, output) -> {
                        output.accept(ModBlocks.BOARDCOMPUTER);
                        output.accept(ModItems.HUD_HELMET);
                        output.accept(ModBlocks.HUDDISPLAY);
                        output.accept(ModBlocks.WEAPONLINKER);

                        output.accept(ModBlocks.TRANSFORMIUM);
                        output.accept(ModBlocks.TRANSFORMIUMORE);
                        output.accept(ModItems.RAW_TRANSFORMIUM);
                        output.accept(ModItems.TRANSFORMIUM_INGOT);

                    }).build());


    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TAB.register(eventBus);
    }
}
