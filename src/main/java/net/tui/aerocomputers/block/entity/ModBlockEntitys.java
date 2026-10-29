package net.tui.aerocomputers.block.entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.block.ModBlocks;

import java.util.function.Supplier;

public class ModBlockEntitys {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITYS =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, AeroComputers.MOD_ID);

    public static final Supplier<BlockEntityType<BoardComputerBlockEntity>> BOARDCOMPUTER_BE =
            BLOCK_ENTITYS.register("board_computer_be", () -> BlockEntityType.Builder.of(
                    BoardComputerBlockEntity::new, ModBlocks.BOARDCOMPUTER.get()).build(null));

    public static final Supplier<BlockEntityType<HudDisplayBlockEntity>> HUDDISPLAY_BE =
            BLOCK_ENTITYS.register("hud_display_be", () -> BlockEntityType.Builder.of(
                    HudDisplayBlockEntity::new, ModBlocks.HUDDISPLAY.get()).build(null));

    public static final Supplier<BlockEntityType<WeaponLinkerBlockEntity>> WEAPONLINKER_BE =
            BLOCK_ENTITYS.register("weapon_linker_be", () -> BlockEntityType.Builder.of(
                    WeaponLinkerBlockEntity::new, ModBlocks.WEAPONLINKER.get()).build(null));

    public static final Supplier<BlockEntityType<SensorBlockEntity>> SENSOR_BE =
            BLOCK_ENTITYS.register("sensor_be", () -> BlockEntityType.Builder.of(
                    SensorBlockEntity::new, ModBlocks.SENSORBLOCK.get()).build(null));


    public static void register(IEventBus eventBus) {
        BLOCK_ENTITYS.register(eventBus);
    }
}
