package net.tui.aerocomputers.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Fixed camera settings. Deliberately has NO client-only imports so the server
 * can use it without loading client classes.
 * Location: net/tui/aerocomputers/client/CameraConfig.java
 */
public final class CameraConfig {
    private CameraConfig() {}

    public static final Vec3 POS = new Vec3(0, 80, 0);
    public static final float YAW = 0f;
    public static final float PITCH = 30f;   // positive = looking down
    public static final float FOV = 70f;

    /** Ticket distance. 2 = 5x5 chunks fully loaded (needed so the 3x3 in the middle renders). */
    public static final int LOAD_DISTANCE = 2;
    /** Chunks within this radius are sent to clients IF they are already loaded. */
    public static final int SEND_RADIUS = 10;
    /** Entities within this many blocks of the camera are synced. */
    public static final double ENTITY_RADIUS = 96;

    public static ChunkPos chunk() {
        return new ChunkPos(BlockPos.containing(POS));
    }
}