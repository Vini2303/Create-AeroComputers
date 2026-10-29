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
import net.tui.aerocomputers.block.ModBlocks;
import net.tui.aerocomputers.block.entity.HudDisplayBlockEntity;
import net.tui.aerocomputers.block.entity.SensorBlockEntity;
import net.tui.aerocomputers.screen.ModMenuTypes;
import org.jetbrains.annotations.UnknownNullability;

public class SensorMenu extends AbstractContainerMenu {
    public final SensorBlockEntity blockEntity;
    private final Level level;

    public SensorMenu(int containerId, Inventory inv, FriendlyByteBuf extraData) {
        this(containerId, inv, inv.player.level().getBlockEntity(extraData.readBlockPos()));
    }

    public SensorMenu(int containerId, @UnknownNullability Inventory inv, BlockEntity blockEntity) {
        super(ModMenuTypes.SENSOR_MENU.get(), containerId);
        this.blockEntity = (SensorBlockEntity) blockEntity;
        this.level = inv.player.level();


        addPlayerInventory(inv);
        addPlayerHotbar(inv);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(ContainerLevelAccess.create(level, blockEntity.getBlockPos()), player, ModBlocks.SENSORBLOCK.get());
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
