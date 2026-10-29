package net.tui.aerocomputers.mixin;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.tui.aerocomputers.block.ModBlocks;
import net.tui.aerocomputers.item.ModCreativeModeTabs;
import net.tui.aerocomputers.item.ModItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Mixin(CreativeModeTab.class)
public abstract class CreativeModeTabMixin {

    @Shadow
    private Collection<ItemStack> displayItems;

    @Shadow
    private Set<ItemStack> displayItemsSearchTab;

    @Inject(method = "buildContents", at = @At("HEAD"), cancellable = true)
    private void aerocomputers$addBannerRows(
            CreativeModeTab.ItemDisplayParameters parameters,
            CallbackInfo ci
    ) {
        CreativeModeTab self = (CreativeModeTab) (Object) this;

        if (self != ModCreativeModeTabs.AEROCOMPUTERSTAB.get()) {
            return;
        }

        List<ItemStack> items = new ArrayList<>();
        Set<ItemStack> searchItems = new LinkedHashSet<>();

        // Banner 1: Computers
        addEmptyRow(items);
        addItem(items, searchItems, ModBlocks.BOARDCOMPUTER.asItem());
        addItem(items, searchItems, ModItems.HUD_HELMET.get());
        addItem(items, searchItems, ModBlocks.HUDDISPLAY.asItem());
        addItem(items, searchItems, ModBlocks.WEAPONLINKER.asItem());

        // Pad the current section to a row boundary, then reserve a banner row.
        padToNextRow(items);
        addEmptyRow(items);

        addEmptyRow(items);
        addEmptyRow(items);
        addEmptyRow(items);
        addEmptyRow(items);

        // Banner 2: Transformium / resources
        addItem(items, searchItems, ModBlocks.TRANSFORMIUM.asItem());
        addItem(items, searchItems, ModBlocks.TRANSFORMIUMORE.asItem());
        addItem(items, searchItems, ModItems.RAW_TRANSFORMIUM.get());
        addItem(items, searchItems, ModItems.TRANSFORMIUM_INGOT.get());

        this.displayItems = items;
        this.displayItemsSearchTab = searchItems;

        ci.cancel();
    }

    private static void addEmptyRow(List<ItemStack> items) {
        for (int i = 0; i < 9; i++) {
            items.add(ItemStack.EMPTY);
        }
    }

    private static void addItem(
            List<ItemStack> items,
            Set<ItemStack> searchItems,
            net.minecraft.world.item.Item item
    ) {
        ItemStack stack = new ItemStack(item);
        items.add(stack);
        searchItems.add(stack);
    }

    private static void padToNextRow(List<ItemStack> items) {
        int remainder = items.size() % 9;
        if (remainder != 0) {
            for (int i = remainder; i < 9; i++) {
                items.add(ItemStack.EMPTY);
            }
        }
    }
}