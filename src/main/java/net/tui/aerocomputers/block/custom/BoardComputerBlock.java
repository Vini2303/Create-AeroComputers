package net.tui.aerocomputers.block.custom;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.ShaftRenderer;
import com.simibubi.create.content.kinetics.base.ShaftVisual;
import com.simibubi.create.content.kinetics.simpleRelays.ShaftBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.tui.aerocomputers.block.entity.BoardComputerBlockEntity;
import net.tui.aerocomputers.block.entity.ModBlockEntitys;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class BoardComputerBlock extends DirectionalKineticBlock implements EntityBlock {
    public static final MapCodec<BoardComputerBlock> CODEC = simpleCodec(BoardComputerBlock::new);

    public BoardComputerBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(DirectionalKineticBlock.FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BoardComputerBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BoardComputerBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof BoardComputerBlockEntity boardComputerBlockEntity)
            if (!level.isClientSide) {
                ((ServerPlayer) player).openMenu(new SimpleMenuProvider(boardComputerBlockEntity, Component.literal("Board Computer")), pos);
            }
        return InteractionResult.SUCCESS;
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(DirectionalKineticBlock.FACING).getAxis();
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(DirectionalKineticBlock.FACING) || face == state.getValue(DirectionalKineticBlock.FACING).getOpposite();
    }

    @Override
    public SpeedLevel getMinimumRequiredSpeedLevel() {
        return SpeedLevel.of(128f);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != ModBlockEntitys.BOARDCOMPUTER_BE.get()) {
            return null;
        }
        return (level2, pos, st, be) -> {
            if (be instanceof BoardComputerBlockEntity bce) {
                bce.tick();
            }
        };
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction preferred = this.getPreferredFacing(context);
        if (preferred != null && preferred.getAxis() != Direction.Axis.Y
                && !(context.getPlayer() != null && context.getPlayer().isShiftKeyDown())) {
            return this.defaultBlockState().setValue(DirectionalKineticBlock.FACING, preferred.getOpposite());
        }
        return this.defaultBlockState().setValue(DirectionalKineticBlock.FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        if(true) {
            tooltipComponents.add(Component.translatable("tooltip.createaerocomputers.board_computer_shift"));
        } else {
            tooltipComponents.add(Component.translatable("tooltip.createaerocomputers.pressshift"));
        }
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }
}