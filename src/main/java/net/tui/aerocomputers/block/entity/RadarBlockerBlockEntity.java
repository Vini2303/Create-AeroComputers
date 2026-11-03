package net.tui.aerocomputers.block.entity;

import com.defpwoq.createarms.block.ChinGunBlockEntity;
import com.defpwoq.createarms.block.MFDControllerBlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.tui.aerocomputers.registries.SensorRegistry;
import net.tui.aerocomputers.screen.custom.RadarBlockerMenu;
import net.tui.aerocomputers.screen.custom.WeaponLinkerMenu;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;

public class RadarBlockerBlockEntity extends BlockEntity implements MenuProvider, BlockEntitySubLevelActor {
    private String FREQ_ONE = "";
    private String FREQ_TWO = "";

    public RadarBlockerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntitys.RADARBLOCKER_BE.get(), pos, blockState);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        tag.putString("FREQ_ONE", FREQ_ONE);
        tag.putString("FREQ_TWO", FREQ_TWO);

    }

    @Override
    protected void loadAdditional(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        super.loadAdditional(tag, registries);

        FREQ_ONE = tag.getString("FREQ_ONE");
        FREQ_TWO = tag.getString("FREQ_TWO");

    }

    public void setFreq(String freq_one, String freq_two) {
        this.FREQ_ONE = freq_one;
        this.FREQ_TWO = freq_two;
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
        return Component.literal("Radar Blocker");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int i, Inventory playerInventory, Player player) {
        return new RadarBlockerMenu(i, playerInventory, this);
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
