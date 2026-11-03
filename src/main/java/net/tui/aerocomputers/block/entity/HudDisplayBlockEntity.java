package net.tui.aerocomputers.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.tui.aerocomputers.block.custom.HudDisplayBlock;
import net.tui.aerocomputers.client.HudDisplayRenderCache;
import net.tui.aerocomputers.registries.SensorRegistry;
import net.tui.aerocomputers.screen.custom.HudDisplayMenu;
import org.jetbrains.annotations.Nullable;

import javax.xml.crypto.dsig.spec.XSLTTransformParameterSpec;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

public class HudDisplayBlockEntity extends BlockEntity implements MenuProvider{
    private String FREQ_ONE = "";
    private String FREQ_TWO = "";

    public HudDisplayBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntitys.HUDDISPLAY_BE.get(), pos, blockState);
    }

    public String getFreqOne() {
        return FREQ_ONE;
    }

    public String getFreqTwo() {
        return FREQ_TWO;
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

        if (level != null) {
            HudDisplayRenderCache.invalidate(level);
        }
    }

    public void setFreq(String freq_one, String freq_two) {
        if (level == null) {
            this.FREQ_ONE = freq_one;
            this.FREQ_TWO = freq_two;
            setChanged();
            return;
        }

        if (level.isClientSide)
            return;

        // Remember the old frequencies before changing anything
        String oldFreqOne = this.FREQ_ONE;
        String oldFreqTwo = this.FREQ_TWO;

        // Find every connected display using the old frequencies
        Set<BlockPos> connectedDisplays = findConnectedDisplays(
                level,
                worldPosition,
                oldFreqOne,
                oldFreqTwo
        );

        // Change the frequencies of every display in the group
        for (BlockPos pos : connectedDisplays) {
            if (level.getBlockEntity(pos) instanceof HudDisplayBlockEntity be) {
                be.setFreqLocal(freq_one, freq_two);
            }
        }

        // Update blockstates and synchronize block entities
        for (BlockPos pos : connectedDisplays) {
            if (!(level.getBlockEntity(pos) instanceof HudDisplayBlockEntity be))
                continue;

            BlockState state = level.getBlockState(pos);

            be.setChanged();

            level.sendBlockUpdated(
                    pos,
                    state,
                    state,
                    3
            );
        }

        // Recalculate connections for all changed displays and their neighbours
        Set<BlockPos> positionsToRefresh = new HashSet<>(connectedDisplays);

        for (BlockPos pos : connectedDisplays) {
            if (!(level.getBlockState(pos).getBlock() instanceof HudDisplayBlock))
                continue;

            Direction facing = level.getBlockState(pos).getValue(HudDisplayBlock.FACING);

            positionsToRefresh.add(pos.above());
            positionsToRefresh.add(pos.below());
            positionsToRefresh.add(pos.relative(facing.getCounterClockWise()));
            positionsToRefresh.add(pos.relative(facing.getClockWise()));
        }

        for (BlockPos pos : positionsToRefresh) {
            HudDisplayBlock.refreshConnections(level, pos);
        }

        HudDisplayRenderCache.invalidate(level);
    }

    private void setFreqLocal(String freqOne, String freqTwo) {
        this.FREQ_ONE = freqOne;
        this.FREQ_TWO = freqTwo;
        setChanged();
    }

    private static Set<BlockPos> findConnectedDisplays(
            Level level,
            BlockPos startPos,
            String freqOne,
            String freqTwo
    ) {
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        queue.add(startPos);
        visited.add(startPos);

        while (!queue.isEmpty()) {
            BlockPos currentPos = queue.poll();

            BlockState currentState = level.getBlockState(currentPos);

            if (!(currentState.getBlock() instanceof HudDisplayBlock))
                continue;

            Direction facing = currentState.getValue(HudDisplayBlock.FACING);

            Direction[] directions = {
                    Direction.UP,
                    Direction.DOWN,
                    facing.getCounterClockWise(),
                    facing.getClockWise()
            };

            for (Direction direction : directions) {
                BlockPos neighborPos = currentPos.relative(direction);

                if (visited.contains(neighborPos))
                    continue;

                BlockState neighborState = level.getBlockState(neighborPos);

                if (!(neighborState.getBlock() instanceof HudDisplayBlock))
                    continue;

                // Must face the same direction
                if (neighborState.getValue(HudDisplayBlock.FACING) != facing)
                    continue;

                if (!(level.getBlockEntity(neighborPos) instanceof HudDisplayBlockEntity neighborBE))
                    continue;

                // Both frequencies must match
                if (!neighborBE.getFreqOne().equals(freqOne))
                    continue;

                if (!neighborBE.getFreqTwo().equals(freqTwo))
                    continue;

                visited.add(neighborPos);
                queue.add(neighborPos);
            }
        }

        return visited;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Set HudEntry");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int i, Inventory playerInventory, Player player) {
        return new HudDisplayMenu(i, playerInventory, this);
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
