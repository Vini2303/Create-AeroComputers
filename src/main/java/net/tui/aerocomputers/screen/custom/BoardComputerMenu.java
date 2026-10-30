package net.tui.aerocomputers.screen.custom;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.tui.aerocomputers.block.ModBlocks;
import net.tui.aerocomputers.block.entity.BoardComputerBlockEntity;
import net.tui.aerocomputers.screen.ModMenuTypes;
import net.tui.aerocomputers.screen.PopupSlot;
import org.jetbrains.annotations.UnknownNullability;

import java.util.ArrayList;
import java.util.List;

public class BoardComputerMenu extends AbstractContainerMenu {
    public final BoardComputerBlockEntity blockEntity;
    private final Level level;

    public final List<PopupSlot> popupSlots = new ArrayList<>();

    public BoardComputerMenu(int containerId, Inventory inv, FriendlyByteBuf extraData) {
        this(containerId, inv, inv.player.level().getBlockEntity(extraData.readBlockPos()));
    }

    public BoardComputerMenu(int containerId, @UnknownNullability Inventory inv, BlockEntity blockEntity) {
        super(ModMenuTypes.BOARDCOMPUTER_MENU.get(), containerId);
        this.blockEntity = ((BoardComputerBlockEntity) blockEntity);
        this.level = inv.player.level();

        for (int i = 0; i < 36; i++) {
            int containerIndex = i < 27 ? 9 + i : i - 27;
            PopupSlot slot = new PopupSlot(inv, containerIndex);
            popupSlots.add(slot);
            addSlot(slot);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(ContainerLevelAccess.create(level, blockEntity.getBlockPos()), player, ModBlocks.BOARDCOMPUTER.get());
    }
}