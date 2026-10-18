package net.tui.aerocomputers.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.tui.aerocomputers.screen.custom.HudDisplayMenu;
import net.tui.aerocomputers.screen.custom.WeaponLinkerMenu;
import org.jetbrains.annotations.Nullable;

public class WeaponLinkerBlockEntity extends BlockEntity implements MenuProvider{
    private String HudEntry = "";

    public WeaponLinkerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntitys.WEAPONLINKER_BE.get(), pos, blockState);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        tag.putString("HudEntry", HudEntry);
    }

    @Override
    protected void loadAdditional(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        super.loadAdditional(tag, registries);

        HudEntry = tag.getString("HudEntry");
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Set Board Computer");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int i, Inventory playerInventory, Player player) {
        return new WeaponLinkerMenu(i, playerInventory, this);
    }

    public String getEntry() {
        return HudEntry;
    }

    public void setEntry(String entry) {
        this.HudEntry = entry;
        setChanged();

        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(
                    worldPosition,
                    getBlockState(),
                    getBlockState(),
                    3
            );
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
