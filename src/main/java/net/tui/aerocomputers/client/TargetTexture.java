package net.tui.aerocomputers.client;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

public class TargetTexture extends AbstractTexture {
    @Override public void load(ResourceManager rm) {}
    @Override public int getId() {
        var t = CameraFeed.getTarget();
        return t == null ? 0 : t.getColorTextureId();
    }
    @Override public void releaseId() {}   // the RenderTarget owns the GL texture
    @Override public void close() {}
}