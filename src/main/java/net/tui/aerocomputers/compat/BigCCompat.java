package net.tui.aerocomputers.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.util.Mth;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.tui.aerocomputers.block.entity.WeaponLinkerBlockEntity;
import org.joml.Vector3d;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;

public final class BigCCompat {

    private BigCCompat() {
    }

    private static CannonMountBlockEntity getMount(WeaponLinkerBlockEntity linker) {
        if (linker.getLevel() == null) {
            return null;
        }

        BlockEntity connected = linker.getLevel().getBlockEntity(
                linker.getConnectedBlockPos()
        );

        if (connected instanceof CannonMountBlockEntity mount) {
            return mount;
        }

        return null;
    }

    public static void setFireRate(WeaponLinkerBlockEntity linker, int fireRate) {
        CannonMountBlockEntity mount = getMount(linker);

        if (mount == null) {
            return;
        }

        PitchOrientedContraptionEntity entity = mount.getContraption();

        if (entity != null
                && entity.getContraption() instanceof MountedAutocannonContraption cannon) {

            BlockPos breechPos = cannon.getStartPos();
            BlockEntity breechEntity = cannon.presentBlockEntities.get(breechPos);

            if (breechEntity instanceof AbstractAutocannonBreechBlockEntity breech) {
                breech.setFireRate(Mth.clamp(fireRate, 0, 15));
            }
        }
    }

    public static void setAimAngle(
            WeaponLinkerBlockEntity linker,
            double pitch,
            double yaw
    ) {
        CannonMountBlockEntity mount = getMount(linker);

        if (mount != null) {
            mount.setPitch((float) pitch);
            mount.setYaw((float) yaw);
        }
    }

    public static void setAimPos(
            WeaponLinkerBlockEntity linker,
            double x,
            double y,
            double z
    ) {
        CannonMountBlockEntity mount = getMount(linker);

        if (mount == null) {
            return;
        }

        BlockPos mountPos = mount.getBlockPos();

        Vector3d gunLocalPos = new Vector3d(
                mountPos.getX() + 0.5,
                mountPos.getY() + 2.5,
                mountPos.getZ() + 0.5
        );

        Vector3d targetLocal = new Vector3d(
                x + 0.5,
                y + 0.5,
                z + 0.5
        );

        SubLevelAccess gunSubLevel = SableCompanion.INSTANCE.getContaining(mount);

        if (gunSubLevel != null) {
            gunSubLevel.logicalPose().transformPositionInverse(targetLocal);
        }

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
}