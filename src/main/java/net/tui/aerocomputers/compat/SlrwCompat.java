package net.tui.aerocomputers.compat;

import com.defpwoq.createarms.block.ChinGunBlockEntity;
import com.defpwoq.createarms.block.MFDControllerBlockEntity;
import com.defpwoq.createarms.block.MunitionRackBlockEntity;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.tui.aerocomputers.block.entity.WeaponLinkerBlockEntity;
import org.joml.Vector3d;
import net.tui.aerocomputers.AeroComputers;

import java.lang.reflect.Method;

public final class SlrwCompat {

    private SlrwCompat() {
    }

    private static final Method CHIN_GUN_FIRE_METHOD;

    static {
        try {
            CHIN_GUN_FIRE_METHOD = ChinGunBlockEntity.class.getDeclaredMethod(
                    "fire",
                    Level.class,
                    BlockPos.class,
                    MunitionRackBlockEntity.class,
                    MFDControllerBlockEntity.class
            );

            CHIN_GUN_FIRE_METHOD.setAccessible(true);

        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Could not find Chin Gun fire method!", e);
        }
    }

    private static ChinGunBlockEntity getChinGun(WeaponLinkerBlockEntity linker) {
        if (linker.getLevel() == null) {
            return null;
        }

        BlockEntity connected = linker.getLevel().getBlockEntity(
                linker.getConnectedBlockPos()
        );

        if (connected instanceof ChinGunBlockEntity chinGun) {
            return chinGun;
        }

        return null;
    }

    public static void tick(WeaponLinkerBlockEntity linker) {
        if (!linker.isChinFireEnabled()) {
            return;
        }

        ChinGunBlockEntity gun = getChinGun(linker);

        if (gun != null) {
            fireChinGun(gun);
        }
    }

    public static boolean fireChinGun(ChinGunBlockEntity gun) {
        if (gun == null || gun.getLevel() == null) {
            return false;
        }

        Level level = gun.getLevel();

        if (level.isClientSide || gun.getAmmo() <= 0) {
            return false;
        }

        try {
            CHIN_GUN_FIRE_METHOD.invoke(
                    gun,
                    level,
                    gun.getBlockPos(),
                    null,
                    null
            );

            return true;

        } catch (ReflectiveOperationException e) {
            AeroComputers.LOGGER.error("Failed to fire Chin Gun", e);
            return false;
        }
    }

    public static void setFire(WeaponLinkerBlockEntity linker, boolean fire) {
        ChinGunBlockEntity gun = getChinGun(linker);

        if (gun != null) {
            linker.setChinFireEnabledInternal(fire);
        } else {
            linker.setChinFireEnabledInternal(false);
        }
    }

    public static void setAimAngle(
            WeaponLinkerBlockEntity linker,
            double pitch,
            double yaw
    ) {
        ChinGunBlockEntity gun = getChinGun(linker);

        if (gun == null) {
            return;
        }

        gun.setPitch((float) pitch);
        gun.setYaw((float) yaw);
        gun.setChanged();
    }

    public static void setAimPos(
            WeaponLinkerBlockEntity linker,
            double x,
            double y,
            double z
    ) {
        ChinGunBlockEntity gun = getChinGun(linker);

        if (gun == null) {
            return;
        }

        BlockPos gunPos = gun.getBlockPos();

        Vector3d gunLocalPos = new Vector3d(
                gunPos.getX() + 0.5,
                gunPos.getY() + 0.5,
                gunPos.getZ() + 0.5
        );

        Vector3d targetLocal = new Vector3d(
                x + 0.5,
                y + 0.5,
                z + 0.5
        );

        SubLevelAccess gunSubLevel =
                SableCompanion.INSTANCE.getContaining(gun);

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

        gun.setPitch(pitch);
        gun.setYaw(yaw);
        gun.setChanged();
    }
}