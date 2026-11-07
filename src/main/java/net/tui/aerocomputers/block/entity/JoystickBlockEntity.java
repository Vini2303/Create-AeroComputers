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
import net.minecraft.world.level.block.state.BlockState;
import net.tui.aerocomputers.block.custom.HudDisplayBlock;
import net.tui.aerocomputers.client.HudDisplayRenderCache;
import net.tui.aerocomputers.registries.JoystickRegistry;
import net.tui.aerocomputers.registries.WeaponLinkerRegistry;
import net.tui.aerocomputers.screen.custom.HudDisplayMenu;
import net.tui.aerocomputers.screen.custom.JoystickMenu;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

public class JoystickBlockEntity extends BlockEntity implements MenuProvider{
    private String FREQ_ONE = "";
    private String FREQ_TWO = "";

    public JoystickBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntitys.JOYSTICK_BE.get(), pos, blockState);
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
    }

    public void setFreq(String freq_one, String freq_two) {
        JoystickRegistry.unregister(FREQ_ONE, FREQ_TWO, this);

        this.FREQ_ONE = freq_one;
        this.FREQ_TWO = freq_two;

        JoystickRegistry.register(FREQ_ONE, FREQ_TWO, this);

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

    @Override
    public Component getDisplayName() {
        return Component.literal("Joystick");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int i, Inventory playerInventory, Player player) {
        return new JoystickMenu(i, playerInventory, this);
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
