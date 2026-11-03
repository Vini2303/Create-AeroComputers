package net.tui.aerocomputers.client;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.packs.resources.ResourceManager;

/** Lets vanilla RenderTypes sample a camera's framebuffer without copying it. One instance per camera. */
public class TargetTexture extends AbstractTexture {
    private final BlockPos cameraId;

    public TargetTexture(BlockPos cameraId) {
        this.cameraId = cameraId;
    }

    @Override public void load(ResourceManager rm) {}

    @Override public int getId() {
        var t = CameraFeed.getTarget(cameraId);
        return t == null ? 0 : t.getColorTextureId();
    }

    @Override public void releaseId() {}   // the RenderTarget owns the GL texture
    @Override public void close() {}
}