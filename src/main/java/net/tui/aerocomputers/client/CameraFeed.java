package net.tui.aerocomputers.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;
import net.tui.aerocomputers.AeroComputers;

public class CameraFeed {
    public static final ResourceLocation TEXTURE_ID =
            ResourceLocation.fromNamespaceAndPath(AeroComputers.MOD_ID, "camera_feed");

    private static TextureTarget target;
    private static Marker camEntity;
    private static boolean textureRegistered = false;

    public static boolean rendering = false;   // read by the FOV event
    public static float fov = 70f;

    // set by consumers (overlay / block renderer) every frame -> only render when someone looks at it
    private static boolean requested = false;
    private static Vec3 pos = Vec3.ZERO;
    private static float yaw, pitch;

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static boolean tickPending = false;

    public static void request(Vec3 p, float yawDeg, float pitchDeg, float fovDeg) {
        requested = true; pos = p; yaw = yawDeg; pitch = pitchDeg; fov = fovDeg;
    }

    public static TextureTarget getTarget() { return target; }

    /** Called from ClientTickEvent.Post */
    public static void onClientTick() { tickPending = true; }

    public static void renderIfRequested() {
        if (!requested || !tickPending || rendering) return;
        requested = false;
        tickPending = false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        if (target == null) {
            target = new TextureTarget(WIDTH, HEIGHT, true, Minecraft.ON_OSX);
            target.setClearColor(0f, 0f, 0f, 1f);
        }
        // ... rest unchanged (texture registration, camEntity, swap targets, renderLevel)

        if (!textureRegistered) {
            mc.getTextureManager().register(TEXTURE_ID, new TargetTexture());
            textureRegistered = true;
        }

        if (camEntity == null || camEntity.level() != mc.level) {
            camEntity = new Marker(EntityType.MARKER, mc.level);
        }
        camEntity.setPos(pos);
        camEntity.setYRot(yaw);
        camEntity.setXRot(pitch);
        // no interpolation, otherwise the camera "swims" between old and new position
        camEntity.xo = camEntity.xOld = pos.x;
        camEntity.yo = camEntity.yOld = pos.y;
        camEntity.zo = camEntity.zOld = pos.z;
        camEntity.yRotO = yaw;
        camEntity.xRotO = pitch;

        RenderTarget oldTarget = mc.getMainRenderTarget();
        Entity oldCamera = mc.getCameraEntity();
        boolean oldHand = mc.gameRenderer.renderHand;

        mc.mainRenderTarget = target;
        mc.setCameraEntity(camEntity);
        mc.gameRenderer.renderHand = false;
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        rendering = true;
        try {
            mc.gameRenderer.renderLevel(mc.getTimer());
        } finally {
            rendering = false;
            mc.gameRenderer.renderHand = oldHand;
            mc.mainRenderTarget = oldTarget;
            mc.setCameraEntity(oldCamera);
            oldTarget.bindWrite(true);
        }
    }
}