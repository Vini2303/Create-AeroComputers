package net.tui.aerocomputers.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.tui.aerocomputers.block.custom.HudDisplayBlock;
import net.tui.aerocomputers.block.entity.HudDisplayBlockEntity;
import net.tui.aerocomputers.client.CameraFeed;
import net.tui.aerocomputers.client.ClientHudRegistry;
import net.tui.aerocomputers.network.BoardComputerChannelPayload;
import org.joml.Matrix4f;

import java.awt.*;
import java.util.List;

public class HudDisplayRenderer implements BlockEntityRenderer<HudDisplayBlockEntity> {

    /*
     * Virtual HUD resolution.
     *
     * Everything in ClientHudRegistry uses these coordinates.
     */
    private static final float HUD_WIDTH = 640;
    private static final float HUD_HEIGHT = 640;
    private static final float Z_STEP = 0.001f;
    private static final float FACE_OFFSET = 0.502f;
    private static final float BORDER_Z_OFFSET = 0.0001f;

    /*
     * How much of the block face the HUD occupies.
     *
     * 0.90 = 90% of the block width.
     */
    private static final float DISPLAY_WIDTH = 0.90f;

    /*
     * Scale from HUD pixels -> Minecraft block coordinates.
     */
    private static final float SCALE = DISPLAY_WIDTH / HUD_WIDTH;

    /*
     * Center the 640x640 display vertically.
     *
     * Height = 200 * SCALE = 0.09
     * Bottom = (1 - 0.09) / 2 = 0.455
     * Top    = 0.455 + 0.09 = 0.545
     */
    private static final float DISPLAY_HEIGHT = HUD_HEIGHT * SCALE;
    private static final float DISPLAY_X = (1.0f - DISPLAY_WIDTH) / 2.0f;
    private static final float DISPLAY_Y = (1.0f - DISPLAY_HEIGHT) / 2.0f;

    private final Font font;

    public HudDisplayRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    @Override
    public void render(
            HudDisplayBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int packedOverlay
    ) {
        List<ClientHudRegistry.DrawEntry> entries =
                ClientHudRegistry.get(blockEntity.getEntry());

        if (entries.isEmpty()) {
            return;
        }

        poseStack.pushPose();

        Direction facing = blockEntity.getBlockState()
                .getValue(HudDisplayBlock.FACING);

        poseStack.translate(0.5, 0.5, 0.5);

        switch (facing) {
            case SOUTH -> {
            }

            case NORTH -> {
                poseStack.mulPose(Axis.YP.rotationDegrees(180));
            }

            case EAST -> {
                poseStack.mulPose(Axis.YP.rotationDegrees(90));
            }

            case WEST -> {
                poseStack.mulPose(Axis.YP.rotationDegrees(-90));
            }

            case UP -> {
                poseStack.mulPose(Axis.XP.rotationDegrees(90));
            }

            case DOWN -> {
                poseStack.mulPose(Axis.XP.rotationDegrees(-90));
            }
        }

        poseStack.translate(-0.5, -0.5, -0.5);

        /*
         * Move slightly in front of the face.
         */
        poseStack.translate(
                DISPLAY_X,
                DISPLAY_Y + DISPLAY_HEIGHT,
                FACE_OFFSET
        );

        poseStack.scale(
                SCALE,
                -SCALE,
                1.0f
        );

        for (int i = 0; i < entries.size(); i++) {
            ClientHudRegistry.DrawEntry e = entries.get(i);

            poseStack.pushPose();

            poseStack.translate(0, 0, i * Z_STEP);

            switch (e.type()) {
                case "label" -> renderLabel(
                        poseStack,
                        bufferSource,
                        e,
                        packedLight
                );

                case "line" -> renderLine(
                        poseStack,
                        bufferSource,
                        e,
                        packedLight
                );

                case "rect" -> renderRect(
                        poseStack,
                        bufferSource,
                        e,
                        packedLight
                );

                case "poly" -> renderPoly(
                        poseStack,
                        bufferSource,
                        e,
                        packedLight
                );

                case "cam" -> renderCamera(
                        poseStack,
                        bufferSource,
                        e,
                        packedLight
                );
            }

            poseStack.popPose();
        }

        poseStack.popPose();
    }

    // =========================================================
    // COLOR
    // =========================================================

    private int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private int argb(List<Integer> rgb) {
        int r = rgb.size() > 0 ? clamp255(rgb.get(0)) : 255;
        int g = rgb.size() > 1 ? clamp255(rgb.get(1)) : 255;
        int b = rgb.size() > 2 ? clamp255(rgb.get(2)) : 255;

        return 0xFF000000
                | (r << 16)
                | (g << 8)
                | b;
    }

    private int alpha(int color) {
        return (color >> 24) & 0xFF;
    }

    private float red(int color) {
        return ((color >> 16) & 0xFF) / 255.0f;
    }

    private float green(int color) {
        return ((color >> 8) & 0xFF) / 255.0f;
    }

    private float blue(int color) {
        return (color & 0xFF) / 255.0f;
    }

    // =========================================================
    // LABEL
    // =========================================================

    private void renderLabel(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            ClientHudRegistry.DrawEntry e,
            int packedLight
    ) {
        poseStack.pushPose();

        poseStack.translate(
                e.x(),
                e.y(),
                0.001
        );

        /*
         * e.fontSize() is in HUD pixels.
         *
         * Example:
         * fontSize = 4
         * means a 4x larger font in the virtual HUD.
         */
        poseStack.scale(
                (float) e.fontSize(),
                (float) e.fontSize(),
                1.0f
        );

        int color = fromRGBA(e.color().get(0), e.color().get(1), e.color().get(2), 255);

        font.drawInBatch(
                e.text(),
                0,
                0,
                color,
                false,
                poseStack.last().pose(),
                bufferSource,
                Font.DisplayMode.NORMAL,
                0,
                packedLight
        );

        poseStack.popPose();
    }

    // =========================================================
    // RECTANGLE
    // =========================================================

    private void renderRect(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            ClientHudRegistry.DrawEntry e,
            int packedLight
    ) {
        if (e.renderCenter()) {
            renderQuad(
                    poseStack,
                    bufferSource,
                    e.x(),
                    e.y(),
                    e.w(),
                    e.h(),
                    argb(e.centerColor()),
                    packedLight
            );
        }


        if (e.renderBorder()) {
            if(e.renderCenter()) {
                poseStack.translate(0, 0, BORDER_Z_OFFSET);
            }
            int bw = Math.max(1, (int) e.borderWidth());
            int color = argb(e.borderColor());

            // Top
            renderQuad(
                    poseStack,
                    bufferSource,
                    e.x(),
                    e.y(),
                    e.w(),
                    bw,
                    color,
                    packedLight
            );

            // Bottom
            renderQuad(
                    poseStack,
                    bufferSource,
                    e.x(),
                    e.y() + e.h() - bw,
                    e.w(),
                    bw,
                    color,
                    packedLight
            );

            // Left
            renderQuad(
                    poseStack,
                    bufferSource,
                    e.x(),
                    e.y(),
                    bw,
                    e.h(),
                    color,
                    packedLight
            );

            // Right
            renderQuad(
                    poseStack,
                    bufferSource,
                    e.x() + e.w() - bw,
                    e.y(),
                    bw,
                    e.h(),
                    color,
                    packedLight
            );
        }
    }

    private void renderQuad(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            double x,
            double y,
            double width,
            double height,
            int color,
            int packedLight
    ) {
        VertexConsumer buffer =
                bufferSource.getBuffer(RenderType.debugQuads());

        Matrix4f matrix = poseStack.last().pose();

        int a = alpha(color);
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        float x1 = (float) x;
        float y1 = (float) y;
        float x2 = (float) (x + width);
        float y2 = (float) (y + height);

        buffer.addVertex(matrix, x1, y1, 0)
                .setColor(r, g, b, a);

        buffer.addVertex(matrix, x1, y2, 0)
                .setColor(r, g, b, a);

        buffer.addVertex(matrix, x2, y2, 0)
                .setColor(r, g, b, a);

        buffer.addVertex(matrix, x2, y1, 0)
                .setColor(r, g, b, a);
    }

    // =========================================================
    // LINE
    // =========================================================

    private void renderLine(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            ClientHudRegistry.DrawEntry e,
            int packedLight
    ) {
        renderLinePoints(
                poseStack,
                bufferSource,
                e.x(),
                e.y(),
                e.x2(),
                e.y2(),
                e.width(),
                argb(e.color()),
                packedLight
        );
    }

    // =========================================================
    // POLYGON
    // =========================================================

    private void renderPoly(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            ClientHudRegistry.DrawEntry e,
            int packedLight
    ) {
        List<Double> xs = e.xs();
        List<Double> ys = e.ys();

        if (xs.size() < 2 || xs.size() != ys.size()) {
            return;
        }

        int color = argb(e.borderColor());

        // Always render the polygon border
        for (int i = 0; i < xs.size(); i++) {
            int j = (i + 1) % xs.size();

            renderLinePoints(
                    poseStack,
                    bufferSource,
                    xs.get(i),
                    ys.get(i),
                    xs.get(j),
                    ys.get(j),
                    e.borderWidth(),
                    color,
                    packedLight
            );
        }
    }

    private void renderLinePoints(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            double x1,
            double y1,
            double x2,
            double y2,
            double width,
            int color,
            int packedLight
    ) {
        double dx = x2 - x1;
        double dy = y2 - y1;

        double length = Math.sqrt(dx * dx + dy * dy);

        if (length < 0.001) {
            return;
        }

        double nx = -dy / length;
        double ny = dx / length;

        double halfWidth = Math.max(1.0, width) / 2.0;

        renderQuadPoints(
                poseStack,
                bufferSource,

                x1 + nx * halfWidth,
                y1 + ny * halfWidth,

                x1 - nx * halfWidth,
                y1 - ny * halfWidth,

                x2 - nx * halfWidth,
                y2 - ny * halfWidth,

                x2 + nx * halfWidth,
                y2 + ny * halfWidth,

                color,
                packedLight
        );
    }

    private void renderQuadPoints(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            double x1,
            double y1,
            double x2,
            double y2,
            double x3,
            double y3,
            double x4,
            double y4,
            int color,
            int packedLight
    ) {
        VertexConsumer buffer =
                bufferSource.getBuffer(RenderType.debugQuads());

        Matrix4f matrix = poseStack.last().pose();

        int a = alpha(color);
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        buffer.addVertex(
                matrix,
                (float) x1,
                (float) y1,
                0
        ).setColor(r, g, b, a);

        buffer.addVertex(
                matrix,
                (float) x2,
                (float) y2,
                0
        ).setColor(r, g, b, a);

        buffer.addVertex(
                matrix,
                (float) x3,
                (float) y3,
                0
        ).setColor(r, g, b, a);

        buffer.addVertex(
                matrix,
                (float) x4,
                (float) y4,
                0
        ).setColor(r, g, b, a);
    }

    private void renderCamera(PoseStack ps, MultiBufferSource buf, ClientHudRegistry.DrawEntry e, int light) {
        if (CameraFeed.rendering) return;   // never sample the texture we're drawing into

        CameraFeed.requestFixed();
        if (CameraFeed.getTarget() == null) return;   // first frame(s): nothing rendered yet

        VertexConsumer vc = buf.getBuffer(RenderType.text(CameraFeed.TEXTURE_ID));
        Matrix4f m = ps.last().pose();
        float x1 = (float) e.x(), y1 = (float) e.y();
        float x2 = x1 + (float) e.w(), y2 = y1 + (float) e.h();
        int full = 0xF000F0;   // fullbright

        vc.addVertex(m, x1, y2, 0).setColor(255, 255, 255, 255).setUv(0, 0).setLight(full);
        vc.addVertex(m, x2, y2, 0).setColor(255, 255, 255, 255).setUv(1, 0).setLight(full);
        vc.addVertex(m, x2, y1, 0).setColor(255, 255, 255, 255).setUv(1, 1).setLight(full);
        vc.addVertex(m, x1, y1, 0).setColor(255, 255, 255, 255).setUv(0, 1).setLight(full);
    }


    public static int fromRGBA(int r, int g, int b, int a) {
        return (r << 16) + (g << 8) + b + (a << 24);
    }
}