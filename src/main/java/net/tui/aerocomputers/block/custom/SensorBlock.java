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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.tui.aerocomputers.block.entity.BoardComputerBlockEntity;
import net.tui.aerocomputers.block.entity.ModBlockEntitys;
import net.tui.aerocomputers.block.entity.SensorBlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class SensorBlock extends DirectionalKineticBlock implements EntityBlock {
    public static final MapCodec<SensorBlock> CODEC = simpleCodec(SensorBlock::new);

    public SensorBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(DirectionalKineticBlock.FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends SensorBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SensorBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof SensorBlockEntity sensorBlockEntity)
            if (!level.isClientSide) {
                ((ServerPlayer) player).openMenu(new SimpleMenuProvider(sensorBlockEntity, Component.literal("Sensor")), pos);
            }
        return InteractionResult.SUCCESS;
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(DirectionalKineticBlock.FACING).getAxis();
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != ModBlockEntitys.SENSOR_BE.get()) {
            return null;
        }
        return (level2, pos, st, be) -> {
            if (be instanceof SensorBlockEntity bce) {
                bce.tick();
            }
        };
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(DirectionalKineticBlock.FACING) || face == state.getValue(DirectionalKineticBlock.FACING).getOpposite();
    }

    @Override
    public SpeedLevel getMinimumRequiredSpeedLevel() {
        return SpeedLevel.of(64f);
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
            tooltipComponents.add(Component.translatable("tooltip.createaerocomputers.sensor_shift"));
        } else {
            tooltipComponents.add(Component.translatable("tooltip.createaerocomputers.pressshift"));
        }
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }
}