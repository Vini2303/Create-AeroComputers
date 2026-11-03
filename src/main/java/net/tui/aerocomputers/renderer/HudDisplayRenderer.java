package net.tui.aerocomputers.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.tui.aerocomputers.block.custom.HudDisplayBlock;
import net.tui.aerocomputers.block.entity.HudDisplayBlockEntity;
import net.tui.aerocomputers.client.CameraFeed;
import net.tui.aerocomputers.client.CameraLookup;
import net.tui.aerocomputers.client.HudDisplayRenderCache;
import net.tui.aerocomputers.registries.HudRegistry;
import org.joml.Matrix4f;

import java.util.*;
import java.util.ArrayDeque;

public class HudDisplayRenderer implements BlockEntityRenderer<HudDisplayBlockEntity> {

    private static final float HUD_UNIT = 640.0f;

    private static final float DISPLAY_WIDTH = 0.90f;
    private static final float SCALE = DISPLAY_WIDTH / HUD_UNIT;

    private static final float FACE_OFFSET = 0.502f;
    private static final float Z_STEP = 0.001f;
    private static final float BORDER_Z_OFFSET = 0.0001f;

    private final Font font;

    // Current rendering bounds, in virtual HUD pixels.
    private float currentHudWidth;
    private float currentHudHeight;

    public HudDisplayRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    // =========================================================
    // RENDER
    // =========================================================

    @Override
    public void render(
            HudDisplayBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int packedOverlay
    ) {
        List<HudRegistry.DrawEntry> entries =
                HudRegistry.get(
                        blockEntity.getFreqOne(),
                        blockEntity.getFreqTwo()
                );

        if (entries.isEmpty())
            return;

        BlockPos currentPos = blockEntity.getBlockPos();

        // Find the largest completely filled rectangle.
        DisplayRectangle rectangle = getCachedRectangle(blockEntity);

        if (rectangle == null)
            return;

        // Only the top-left block renders the HUD.
        if (!currentPos.equals(rectangle.topLeft()))
            return;

        int widthBlocks = rectangle.width();
        int heightBlocks = rectangle.height();

        /*
         * The display surface is inset by 0.05 blocks on every side.
         *
         * For multiple blocks, the HUD spans from:
         *
         * X = 0.05
         * X = widthBlocks - 0.05
         *
         * The same applies vertically.
         */
        currentHudWidth =
                ((widthBlocks - 0.1f) / DISPLAY_WIDTH) * HUD_UNIT;

        currentHudHeight =
                ((heightBlocks - 0.1f) / DISPLAY_WIDTH) * HUD_UNIT;

        poseStack.pushPose();

        Direction facing = blockEntity.getBlockState()
                .getValue(HudDisplayBlock.FACING);

        poseStack.translate(0.5, 0.5, 0.5);

        switch (facing) {
            case SOUTH -> {
            }

            case NORTH ->
                    poseStack.mulPose(Axis.YP.rotationDegrees(180));

            case EAST ->
                    poseStack.mulPose(Axis.YP.rotationDegrees(90));

            case WEST ->
                    poseStack.mulPose(Axis.YP.rotationDegrees(-90));

            case UP ->
                    poseStack.mulPose(Axis.XP.rotationDegrees(90));

            case DOWN ->
                    poseStack.mulPose(Axis.XP.rotationDegrees(-90));
        }

        poseStack.translate(-0.5, -0.5, -0.5);

        /*
         * The current block is the top-left block of the rectangle.
         *
         * Therefore:
         * X starts at the left edge + 0.05.
         * Y starts at the top edge - 0.05.
         */
        poseStack.translate(
                0.05,
                0.95,
                FACE_OFFSET
        );

        poseStack.scale(
                SCALE,
                -SCALE,
                1.0f
        );

        for (int i = 0; i < entries.size(); i++) {
            HudRegistry.DrawEntry e = entries.get(i);

            poseStack.pushPose();

            poseStack.translate(0, 0, i * Z_STEP);

            switch (e.type()) {
                case "label" ->
                        renderLabel(poseStack, bufferSource, e, packedLight);

                case "line" ->
                        renderLine(poseStack, bufferSource, e, packedLight);

                case "rect" ->
                        renderRect(poseStack, bufferSource, e, packedLight);

                case "poly" ->
                        renderPoly(poseStack, bufferSource, e, packedLight);

                case "cam" ->
                        renderCamera(poseStack, bufferSource, e);
            }

            poseStack.popPose();
        }

        poseStack.popPose();
    }

    // =========================================================
    // CONNECTED DISPLAY DETECTION
    // =========================================================

    private record GridCell(int x, int y) {}

    private record DisplayRectangle(
            BlockPos topLeft,
            int width,
            int height,
            int area
    ) {}

    private record RectangleCandidate(
            int leftX,
            int topY,
            int width,
            int height
    ) {
        int area() {
            return width * height;
        }
    }

    private DisplayRectangle findLargestRectangle(
            HudDisplayBlockEntity startBE
    ) {
        Level level = startBE.getLevel();

        if (level == null) {
            return new DisplayRectangle(
                    startBE.getBlockPos(),
                    1,
                    1,
                    1
            );
        }

        BlockPos startPos = startBE.getBlockPos();

        BlockState startState = level.getBlockState(startPos);

        if (!(startState.getBlock() instanceof HudDisplayBlock)) {
            return new DisplayRectangle(startPos, 1, 1, 1);
        }

        Direction facing = startState.getValue(HudDisplayBlock.FACING);

        Direction rightDirection = facing.getCounterClockWise();

        String freqOne = startBE.getFreqOne();
        String freqTwo = startBE.getFreqTwo();

        // Find the entire connected group.
        Set<BlockPos> connected = findConnectedDisplays(
                level,
                startPos,
                facing,
                freqOne,
                freqTwo
        );

        if (connected.isEmpty()) {
            return new DisplayRectangle(startPos, 1, 1, 1);
        }

        /*
         * Convert world positions into a 2D grid.
         *
         * X = screen-right direction.
         * Y = world vertical direction.
         */
        Map<GridCell, BlockPos> grid = new HashMap<>();

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (BlockPos pos : connected) {
            int gridX =
                    pos.getX() * rightDirection.getStepX()
                            + pos.getZ() * rightDirection.getStepZ();

            int gridY = pos.getY();

            grid.put(
                    new GridCell(gridX, gridY),
                    pos
            );

            minX = Math.min(minX, gridX);
            maxX = Math.max(maxX, gridX);

            minY = Math.min(minY, gridY);
            maxY = Math.max(maxY, gridY);
        }

        int gridWidth = maxX - minX + 1;
        int gridHeight = maxY - minY + 1;

        /*
         * Find the largest rectangle using a histogram.
         *
         * Each row increases the histogram height where a display
         * exists and resets it where a display is missing.
         */
        int[] heights = new int[gridWidth];

        RectangleCandidate best = null;

        for (int y = minY; y <= maxY; y++) {

            for (int x = 0; x < gridWidth; x++) {
                GridCell cell = new GridCell(minX + x, y);

                if (grid.containsKey(cell)) {
                    heights[x]++;
                } else {
                    heights[x] = 0;
                }
            }

            int[] stack = new int[gridWidth + 1];
            int stackSize = 0;

            for (int i = 0; i <= gridWidth; i++) {

                int currentHeight =
                        i == gridWidth ? 0 : heights[i];

                while (stackSize > 0
                        && heights[stack[stackSize - 1]] > currentHeight) {

                    int height = heights[stack[--stackSize]];

                    int left = stackSize == 0
                            ? 0
                            : stack[stackSize - 1] + 1;

                    int right = i - 1;

                    int width = right - left + 1;

                    if (height <= 0 || width <= 0)
                        continue;

                    RectangleCandidate candidate =
                            new RectangleCandidate(
                                    minX + left,
                                    y,
                                    width,
                                    height
                            );

                    if (isBetterRectangle(candidate, best)) {
                        best = candidate;
                    }
                }

                if (i < gridWidth) {
                    stack[stackSize++] = i;
                }
            }
        }

        if (best == null) {
            return new DisplayRectangle(startPos, 1, 1, 1);
        }

        /*
         * Convert the top-left grid coordinate back into a world
         * position.
         */
        int startGridX =
                startPos.getX() * rightDirection.getStepX()
                        + startPos.getZ() * rightDirection.getStepZ();

        int startGridY = startPos.getY();

        int offsetX = best.leftX() - startGridX;
        int offsetY = best.topY() - startGridY;

        BlockPos topLeft = startPos.offset(
                rightDirection.getStepX() * offsetX,
                offsetY,
                rightDirection.getStepZ() * offsetX
        );

        return new DisplayRectangle(
                topLeft,
                best.width(),
                best.height(),
                best.area()
        );
    }

    private boolean isBetterRectangle(
            RectangleCandidate candidate,
            RectangleCandidate current
    ) {
        if (current == null)
            return true;

        // First: largest area.
        if (candidate.area() != current.area()) {
            return candidate.area() > current.area();
        }

        // Tie: choose the rectangle with the highest top edge.
        if (candidate.topY() != current.topY()) {
            return candidate.topY() > current.topY();
        }

        // Tie: choose the rectangle furthest left.
        if (candidate.leftX() != current.leftX()) {
            return candidate.leftX() < current.leftX();
        }

        // Final tie: prefer the wider rectangle.
        return candidate.width() > current.width();
    }

    private Set<BlockPos> findConnectedDisplays(
            Level level,
            BlockPos startPos,
            Direction facing,
            String freqOne,
            String freqTwo
    ) {
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        queue.add(startPos);
        visited.add(startPos);

        Direction[] directions = {
                Direction.UP,
                Direction.DOWN,
                facing.getCounterClockWise(),
                facing.getClockWise()
        };

        while (!queue.isEmpty()) {
            BlockPos currentPos = queue.poll();

            for (Direction direction : directions) {
                BlockPos neighborPos = currentPos.relative(direction);

                if (visited.contains(neighborPos))
                    continue;

                BlockState neighborState =
                        level.getBlockState(neighborPos);

                if (!(neighborState.getBlock() instanceof HudDisplayBlock))
                    continue;

                if (neighborState.getValue(HudDisplayBlock.FACING) != facing)
                    continue;

                if (!(level.getBlockEntity(neighborPos)
                        instanceof HudDisplayBlockEntity neighborBE))
                    continue;

                if (!neighborBE.getFreqOne().equals(freqOne))
                    continue;

                if (!neighborBE.getFreqTwo().equals(freqTwo))
                    continue;

                visited.add(neighborPos);
                queue.add(neighborPos);
            }
        }

        return visited;
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

    // =========================================================
    // LABEL
    // =========================================================

    private void renderLabel(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            HudRegistry.DrawEntry e,
            int packedLight
    ) {
        double x = e.x();
        double y = e.y();

        if (x < 0 || y < 0 || y > currentHudHeight)
            return;

        if (x >= currentHudWidth)
            return;

        String text = e.text();

        /*
         * Trim text that would extend beyond the right edge.
         * The label's starting position must be inside the HUD.
         */
        poseStack.pushPose();

        poseStack.translate(x, y, 0.001);

        poseStack.scale(
                (float) e.fontSize(),
                (float) e.fontSize(),
                1.0f
        );

        float availableWidth =
                (float) ((currentHudWidth - x) / e.fontSize());

        if (availableWidth <= 0) {
            poseStack.popPose();
            return;
        }

        while (!text.isEmpty() && font.width(text) > availableWidth) {
            text = text.substring(0, text.length() - 1);
        }

        if (!text.isEmpty()) {
            int color = fromRGBA(
                    e.color().get(0),
                    e.color().get(1),
                    e.color().get(2),
                    255
            );

            font.drawInBatch(
                    text,
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
        }

        poseStack.popPose();
    }

    // =========================================================
    // RECTANGLE
    // =========================================================

    private void renderRect(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            HudRegistry.DrawEntry e,
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
            if (e.renderCenter()) {
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
        renderQuadPoints(
                poseStack,
                bufferSource,
                x, y,
                x, y + height,
                x + width, y + height,
                x + width, y,
                color,
                packedLight
        );
    }

    // =========================================================
    // LINE
    // =========================================================

    private void renderLine(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            HudRegistry.DrawEntry e,
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

        if (length < 0.001)
            return;

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

    // =========================================================
    // POLYGON
    // =========================================================

    private void renderPoly(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            HudRegistry.DrawEntry e,
            int packedLight
    ) {
        List<Double> xs = e.xs();
        List<Double> ys = e.ys();

        if (xs.size() < 2 || xs.size() != ys.size())
            return;

        int color = argb(e.borderColor());

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

    // =========================================================
    // GEOMETRY CLIPPING
    // =========================================================

    private record ClipVertex(
            double x,
            double y,
            double u,
            double v
    ) {}

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
        List<ClipVertex> polygon = new ArrayList<>();

        polygon.add(new ClipVertex(x1, y1, 0, 0));
        polygon.add(new ClipVertex(x2, y2, 0, 0));
        polygon.add(new ClipVertex(x3, y3, 0, 0));
        polygon.add(new ClipVertex(x4, y4, 0, 0));

        polygon = clipPolygon(polygon);

        if (polygon.size() < 3)
            return;

        VertexConsumer buffer =
                bufferSource.getBuffer(RenderType.debugQuads());

        Matrix4f matrix = poseStack.last().pose();

        int a = alpha(color);
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        ClipVertex first = polygon.get(0);

        for (int i = 1; i < polygon.size() - 1; i++) {
            ClipVertex second = polygon.get(i);
            ClipVertex third = polygon.get(i + 1);

            // Triangle 1, represented as a quad.
            addSolidVertex(buffer, matrix, first, r, g, b, a);
            addSolidVertex(buffer, matrix, second, r, g, b, a);
            addSolidVertex(buffer, matrix, third, r, g, b, a);
            addSolidVertex(buffer, matrix, third, r, g, b, a);
        }
    }

    private void addSolidVertex(
            VertexConsumer buffer,
            Matrix4f matrix,
            ClipVertex vertex,
            int r,
            int g,
            int b,
            int a
    ) {
        buffer.addVertex(
                matrix,
                (float) vertex.x(),
                (float) vertex.y(),
                0
        ).setColor(r, g, b, a);
    }

    private List<ClipVertex> clipPolygon(List<ClipVertex> input) {
        List<ClipVertex> result = input;

        // Left edge
        result = clipEdge(result, 0);

        // Right edge
        result = clipEdge(result, 1);

        // Top edge
        result = clipEdge(result, 2);

        // Bottom edge
        result = clipEdge(result, 3);

        return result;
    }

    private List<ClipVertex> clipEdge(
            List<ClipVertex> input,
            int edge
    ) {
        List<ClipVertex> output = new ArrayList<>();

        if (input.isEmpty())
            return output;

        ClipVertex previous = input.get(input.size() - 1);
        boolean previousInside = isInside(previous, edge);

        for (ClipVertex current : input) {
            boolean currentInside = isInside(current, edge);

            if (currentInside) {
                if (!previousInside) {
                    output.add(intersect(previous, current, edge));
                }

                output.add(current);
            } else if (previousInside) {
                output.add(intersect(previous, current, edge));
            }

            previous = current;
            previousInside = currentInside;
        }

        return output;
    }

    private boolean isInside(ClipVertex vertex, int edge) {
        return switch (edge) {
            case 0 -> vertex.x() >= 0;
            case 1 -> vertex.x() <= currentHudWidth;
            case 2 -> vertex.y() >= 0;
            case 3 -> vertex.y() <= currentHudHeight;
            default -> false;
        };
    }

    private ClipVertex intersect(
            ClipVertex a,
            ClipVertex b,
            int edge
    ) {
        double t;

        switch (edge) {
            case 0, 1 -> {
                double boundary = edge == 0 ? 0 : currentHudWidth;
                double dx = b.x() - a.x();

                if (Math.abs(dx) < 0.000001)
                    t = 0;
                else
                    t = (boundary - a.x()) / dx;
            }

            case 2, 3 -> {
                double boundary = edge == 2 ? 0 : currentHudHeight;
                double dy = b.y() - a.y();

                if (Math.abs(dy) < 0.000001)
                    t = 0;
                else
                    t = (boundary - a.y()) / dy;
            }

            default -> t = 0;
        }

        t = Math.max(0, Math.min(1, t));

        double x = a.x() + (b.x() - a.x()) * t;
        double y = a.y() + (b.y() - a.y()) * t;
        double u = a.u() + (b.u() - a.u()) * t;
        double v = a.v() + (b.v() - a.v()) * t;

        return new ClipVertex(x, y, u, v);
    }

    // =========================================================
    // CAMERA
    // =========================================================

    private void renderCamera(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            HudRegistry.DrawEntry e
    ) {
        BlockPos camPos = CameraLookup.requestFor(e);

        if (camPos == null || CameraFeed.getTarget(camPos) == null)
            return;

        List<ClipVertex> polygon = new ArrayList<>();

        double x1 = e.x();
        double y1 = e.y();

        double x2 = x1 + e.w();
        double y2 = y1 + e.h();

        // Preserve the original camera UV orientation.
        polygon.add(new ClipVertex(x1, y2, 0, 0));
        polygon.add(new ClipVertex(x2, y2, 1, 0));
        polygon.add(new ClipVertex(x2, y1, 1, 1));
        polygon.add(new ClipVertex(x1, y1, 0, 1));

        polygon = clipPolygon(polygon);

        if (polygon.size() < 3)
            return;

        VertexConsumer buffer = bufferSource.getBuffer(
                RenderType.text(CameraFeed.getTextureId(camPos))
        );

        Matrix4f matrix = poseStack.last().pose();

        int full = 0xF000F0;

        ClipVertex first = polygon.get(0);

        for (int i = 1; i < polygon.size() - 1; i++) {
            ClipVertex second = polygon.get(i);
            ClipVertex third = polygon.get(i + 1);

            // Triangle represented as a quad.
            addCameraVertex(buffer, matrix, first, full);
            addCameraVertex(buffer, matrix, second, full);
            addCameraVertex(buffer, matrix, third, full);
            addCameraVertex(buffer, matrix, third, full);
        }
    }

    private void addCameraVertex(
            VertexConsumer buffer,
            Matrix4f matrix,
            ClipVertex vertex,
            int packedLight
    ) {
        buffer.addVertex(
                        matrix,
                        (float) vertex.x(),
                        (float) vertex.y(),
                        0
                )
                .setColor(255, 255, 255, 255)
                .setUv((float) vertex.u(), (float) vertex.v())
                .setLight(packedLight);
    }

    // =========================================================
    // COLOR HELPER
    // =========================================================

    public static int fromRGBA(int r, int g, int b, int a) {
        return (r << 16)
                + (g << 8)
                + b
                + (a << 24);
    }

    private record CachedRectangle(
            long revision,
            DisplayRectangle rectangle
    ) {}

    private static final Map<Level, Map<BlockPos, CachedRectangle>> RECTANGLE_CACHE =
            new WeakHashMap<>();

    private DisplayRectangle getCachedRectangle(
            HudDisplayBlockEntity blockEntity
    ) {
        Level level = blockEntity.getLevel();

        if (level == null)
            return null;

        long revision = HudDisplayRenderCache.getRevision(level);

        synchronized (RECTANGLE_CACHE) {
            Map<BlockPos, CachedRectangle> levelCache =
                    RECTANGLE_CACHE.computeIfAbsent(
                            level,
                            ignored -> new HashMap<>()
                    );

            BlockPos pos = blockEntity.getBlockPos();

            CachedRectangle cached = levelCache.get(pos);

            if (cached != null && cached.revision() == revision) {
                return cached.rectangle();
            }

            DisplayRectangle rectangle = findLargestRectangle(blockEntity);

            if (rectangle != null) {
                levelCache.put(
                        pos,
                        new CachedRectangle(revision, rectangle)
                );
            }

            return rectangle;
        }
    }

    @Override
    public AABB getRenderBoundingBox(HudDisplayBlockEntity blockEntity) {
        DisplayRectangle rectangle = getCachedRectangle(blockEntity);

        if (rectangle == null)
            return new AABB(blockEntity.getBlockPos());

        BlockPos topLeft = rectangle.topLeft();

        Direction facing = blockEntity.getBlockState()
                .getValue(HudDisplayBlock.FACING);

        Direction right = facing.getCounterClockWise();

        BlockPos bottomRight = topLeft.offset(
                right.getStepX() * (rectangle.width() - 1),
                -(rectangle.height() - 1),
                right.getStepZ() * (rectangle.width() - 1)
        );

        double minX = Math.min(topLeft.getX(), bottomRight.getX());
        double maxX = Math.max(topLeft.getX(), bottomRight.getX()) + 1.0;

        double minY = bottomRight.getY();
        double maxY = topLeft.getY() + 1.0;

        double minZ = Math.min(topLeft.getZ(), bottomRight.getZ());
        double maxZ = Math.max(topLeft.getZ(), bottomRight.getZ()) + 1.0;

        return new AABB(
                minX, minY, minZ,
                maxX, maxY, maxZ
        ).inflate(0.1);
    }

    @Override
    public boolean shouldRender(
            HudDisplayBlockEntity blockEntity,
            net.minecraft.world.phys.Vec3 cameraPos
    ) {
        AABB bounds = getRenderBoundingBox(blockEntity);

        double viewDistance = getViewDistance();

        return bounds.distanceToSqr(cameraPos)
                < viewDistance * viewDistance;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}