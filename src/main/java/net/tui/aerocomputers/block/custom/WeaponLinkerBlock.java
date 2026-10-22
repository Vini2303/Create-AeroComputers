package net.tui.aerocomputers.block.custom;

import com.mojang.serialization.MapCodec;
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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.block.entity.BoardComputerBlockEntity;
import net.tui.aerocomputers.block.entity.HudDisplayBlockEntity;
import net.tui.aerocomputers.block.entity.ModBlockEntitys;
import net.tui.aerocomputers.block.entity.WeaponLinkerBlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class WeaponLinkerBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<WeaponLinkerBlock> CODEC = simpleCodec(WeaponLinkerBlock::new);

    public WeaponLinkerBlock(Properties properties) {
        super(properties);
    }

    private static final VoxelShape SHAPE1 = box(0, 0, 0, 16, 16, 2);
    private static final VoxelShape SHAPE2 = box(0, 0, 14, 16, 16, 16);
    private static final VoxelShape SHAPE3 = box(0, 0, 0, 2, 16, 16);
    private static final VoxelShape SHAPE4 = box(14, 0, 0, 16, 16, 16);

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape var10000;
        switch (state.getValue(FACING)) {
            case NORTH -> var10000 = SHAPE2;
            case SOUTH -> var10000 = SHAPE1;
            case WEST -> var10000 = SHAPE4;
            case EAST -> var10000 = SHAPE3;
            default -> var10000 = SHAPE1;
        }

        return var10000;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != ModBlockEntitys.WEAPONLINKER_BE.get()) {
            return null;
        }
        return (level2, pos, st, be) -> {
            if (be instanceof WeaponLinkerBlockEntity bce) {
                bce.tick();
            }
        };
    }




    @Override
    protected MapCodec<? extends WeaponLinkerBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WeaponLinkerBlockEntity(pos, state);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof WeaponLinkerBlockEntity weaponLinerBlockEntity)
            if (!level.isClientSide) {
                ((ServerPlayer) player).openMenu(new SimpleMenuProvider(weaponLinerBlockEntity, Component.literal("Set Board Computer")), pos);
                AeroComputers.LOGGER.info("open WeaponLinker Display");
            }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        if(true) {
            tooltipComponents.add(Component.translatable("tooltip.createaerocomputers.weapon_linker_shift"));
        } else {
            tooltipComponents.add(Component.translatable("tooltip.createaerocomputers.pressshift"));
        }
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }
}
