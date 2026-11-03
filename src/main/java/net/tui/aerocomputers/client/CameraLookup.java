package net.tui.aerocomputers.client;

import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.tui.aerocomputers.registries.CameraRegistry;
import net.tui.aerocomputers.registries.HudRegistry;
import org.joml.Vector3d;

public final class CameraLookup {
    private CameraLookup() {}

    private static final double FORWARD_OFFSET = 0.52;   // start just in front of the camera block, not inside it

    /**
     * Finds the camera block for a "cam" draw entry and requests a render from it.
     * Returns the camera id (its BlockPos), or null if the camera can't be found.
     */
    public static BlockPos requestFor(HudRegistry.DrawEntry e) {
        if (CameraFeed.rendering) return null;   // never draw into the texture being rendered
        if (e.camFreq() == null || e.camFreq().size() < 2) return null;

        BlockEntity cam = CameraRegistry.get(e.camFreq().get(0), e.camFreq().get(1));
        if (cam == null || cam.isRemoved() || cam.getLevel() == null) return null;

        BlockPos camPos = cam.getBlockPos();

        // block position -> real world position (the camera may sit on a Sable sub-level)
        Vector3d p = SableCompanion.INSTANCE.projectOutOfSubLevel(
                cam.getLevel(),
                new Vector3d(camPos.getX() + 0.5, camPos.getY() + 0.5, camPos.getZ() + 0.5),
                new Vector3d());

        // Minecraft convention: yaw 0 = south, pitch > 0 = looking down
        float pitch = (float) e.pitch(), yaw = (float) e.yaw();
        double yawR = Math.toRadians(yaw), pitchR = Math.toRadians(pitch);
        Vec3 forward = new Vec3(-Math.sin(yawR) * Math.cos(pitchR),
                                -Math.sin(pitchR),
                                Math.cos(yawR) * Math.cos(pitchR));
        Vec3 pos = new Vec3(p.x, p.y, p.z).add(forward.scale(FORWARD_OFFSET));

        // the camera block is the id, so every display or overlay showing this camera shares one render
        CameraFeed.request(camPos, pos, pitch, yaw, CameraConfig.FOV);
        return camPos;
    }
}