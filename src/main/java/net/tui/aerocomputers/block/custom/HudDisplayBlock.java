package net.tui.aerocomputers.block.custom;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.block.entity.BoardComputerBlockEntity;
import net.tui.aerocomputers.block.entity.HudDisplayBlockEntity;
import net.tui.aerocomputers.client.HudDisplayRenderCache;
import net.tui.aerocomputers.client.ShiftDown;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public class HudDisplayBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<HudDisplayBlock> CODEC = simpleCodec(HudDisplayBlock::new);
    private static final VoxelShape SHAPE1 = box(7, 0, 0, 9,16,16);
    private static final VoxelShape SHAPE2 = box(0, 0, 7, 16,16,9);

    public static final BooleanProperty UP = PipeBlock.UP;
    public static final BooleanProperty DOWN = PipeBlock.DOWN;
    public static final BooleanProperty LEFT = PipeBlock.WEST;
    public static final BooleanProperty RIGHT = PipeBlock.EAST;

    public HudDisplayBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape var10000;
        switch (state.getValue(FACING)) {
            case EAST, WEST -> var10000 = SHAPE1;
            default -> var10000 = SHAPE2;
        }

        return var10000;
    }

    @Override
    protected MapCodec<? extends HudDisplayBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HudDisplayBlockEntity(pos, state);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(UP, false)
                .setValue(DOWN, false)
                .setValue(LEFT, false)
                .setValue(RIGHT, false);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
        builder.add(UP);
        builder.add(DOWN);
        builder.add(LEFT);
        builder.add(RIGHT);
    }

    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof HudDisplayBlockEntity hudDisplayBlockEntity)
            if (!level.isClientSide) {
                ((ServerPlayer) player).openMenu(new SimpleMenuProvider(hudDisplayBlockEntity, Component.literal("Set Board Computer")), pos);
            }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext context,
            List<Component> tooltipComponents,
            TooltipFlag tooltipFlag
    ) {
        if (ShiftDown.isDown()) {
            tooltipComponents.add(
                    Component.translatable("tooltip.createaerocomputers.hud_display_shift")
            );
        } else {
            tooltipComponents.add(
                    Component.translatable("tooltip.createaerocomputers.pressshift")
            );
        }

        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction direction,
            BlockState neighborState,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighborPos
    ) {
        if (level instanceof Level actualLevel) {
            HudDisplayRenderCache.invalidate(actualLevel);
        }

        return updateConnections(state, level, pos);
    }

    public static void refreshConnections(Level level, BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof HudDisplayBlock))
            return;

        BlockState state = level.getBlockState(pos);
        BlockState updated = updateConnections(state, level, pos);

        if (!updated.equals(state)) {
            level.setBlock(pos, updated, 3);
        }
    }

    private static BlockState updateConnections(
            BlockState state,
            BlockGetter level,
            BlockPos pos
    ) {
        Direction facing = state.getValue(FACING);

        Direction left = facing.getCounterClockWise();
        Direction right = facing.getClockWise();

        return state
                .setValue(UP, canConnect(level, pos, pos.above(), facing))
                .setValue(DOWN, canConnect(level, pos, pos.below(), facing))
                .setValue(LEFT, canConnect(level, pos, pos.relative(left), facing))
                .setValue(RIGHT, canConnect(level, pos, pos.relative(right), facing));
    }

    private static boolean canConnect(
            BlockGetter level,
            BlockPos currentPos,
            BlockPos neighborPos,
            Direction facing
    ) {
        BlockState neighborState = level.getBlockState(neighborPos);

        if (!(neighborState.getBlock() instanceof HudDisplayBlock))
            return false;

        if (neighborState.getValue(FACING) != facing)
            return false;

        if (!(level.getBlockEntity(currentPos) instanceof HudDisplayBlockEntity currentBE))
            return false;

        if (!(level.getBlockEntity(neighborPos) instanceof HudDisplayBlockEntity neighborBE))
            return false;

        return currentBE.getFreqOne().equals(neighborBE.getFreqOne()) && currentBE.getFreqTwo().equals(neighborBE.getFreqTwo());
    }
}
