package net.tui.aerocomputers.block.entity;

import com.defpwoq.createarms.block.*;
import com.defpwoq.createarms.network.ModPackets;
import com.defpwoq.createarms.register.ModBlocks;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
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
import org.joml.Vector3d;
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
        BoardComputerBlockEntity computer = ComputerRegistry.get(HudEntry);

        BlockPos pos = this.getBlockPos().relative(this.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
        connectedto = this.level.getBlockEntity(pos);

        if(computer != null) {
            if(connectedto instanceof MFDControllerBlockEntity mfdControllerBlockEntity) {
                BlockPos chingunpos = mfdControllerBlockEntity.getChinGunPos();
                BlockEntity chingun = level.getBlockEntity(chingunpos);

                BoardComputerBlockEntity.Command firechin = computer.getCommand("slrw.firechin");
                BoardComputerBlockEntity.Command aimchinangel = computer.getCommand("slrw.aimchinangel");
                BoardComputerBlockEntity.Command aimchinpos = computer.getCommand("slrw.aimchinpos");

                if(chingun instanceof ChinGunBlockEntity chinGunBlockEntity) {
                    if(firechin != null) {
                        if(firechin.arg(0) == 1) {
                            chinGunBlockEntity.setFiring(true);
                        } else {
                            chinGunBlockEntity.setFiring(false);
                        }
                        if(aimchinangel != null) {
                            chinGunBlockEntity.setPitch((float) aimchinangel.arg(0));
                            chinGunBlockEntity.setYaw((float) aimchinangel.arg(1));
                            chinGunBlockEntity.setChanged();
                        }
                        if(aimchinpos != null) {
                            Vector3d gunLocalPos = new Vector3d(
                                    chingunpos.getX() + 0.5,
                                    chingunpos.getY() + 0.5,
                                    chingunpos.getZ() + 0.5
                            );

                            // Target coordinates supplied by bigc.aimpos (global coordinates).
                            Vector3d targetLocal = new Vector3d(
                                    aimchinpos.arg(0)+0.5,
                                    aimchinpos.arg(1)+0.5,
                                    aimchinpos.arg(2)+0.5
                            );

                            SubLevelAccess gunSubLevel =
                                    SableCompanion.INSTANCE.getContaining(chinGunBlockEntity);

                            // Convert the target into the same coordinate system as the cannon.
                            if (gunSubLevel != null) {
                                gunSubLevel.logicalPose().transformPositionInverse(targetLocal);
                            }

                            // Both positions are now in local coordinates.
                            double dx = targetLocal.x - gunLocalPos.x;
                            double dy = targetLocal.y - gunLocalPos.y;
                            double dz = targetLocal.z - gunLocalPos.z;

                            double horizontal = Math.sqrt(dx * dx + dz * dz);

                            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                            float pitch = (float) -Math.toDegrees(
                                    Math.atan2(dy, horizontal)
                            );

                            chinGunBlockEntity.setPitch(pitch);
                            chinGunBlockEntity.setYaw(yaw);
                            chinGunBlockEntity.setChanged();
                        }
                    }
                }

                //ServerPlayer player = level.getServer()
                //        .getPlayerList()
                //        .getPlayers()
                //        .stream()
                //        .findFirst()
                //        .orElse(null);

                //if (player == null) {
                //    return;
                //}


                //List<BlockPos> racks = mfdControllerBlockEntity.getLinkedRacks();
                //if(racks.isEmpty()) {return;}
                //BlockEntity rack = this.level.getBlockEntity(racks.getFirst());
                //if(rack instanceof TrapezeRackBlockEntity trapezeRackBlockEntity) {
                //    RadarBlockEntity radar = new RadarBlockEntity(racks.getFirst(), ModBlocks.RADAR.get().defaultBlockState());
                //    radar.setSelectedTargetUUID(player.getUUID());
                //    AeroComputers.LOGGER.info(radar.getDetectedTargets().toString());
                //    ModPackets.executeDelayedRadarGuidedFire(
                //            0,
                //            player,
                //            level, radar, trapezeRackBlockEntity, racks.getFirst());
                //}
            }



            if (connectedto instanceof CannonMountBlockEntity mount) {
                BlockPos mountpos = mount.getBlockPos();

                if (computer != null) {
                    BoardComputerBlockEntity.Command aim = computer.getCommand("bigc.aimangel");
                    BoardComputerBlockEntity.Command fire = computer.getCommand("bigc.fire");
                    BoardComputerBlockEntity.Command aimpos = computer.getCommand("bigc.aimpos");

                    if (aim != null) {
                        mount.setPitch((float) aim.arg(0));
                        mount.setYaw((float) aim.arg(1));
                    }

                    if (aimpos != null) {
                        Vector3d gunLocalPos = new Vector3d(
                                mountpos.getX() + 0.5,
                                mountpos.getY() + 2.5,
                                mountpos.getZ() + 0.5
                        );

                        // Target coordinates supplied by bigc.aimpos (global coordinates).
                        Vector3d targetLocal = new Vector3d(
                                aimpos.arg(0)+0.5,
                                aimpos.arg(1)+0.5,
                                aimpos.arg(2)+0.5
                        );

                        SubLevelAccess gunSubLevel =
                                SableCompanion.INSTANCE.getContaining(mount);

                        // Convert the target into the same coordinate system as the cannon.
                        if (gunSubLevel != null) {
                            gunSubLevel.logicalPose().transformPositionInverse(targetLocal);
                        }

                        // Both positions are now in local coordinates.
                        double dx = targetLocal.x - gunLocalPos.x;
                        double dy = targetLocal.y - gunLocalPos.y;
                        double dz = targetLocal.z - gunLocalPos.z;

                        double horizontal = Math.sqrt(dx * dx + dz * dz);

                        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                        float pitch = (float) -Math.toDegrees(
                                Math.atan2(dy, horizontal)
                        );

                        mount.setPitch(-pitch);
                        mount.setYaw(yaw);
                    }

                    PitchOrientedContraptionEntity entity = mount.getContraption();

                    if (entity != null &&
                            entity.getContraption() instanceof MountedAutocannonContraption cannon) {

                        BlockPos breechPos = cannon.getStartPos();
                        BlockEntity breechEntity = cannon.presentBlockEntities.get(breechPos);

                        if (breechEntity instanceof AbstractAutocannonBreechBlockEntity breech) {
                            if (fire != null) {
                                if(fire.arg(0) < 0) {
                                    breech.setFireRate(0);
                                } else if(fire.arg(0) > 15) {
                                    breech.setFireRate(15);
                                } else {
                                    breech.setFireRate((int) fire.arg(0));
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
