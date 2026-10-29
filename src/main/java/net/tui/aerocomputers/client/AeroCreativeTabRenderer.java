
package net.tui.aerocomputers.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.tui.aerocomputers.AeroComputers;

public final class AeroCreativeTabRenderer {

    private static final ResourceLocation COMPUTERS_BANNER =
            ResourceLocation.fromNamespaceAndPath(
                    "createaerocomputers",
                    "textures/gui/creative_tab/computer.png"
            );

    private static final ResourceLocation KINETICS_BANNER =
            ResourceLocation.fromNamespaceAndPath(
                    "createaerocomputers",
                    "textures/gui/creative_tab/kinetics.png"
            );

    private static final ResourceLocation TRANSFORMERS_BANNER =
            ResourceLocation.fromNamespaceAndPath(
                    "createaerocomputers",
                    "textures/gui/creative_tab/transformer.png"
            );

    private static final int BANNER_WIDTH = 162;
    private static final int BANNER_HEIGHT = 18;
    private static final int Y_OFFSET = 14;

    private AeroCreativeTabRenderer() {}

    public static void render(
            CreativeModeInventoryScreen screen,
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float scrollOffs
    ) {
        Minecraft minecraft = Minecraft.getInstance();

        int left = (screen.width - 195) / 2;
        int top = (screen.height - 166) / 2;

        int x = left + 8;

        int clipTop = top + 17;
        int clipBottom = top + 108 + Y_OFFSET;

        int scrollRows = Math.round(scrollOffs*(8-5))*18;

        drawBanner(
                graphics, minecraft, COMPUTERS_BANNER,
                x, top + 18 + Y_OFFSET - scrollRows,
                clipTop, clipBottom,
                Component.translatable(
                        "gui.createaerocomputers.banners.computer"
                )
        );

        drawBanner(
                graphics, minecraft, KINETICS_BANNER,
                x, top + 72 + Y_OFFSET - scrollRows,
                clipTop, clipBottom,
                Component.translatable(
                        "gui.createaerocomputers.banners.kinetics"
                )
        );

        drawBanner(
                graphics, minecraft, TRANSFORMERS_BANNER,
                x, top + 126 + Y_OFFSET - scrollRows,
                clipTop, clipBottom,
                Component.translatable(
                        "gui.createaerocomputers.banners.transformer"
                )
        );
    }

    private static void drawBanner(
            GuiGraphics graphics,
            Minecraft minecraft,
            ResourceLocation texture,
            int x,
            int y,
            int areaTop,
            int areaBottom,
            Component title
    ) {
        // Skip banners that are not completely inside the visible area.
        if (y < areaTop || y + BANNER_HEIGHT > areaBottom) {
            return;
        }

        graphics.blit(
                texture,
                x, y,
                0, 0,
                BANNER_WIDTH, BANNER_HEIGHT,
                BANNER_WIDTH, BANNER_HEIGHT
        );

        graphics.drawString(
                minecraft.font,
                title,
                x + 5,
                y + 5,
                0xFFFFFF,
                false
        );
    }
}
