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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.fml.ModList;
import net.tui.aerocomputers.compat.BigCCompat;
import net.tui.aerocomputers.compat.SlrwCompat;
import net.tui.aerocomputers.registries.WeaponLinkerRegistry;
import net.tui.aerocomputers.screen.custom.WeaponLinkerMenu;
import org.jetbrains.annotations.Nullable;

public class WeaponLinkerBlockEntity extends BlockEntity implements MenuProvider {

    private String FREQ_ONE = "";
    private String FREQ_TWO = "";

    private boolean ChinFire = false;

    public WeaponLinkerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntitys.WEAPONLINKER_BE.get(), pos, blockState);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        tag.putString("FREQ_ONE", FREQ_ONE);
        tag.putString("FREQ_TWO", FREQ_TWO);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        FREQ_ONE = tag.getString("FREQ_ONE");
        FREQ_TWO = tag.getString("FREQ_TWO");
    }

    public void setFreq(String freq_one, String freq_two) {
        WeaponLinkerRegistry.unregister(FREQ_ONE, FREQ_TWO, this);

        this.FREQ_ONE = freq_one;
        this.FREQ_TWO = freq_two;

        WeaponLinkerRegistry.register(FREQ_ONE, FREQ_TWO, this);

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

    public String getFreqOne() {
        return FREQ_ONE;
    }

    public String getFreqTwo() {
        return FREQ_TWO;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Weapon Linker");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(
            int i,
            Inventory playerInventory,
            Player player
    ) {
        return new WeaponLinkerMenu(i, playerInventory, this);
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

    public void tick() {
        if (level == null || level.isClientSide || !ChinFire) {
            return;
        }

        if (ModList.get().isLoaded("createarms")) {
            SlrwCompat.tick(this);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();

        if (level != null && !level.isClientSide) {
            WeaponLinkerRegistry.register(FREQ_ONE, FREQ_TWO, this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();

        if (level != null && !level.isClientSide) {
            WeaponLinkerRegistry.unregister(FREQ_ONE, FREQ_TWO, this);
        }
    }

    // Create Big Cannons compatibility

    public void setBigCFireRate(int fireRate) {
        if (ModList.get().isLoaded("createbigcannons")) {
            BigCCompat.setFireRate(this, fireRate);
        }
    }

    public void setBigCAimAngle(double pitch, double yaw) {
        if (ModList.get().isLoaded("createbigcannons")) {
            BigCCompat.setAimAngle(this, pitch, yaw);
        }
    }

    public void setBigCAimPos(double x, double y, double z) {
        if (ModList.get().isLoaded("createbigcannons")) {
            BigCCompat.setAimPos(this, x, y, z);
        }
    }

    // Create Arms compatibility

    public void setChinFire(boolean fire) {
        if (!fire) {
            ChinFire = false;
            return;
        }

        if (ModList.get().isLoaded("createarms")) {
            SlrwCompat.setFire(this, true);
        }
    }

    public void setChinAimAngle(double pitch, double yaw) {
        if (ModList.get().isLoaded("createarms")) {
            SlrwCompat.setAimAngle(this, pitch, yaw);
        }
    }

    public void setChinAimPos(double x, double y, double z) {
        if (ModList.get().isLoaded("createarms")) {
            SlrwCompat.setAimPos(this, x, y, z);
        }
    }

    // Shared helpers used by the compatibility classes

    public BlockPos getConnectedBlockPos() {
        return worldPosition.relative(
                getBlockState()
                        .getValue(BlockStateProperties.HORIZONTAL_FACING)
                        .getOpposite()
        );
    }

    public boolean isChinFireEnabled() {
        return ChinFire;
    }

    public void setChinFireEnabledInternal(boolean fire) {
        ChinFire = fire;
    }
}