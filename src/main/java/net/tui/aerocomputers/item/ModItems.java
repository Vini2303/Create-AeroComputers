package net.tui.aerocomputers.item;

import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.item.custom.HUDHelmetItem;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AeroComputers.MOD_ID);

    public static final DeferredItem<HUDHelmetItem> HUD_HELMET = ITEMS.register("hud_helmet",
            () -> new HUDHelmetItem(ModArmorMaterials.HUD_ARMOR_MATERIAL, ArmorItem.Type.HELMET,
                    new Item.Properties().durability(ArmorItem.Type.HELMET.getDurability(-1))));

    public static final DeferredItem<Item> RAW_TRANSFORMIUM = ITEMS.register("raw_transformium",
            () -> new Item(new Item.Properties()));

    public static final DeferredItem<Item> TRANSFORMIUM_INGOT = ITEMS.register("transformium_ingot",
            () -> new Item(new Item.Properties()));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
