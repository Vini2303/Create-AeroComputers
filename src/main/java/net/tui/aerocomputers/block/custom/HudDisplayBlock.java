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
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.block.entity.BoardComputerBlockEntity;
import net.tui.aerocomputers.block.entity.HudDisplayBlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class HudDisplayBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<HudDisplayBlock> CODEC = simpleCodec(HudDisplayBlock::new);
    private static final VoxelShape SHAPE1 = box(7, 0, 0, 9,16,16);
    private static final VoxelShape SHAPE2 = box(0, 0, 7, 16,16,9);

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
        if (level.getBlockEntity(pos) instanceof HudDisplayBlockEntity hudDisplayBlockEntity)
            if (!level.isClientSide) {
                ((ServerPlayer) player).openMenu(new SimpleMenuProvider(hudDisplayBlockEntity, Component.literal("Set Board Computer")), pos);
                AeroComputers.LOGGER.info("open Hud Display");
            }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        if(true) {
            tooltipComponents.add(Component.translatable("tooltip.createaerocomputers.hud_display_shift"));
        } else {
            tooltipComponents.add(Component.translatable("tooltip.createaerocomputers.pressshift"));
        }
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }
}
