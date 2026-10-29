package net.tui.aerocomputers.block.entity;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.content.redstone.link.RedstoneLinkFrequencySlot;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.network.CameraChunkManager;
import net.tui.aerocomputers.registries.SensorRegistry;
import net.tui.aerocomputers.screen.custom.BoardComputerMenu;
import net.tui.aerocomputers.screen.custom.SensorMenu;
import net.tui.aerocomputers.script.AeroScriptApi;
import net.tui.aerocomputers.script.HudScriptApi;
import net.tui.aerocomputers.script.PythonScriptRuntime;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.*;

import static net.tui.aerocomputers.block.entity.BoardComputerBlockEntity.round2;


public class SensorBlockEntity extends KineticBlockEntity implements MenuProvider, BlockEntitySubLevelActor {
    private String FREQ_ONE = "";
    private String FREQ_TWO = "";

    public List<List<Double>> getAllSubLevelPositions() {
        List<List<Double>> allPositions = new ArrayList<>();

        if (this.level == null || currentSubLevel == null) {
            return allPositions;
        }

        SubLevelContainer container =
                ServerSubLevelContainer.getContainer(this.level);

        if (container == null) {
            return allPositions;
        }

        Vector3d currentPos = currentSubLevel.logicalPose().position();

        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel != currentSubLevel) {
                Vector3d pos = subLevel.logicalPose().position();

                allPositions.add(List.of(
                        round2(pos.x()),
                        round2(pos.y()),
                        round2(pos.z())
                ));
            }
        }

        allPositions.sort(Comparator.comparingDouble(pos -> {
            double dx = pos.get(0) - currentPos.x();
            double dy = pos.get(1) - currentPos.y();
            double dz = pos.get(2) - currentPos.z();

            return dx * dx + dy * dy + dz * dz;
        }));

        return allPositions;
    }

    private @Nullable SubLevel currentSubLevel;
    private boolean subLevelSeenThisCycle = false;

    public SensorBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntitys.SENSOR_BE.get(), pos, blockState);
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
        SensorRegistry.unregister(FREQ_ONE, FREQ_TWO, this);
        this.FREQ_ONE = freq_one;
        this.FREQ_TWO = freq_two;
        SensorRegistry.register(FREQ_ONE, FREQ_TWO, this);
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
        return Component.literal("Sensor");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int i, Inventory playerInventory, Player player) {
        return new SensorMenu(i, playerInventory, this);
    }

    @Override
    public float calculateStressApplied() {
        return 4f;
    }

    @Override
    public void requestModelDataUpdate() {
        super.requestModelDataUpdate();
    }

    // Methods exposed through the deliberately small AeroScriptApi facade.
    public Object get_Pos() {
        Vector3d pos = new Vector3d(getBlockPos().getX() + 0.5, getBlockPos().getY() + 0.5, getBlockPos().getZ() + 0.5);
        if (currentSubLevel != null) currentSubLevel.logicalPose().transformPosition(pos);
        return List.of(round2(pos.x), round2(pos.y), round2(pos.z));
    }

    public Object get_Rot() {
        if (currentSubLevel == null) return List.of(0.0, 0.0, 0.0);
        Quaterniond start = new Quaterniond().rotateY(Math.toRadians(-getBlockState().getValue(DirectionalKineticBlock.FACING).toYRot()));
        Quaterniond relative = start.invert().mul(new Quaterniond(currentSubLevel.logicalPose().orientation()));
        Vector3d euler = new Vector3d();
        relative.getEulerAnglesYXZ(euler);
        return List.of(round2(Math.toDegrees(euler.x)), round2(Math.toDegrees(euler.y)), round2(Math.toDegrees(euler.z)));
    }

    public Object get_Vel() {
        if (currentSubLevel == null) return List.of(0.0, 0.0, 0.0);
        Vector3d current = new Vector3d(getBlockPos().getX() + 0.5, getBlockPos().getY() + 0.5, getBlockPos().getZ() + 0.5);
        Vector3d previous = new Vector3d(current);
        currentSubLevel.logicalPose().transformPosition(current);
        currentSubLevel.lastPose().transformPosition(previous);
        Vector3d velocity = current.sub(previous).mul(20.0);
        return List.of(round2(velocity.x), round2(velocity.y), round2(velocity.z));
    }

    public Object get_Radar() { return getAllSubLevelPositions(); }


    @Override
    public void sable$tick(ServerSubLevel subLevel) {
        this.currentSubLevel = subLevel;
        this.subLevelSeenThisCycle = true;
    }

    @Override
    public void tick() {
        super.tick();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide) {
            SensorRegistry.register(FREQ_ONE, FREQ_TWO, this);
        }
    }

    @Override
    public void remove() {
        super.remove();
        if (this.level != null && !this.level.isClientSide) {
            AeroComputers.LOGGER.info(SensorRegistry.get(FREQ_ONE, FREQ_TWO).getBlockPos().toString());
            SensorRegistry.unregister(FREQ_ONE, FREQ_TWO, this);
        }
    }
}