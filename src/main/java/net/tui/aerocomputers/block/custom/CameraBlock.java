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
import net.tui.aerocomputers.block.entity.CameraBlockEntity;
import net.tui.aerocomputers.block.entity.ModBlockEntitys;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class CameraBlock extends DirectionalKineticBlock implements EntityBlock {
    public static final MapCodec<CameraBlock> CODEC = simpleCodec(CameraBlock::new);

    public CameraBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(DirectionalKineticBlock.FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends CameraBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CameraBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof CameraBlockEntity cameraBlockEntity)
            if (!level.isClientSide) {
                ((ServerPlayer) player).openMenu(new SimpleMenuProvider(cameraBlockEntity, Component.literal("Camera")), pos);
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
        if (type != ModBlockEntitys.CAMERA_BE.get()) {
            return null;
        }
        return (level2, pos, st, be) -> {
            if (be instanceof CameraBlockEntity bce) {
                bce.tick();
            }
        };
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(DirectionalKineticBlock.FACING);
    }

    @Override
    public SpeedLevel getMinimumRequiredSpeedLevel() {
        return SpeedLevel.of(64f);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction preferred = getPreferredFacing(context);

        if (preferred != null &&
                !(context.getPlayer() != null && context.getPlayer().isShiftKeyDown())) {
            return this.defaultBlockState()
                    .setValue(DirectionalKineticBlock.FACING, preferred.getOpposite());
        }

        return this.defaultBlockState()
                .setValue(
                        DirectionalKineticBlock.FACING,
                        context.getNearestLookingDirection().getOpposite()
                );
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        if(true) {
            tooltipComponents.add(Component.translatable("tooltip.createaerocomputers.camera_shift"));
        } else {
            tooltipComponents.add(Component.translatable("tooltip.createaerocomputers.pressshift"));
        }
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }
}