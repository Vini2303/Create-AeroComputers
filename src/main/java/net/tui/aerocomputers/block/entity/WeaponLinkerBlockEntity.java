package net.tui.aerocomputers.block.entity;

import com.defpwoq.createarms.block.CiwsBreechBlockEntity;
import com.defpwoq.createarms.block.MFDControllerBlockEntity;
import com.defpwoq.createarms.block.StingerBlock;
import com.defpwoq.createarms.block.TrapezeRackBlockEntity;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.event.level.NoteBlockEvent;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.screen.custom.HudDisplayMenu;
import net.tui.aerocomputers.screen.custom.WeaponLinkerMenu;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;

import java.util.List;

public class WeaponLinkerBlockEntity extends BlockEntity implements MenuProvider{
    private String HudEntry = "";
    private BlockEntity connectedto;

    public WeaponLinkerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntitys.WEAPONLINKER_BE.get(), pos, blockState);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        tag.putString("HudEntry", HudEntry);
    }

    @Override
    protected void loadAdditional(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        super.loadAdditional(tag, registries);

        HudEntry = tag.getString("HudEntry");
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Set Board Computer");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int i, Inventory playerInventory, Player player) {
        return new WeaponLinkerMenu(i, playerInventory, this);
    }

    public String getEntry() {
        return HudEntry;
    }

    public void setEntry(String entry) {
        this.HudEntry = entry;
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
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }



    public void tick() {
        if (!(this.level instanceof ServerLevel level)) return;

        BlockPos pos = this.getBlockPos().relative(this.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());

        connectedto = this.level.getBlockEntity(pos);

        if(connectedto instanceof MFDControllerBlockEntity mfdControllerBlockEntity) {
            //BlockPos chingun = mfdControllerBlockEntity.getChinGunPos();
            List<BlockPos> racks = mfdControllerBlockEntity.getLinkedRacks();
            if(racks.isEmpty()) {return;}
            BlockEntity rack = this.level.getBlockEntity(racks.getFirst());
            if(rack instanceof TrapezeRackBlockEntity trapezeRackBlockEntity) {
                trapezeRackBlockEntity.startLaunchSequenceDumb(0, level.getServer().getPlayerList().getPlayers().stream().findFirst().orElse(null), racks.getFirst());
            }


            AeroComputers.LOGGER.info(mfdControllerBlockEntity.getLinkedRacks().toString());
        }




        if (connectedto instanceof CannonMountBlockEntity mount) {
            PitchOrientedContraptionEntity entity = mount.getContraption();

            if (entity != null &&
                    entity.getContraption() instanceof MountedAutocannonContraption cannon) {

                BlockPos breechPos = cannon.getStartPos();
                BlockEntity breechEntity = cannon.presentBlockEntities.get(breechPos);

                if (breechEntity instanceof AbstractAutocannonBreechBlockEntity breech) {
                    breech.setFireRate(15);
                }

                entity.tryFiringShot();
            }
        }

    }
}
