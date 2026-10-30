package net.tui.aerocomputers.block.entity;

import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.gearbox.GearboxBlock;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.ticks.TickPriority;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.registries.DigitalTransmissionRegistry;
import net.tui.aerocomputers.registries.SensorRegistry;
import net.tui.aerocomputers.screen.custom.DigitalTransmissionMenu;
import net.tui.aerocomputers.screen.custom.SensorMenu;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static net.tui.aerocomputers.block.entity.BoardComputerBlockEntity.round2;




public class DigitalTransmissionBlockEntity extends SplitShaftBlockEntity implements MenuProvider, BlockEntitySubLevelActor {
    private String FREQ_ONE = "";
    private String FREQ_TWO = "";

    private int output_speed = 0;


    public DigitalTransmissionBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntitys.DIGITALTRASMISSION_BE.get(), pos, blockState);
        setLazyTickRate(10);
    }

    @Override
    public float getRotationSpeedModifier(Direction face) {
        // No input connected yet
        if (!hasSource())
            return 1.0f;

        // Never modify the input side
        if (face == getSourceFacing())
            return 1.0f;

        // Get the actual input speed
        float inputSpeed = getTheoreticalSpeed();

        // Avoid division by zero
        if (Math.abs(inputSpeed) < 0.001f)
            return 0.0f;

        // Calculate the required transmission ratio
        return output_speed / inputSpeed;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider provider, boolean clientPacket) {
        super.write(tag, provider, clientPacket);

        tag.putString("FREQ_ONE", FREQ_ONE);
        tag.putString("FREQ_TWO", FREQ_TWO);

        tag.putInt("OUTPUT_SPEED", output_speed);

    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider provider, boolean clientPacket) {
        super.read(tag, provider, clientPacket);

        FREQ_ONE = tag.getString("FREQ_ONE");
        FREQ_TWO = tag.getString("FREQ_TWO");

        output_speed = tag.getInt("OUTPUT_SPEED");
    }

    public void setFreq(String freq_one, String freq_two) {
        DigitalTransmissionRegistry.unregister(FREQ_ONE, FREQ_TWO, this);
        this.FREQ_ONE = freq_one;
        this.FREQ_TWO = freq_two;
        DigitalTransmissionRegistry.register(FREQ_ONE, FREQ_TWO, this);
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

    public void setSpeed(int speed) {
        speed = Mth.clamp(speed, -256, 256);

        if (this.output_speed == speed)
            return;

        this.output_speed = speed;
        setChanged();

        if (level == null || level.isClientSide)
            return;

        // Disconnect the old output network
        RotationPropagator.handleRemoved(level, worldPosition, this);

        // Reconnect with the new ratio next tick
        level.scheduleTick(
                worldPosition,
                getBlockState().getBlock(),
                1,
                TickPriority.EXTREMELY_HIGH
        );
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Digital Transmission");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int i, Inventory playerInventory, Player player) {
        return new DigitalTransmissionMenu(i, playerInventory, this);
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
            DigitalTransmissionRegistry.register(FREQ_ONE, FREQ_TWO, this);
        }
    }

    @Override
    public void remove() {
        super.remove();
        if (this.level != null && !this.level.isClientSide) {
            DigitalTransmissionRegistry.unregister(FREQ_ONE, FREQ_TWO, this);
        }
    }

}