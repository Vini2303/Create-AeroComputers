package net.tui.aerocomputers.screen.custom;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.tui.aerocomputers.block.ModBlocks;
import net.tui.aerocomputers.block.entity.HudDisplayBlockEntity;
import net.tui.aerocomputers.screen.ModMenuTypes;
import org.jetbrains.annotations.UnknownNullability;

public class HudMenu extends AbstractContainerMenu {

    public final InteractionHand hand;
    private final Container frequencyContainer = new SimpleContainer(2);

    public HudMenu(int containerId, Inventory inventory, FriendlyByteBuf extraData) {
        this(containerId, inventory, extraData.readEnum(InteractionHand.class));
    }

    public HudMenu(int containerId, @UnknownNullability Inventory inv, InteractionHand hand) {
        super(ModMenuTypes.HUD_MENU.get(), containerId);

        this.hand = hand;

        addPlayerInventory(inv);
        addPlayerHotbar(inv);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    private void addPlayerInventory(Inventory playerInventory) {
        for (int i = 0; i < 3; ++i) {
            for (int l = 0; l < 9; ++l) {
                this.addSlot(new Slot(playerInventory, l + i * 9 + 9, 8 + l * 18, 37+ i * 18));
            }
        }
    }

    private void addPlayerHotbar(Inventory playerInventory) {
        for (int i = 0; i < 9; ++i) {
            this.addSlot(new Slot(playerInventory, i, 8 + i * 18, 95));
        }
    }
}
