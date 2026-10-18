package net.tui.aerocomputers.screen.custom;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.tui.aerocomputers.block.ModBlocks;
import net.tui.aerocomputers.block.entity.HudDisplayBlockEntity;
import net.tui.aerocomputers.block.entity.WeaponLinkerBlockEntity;
import net.tui.aerocomputers.screen.ModMenuTypes;
import org.jetbrains.annotations.UnknownNullability;

public class WeaponLinkerMenu extends AbstractContainerMenu {
    public final WeaponLinkerBlockEntity blockEntity;
    private final Level level;

    public WeaponLinkerMenu(int containerId, Inventory inv, FriendlyByteBuf extraData) {
        this(containerId, inv, inv.player.level().getBlockEntity(extraData.readBlockPos()));
    }

    public WeaponLinkerMenu(int containerId, @UnknownNullability Inventory inv, BlockEntity blockEntity) {
        super(ModMenuTypes.WEAPONLINKER_MENU.get(), containerId);
        this.blockEntity = (WeaponLinkerBlockEntity) blockEntity;
        this.level = inv.player.level();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(ContainerLevelAccess.create(level, blockEntity.getBlockPos()), player, ModBlocks.WEAPONLINKER.get());
    }
}
