package net.tui.aerocomputers.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.item.ModItems;
import net.tui.aerocomputers.network.HudEntryPayload;
import org.joml.Matrix4f;

import java.util.List;

public class HudOverlay implements LayeredDraw.Layer {

    @Override
    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;

        ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
        if (!helmet.is(ModItems.HUD_HELMET.get())) return;

        CustomData data = helmet.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag tag = data.copyTag();
        String linkedName = tag.contains(HudEntryPayload.NBT_KEY) ? tag.getString(HudEntryPayload.NBT_KEY) : "";

        List<ClientHudRegistry.DrawEntry> entries = ClientHudRegistry.get(linkedName);

        for (ClientHudRegistry.DrawEntry e : entries) {
            switch (e.type()) {
                case "label" -> renderLabel(guiGraphics, e);
                case "line" -> renderLine(guiGraphics, e);
                case "rect" -> renderRect(guiGraphics, e);
                case "poly" -> renderPoly(guiGraphics, e);
                case "cam" -> renderCamera(guiGraphics, e);
            }
        }
    }

    private int argb(List<Integer> rgb) {
        int r = rgb.size() > 0 ? clamp255(rgb.get(0)) : 255;
        int g = rgb.size() > 1 ? clamp255(rgb.get(1)) : 255;
        int b = rgb.size() > 2 ? clamp255(rgb.get(2)) : 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private int clamp255(int v) { return Math.max(0, Math.min(255, v)); }

    private void renderLabel(GuiGraphics g, ClientHudRegistry.DrawEntry e) {
        var font = Minecraft.getInstance().font;
        g.pose().pushPose();
        g.pose().translate((float) e.x(), (float) e.y(), 0);
        g.pose().scale((float) e.fontSize(), (float) e.fontSize(), 1f);
        g.drawString(font, e.text(), 0, 0, argb(e.color()));
        g.pose().popPose();
    }

    private void renderRect(GuiGraphics g, ClientHudRegistry.DrawEntry e) {
        int x = (int) e.x(), y = (int) e.y(), w = (int) e.w(), h = (int) e.h();
        if (e.renderCenter()) {
            g.fill(x, y, x + w, y + h, argb(e.centerColor()));
        }
        if (e.renderBorder()) {
            int bw = Math.max(1, (int) e.borderWidth());
            int c = argb(e.borderColor());
            g.fill(x, y, x + w, y + bw, c);
            g.fill(x, y + h - bw, x + w, y + h, c);
            g.fill(x, y, x + bw, y + h, c);
            g.fill(x + w - bw, y, x + w, y + h, c);
        }
    }

    private void renderLine(GuiGraphics g, ClientHudRegistry.DrawEntry e) {
        double dx = e.x2() - e.x();
        double dy = e.y2() - e.y();
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001) return;
        double angle = Math.atan2(dy, dx);

        g.pose().pushPose();
        g.pose().translate((float) e.x(), (float) e.y(), 0);
        g.pose().mulPose(new org.joml.Quaternionf().rotateZ((float) angle));
        int w = Math.max(1, (int) e.width());
        g.fill(0, -w / 2, (int) len, w / 2 + 1, argb(e.color()));
        g.pose().popPose();
    }

    // Polygon fill/border via GuiGraphics primitives only — no true tessellated
    // fill is attempted here; this draws the outline as connected line segments
    // and approximates a filled center using the bounding box. See note below.
    private void renderPoly(GuiGraphics g, ClientHudRegistry.DrawEntry e) {
        List<Double> xs = e.xs();
        List<Double> ys = e.ys();
        if (xs.size() < 2 || xs.size() != ys.size()) return;

        for (int i = 0; i < xs.size(); i++) {
            int j = (i + 1) % xs.size();
            var lineEntry = new ClientHudRegistry.DrawEntry(
                    "line", xs.get(i), ys.get(i), xs.get(j), ys.get(j),
                    0, 0, "", 0, e.borderWidth(), 0, false, false,
                    e.borderColor(), List.of(), List.of(), List.of(), List.of()
            );
            renderLine(g, lineEntry);
        }
    }

    private void renderCamera(GuiGraphics g, ClientHudRegistry.DrawEntry e) {
        if (CameraFeed.rendering) return;

        CameraFeed.requestFixed();

        var target = CameraFeed.getTarget();
        if (target == null) return;

        float x = (float) e.x();
        float y = (float) e.y();
        float w = (float) e.w();
        float h = (float) e.h();

        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        RenderSystem.setShaderColor(1, 1, 1, 1);

        Matrix4f m = g.pose().last().pose();

        BufferBuilder b = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_TEX
        );

        b.addVertex(m, x,     y + h, 0).setUv(0, 0);
        b.addVertex(m, x + w, y + h, 0).setUv(1, 0);
        b.addVertex(m, x + w, y,     0).setUv(1, 1);
        b.addVertex(m, x,     y,     0).setUv(0, 1);

        BufferUploader.drawWithShader(b.buildOrThrow());

        RenderSystem.setShaderColor(1, 1, 1, 1);
    }
}