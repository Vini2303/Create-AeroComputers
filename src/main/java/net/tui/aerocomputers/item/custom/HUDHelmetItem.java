package net.tui.aerocomputers.item.custom;

import com.simibubi.create.foundation.utility.DistExecutor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.tui.aerocomputers.AeroComputersClient;
import net.tui.aerocomputers.network.HudEntryPayload;

import java.util.List;

public class HUDHelmetItem extends ArmorItem {

    public HUDHelmetItem(net.minecraft.core.Holder<net.minecraft.world.item.ArmorMaterial> material, Type type, Properties properties) {
        super(material, type, properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
            Level level,
            Player player,
            InteractionHand hand
    ) {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide && player.isShiftKeyDown()) {
            net.tui.aerocomputers.client.HUDHelmetClient.requestOpen(hand);
        }

        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("tooltip.createaerocomputers.hud_helmet"));

        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag tag = data.copyTag();
        String hud = tag.contains(HudEntryPayload.NBT_KEY) ? tag.getString(HudEntryPayload.NBT_KEY) : "";
        tooltipComponents.add(Component.literal(hud));

        if(Screen.hasShiftDown()) {
            tooltipComponents.add(Component.translatable("tooltip.createaerocomputers.hud_helmet_shift"));
        } else {
            tooltipComponents.add(Component.translatable("tooltip.createaerocomputers.pressshift"));
        }
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }
}