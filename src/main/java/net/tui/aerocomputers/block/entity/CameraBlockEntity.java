package net.tui.aerocomputers.block.entity;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.tui.aerocomputers.registries.CameraRegistry;
import net.tui.aerocomputers.screen.custom.CameraMenu;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static net.tui.aerocomputers.block.entity.BoardComputerBlockEntity.round2;


public class CameraBlockEntity extends KineticBlockEntity implements MenuProvider, BlockEntitySubLevelActor {
    private String FREQ_ONE = "";
    private String FREQ_TWO = "";

    public CameraBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntitys.CAMERA_BE.get(), pos, blockState);
        setLazyTickRate(10);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider provider, boolean clientPacket) {
        super.write(tag, provider, clientPacket);

        tag.putString("FREQ_ONE", FREQ_ONE);
        tag.putString("FREQ_TWO", FREQ_TWO);

    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider provider, boolean clientPacket) {
        super.read(tag, provider, clientPacket);

        FREQ_ONE = tag.getString("FREQ_ONE");
        FREQ_TWO = tag.getString("FREQ_TWO");
    }

    public void setFreq(String freq_one, String freq_two) {
        CameraRegistry.unregister(FREQ_ONE, FREQ_TWO, this);
        this.FREQ_ONE = freq_one;
        this.FREQ_TWO = freq_two;
        CameraRegistry.register(FREQ_ONE, FREQ_TWO, this);
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
        return Component.literal("Camera");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int i, Inventory playerInventory, Player player) {
        return new CameraMenu(i, playerInventory, this);
    }

    @Override
    public float calculateStressApplied() {
        return 4f;
    }

    @Override
    public void requestModelDataUpdate() {
        super.requestModelDataUpdate();
    }

    @Override
    public void tick() {
        super.tick();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide) {
            CameraRegistry.register(FREQ_ONE, FREQ_TWO, this);
        }
    }

    @Override
    public void remove() {
        super.remove();
        if (this.level != null && !this.level.isClientSide) {
            CameraRegistry.unregister(FREQ_ONE, FREQ_TWO, this);
        }
    }
}