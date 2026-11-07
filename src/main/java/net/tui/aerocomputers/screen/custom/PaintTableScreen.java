package net.tui.aerocomputers.screen.custom;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.network.JoystickEntryPayload;
import net.tui.aerocomputers.screen.ItemIconButton;

public class PaintTableScreen extends AbstractContainerScreen<PaintTableMenu> {
    private final BlockPos blockPos;

    public PaintTableScreen(
            PaintTableMenu menu,
            Inventory playerInventory,
            Component title
    ) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 119;
        this.titleLabelY = -200;
        this.inventoryLabelY = -200;
        this.blockPos = menu.blockEntity.getBlockPos();
    }



    @Override
    protected void renderBg(
            GuiGraphics graphics,
            float partialTick,
            int mouseX,
            int mouseY
    ) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        //RenderSystem.setShaderTexture(0, GUI_TEXTURE);
    }
}