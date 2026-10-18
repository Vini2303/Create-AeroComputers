package net.tui.aerocomputers.block;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.block.custom.BoardComputerBlock;
import net.tui.aerocomputers.block.custom.HudDisplayBlock;
import net.tui.aerocomputers.block.custom.WeaponLinkerBlock;
import net.tui.aerocomputers.item.ModItems;

import java.util.function.Supplier;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AeroComputers.MOD_ID);

    public static final DeferredBlock<Block> BOARDCOMPUTER = registerBlock("board_computer",
            () -> new BoardComputerBlock(BlockBehaviour.Properties.of().strength(2).sound(SoundType.METAL).noOcclusion()));

    public static final DeferredBlock<Block> HUDDISPLAY = registerBlock("hud_display",
            () -> new HudDisplayBlock(BlockBehaviour.Properties.of().strength(0.2f).sound(SoundType.GLASS).noOcclusion()
                    .isViewBlocking((state, level, pos) -> false)
                    .isSuffocating((state, level, pos) -> false)
                    .lightLevel(state -> 0)));

    public static final DeferredBlock<Block> WEAPONLINKER = registerBlock("weapon_linker",
            () -> new WeaponLinkerBlock(BlockBehaviour.Properties.of().strength(2).sound(SoundType.METAL).noOcclusion()));


    private static <T extends Block> DeferredBlock<T> registerBlock(String name, Supplier<T> block) {
        DeferredBlock<T> toReturn = BLOCKS.register(name, block);
        registerBlockItem(name, toReturn);
        return toReturn;
    }

    private static <T extends Block> void registerBlockItem(String name, DeferredBlock<T> block) {
        ModItems.ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }
}
