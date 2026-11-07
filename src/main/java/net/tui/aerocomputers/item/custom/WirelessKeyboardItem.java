package net.tui.aerocomputers.item.custom;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.tui.aerocomputers.client.ShiftDown;
import net.tui.aerocomputers.screen.custom.WirelessKeyboardMenu;

import java.util.List;

public class WirelessKeyboardItem extends Item {


    public WirelessKeyboardItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
            Level level,
            Player player,
            InteractionHand hand
    ) {
        ItemStack stack = player.getItemInHand(hand);

        if (!level.isClientSide && player.isShiftKeyDown()) {
            player.openMenu(
                    new SimpleMenuProvider(
                            (containerId, inventory, p) ->
                                    new WirelessKeyboardMenu(containerId, inventory, hand),
                            Component.literal("Freq Setter")
                    ),
                    buf -> buf.writeEnum(hand)
            );
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            TooltipContext context,
            List<Component> tooltipComponents,
            TooltipFlag tooltipFlag
    ) {
        if (ShiftDown.isDown()) {
            tooltipComponents.add(
                    Component.translatable("tooltip.createaerocomputers.wireless_keyboard_shift")
            );
        } else {
            tooltipComponents.add(
                    Component.translatable("tooltip.createaerocomputers.pressshift")
            );
        }

        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }
}