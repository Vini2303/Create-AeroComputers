package net.tui.aerocomputers.screen.custom;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.network.HudDisplayEntryPayload;
import net.tui.aerocomputers.network.WeaponLinkerEntryPayload;

public class WeaponLinkerScreen extends AbstractContainerScreen<WeaponLinkerMenu> {
    private final ResourceLocation GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(AeroComputers.MOD_ID, "textures/gui/hud/hud_gui.png");

    private EditBox textBox;
    private final BlockPos blockPos;

    public WeaponLinkerScreen(WeaponLinkerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 120;
        this.imageHeight = 30;
        this.titleLabelY = -200;
        this.blockPos = menu.blockEntity.getBlockPos();
    }

    @Override
    protected void init() {
        super.init();

        int width = 100;
        int height = 14;

        int x = (this.width - width) / 2;
        int y = (this.height - height) / 2;

        textBox = new EditBox(
                this.font,
                x,
                y,
                width,
                height,
                Component.literal("")
        );

        textBox.setMaxLength(32);

        textBox.setValue(menu.blockEntity.getEntry());

        this.addRenderableWidget(textBox);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.setShaderTexture(0, GUI_TEXTURE);
        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        guiGraphics.blit(GUI_TEXTURE, x, y, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);
    }

    @Override
    public void removed() {
        PacketDistributor.sendToServer(new WeaponLinkerEntryPayload(blockPos, textBox.getValue()));
        super.removed();
    }
}
