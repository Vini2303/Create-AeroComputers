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
import net.tui.aerocomputers.network.DigitalTransmissionEntryPayload;
import net.tui.aerocomputers.network.SensorEntryPayload;
import net.tui.aerocomputers.screen.ItemIconButton;

public class DigitalTransmissionScreen extends AbstractContainerScreen<DigitalTransmissionMenu> {

    private final ResourceLocation GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    AeroComputers.MOD_ID,
                    "textures/gui/freq_setter.png"
            );

    private ItemStack freqOne = ItemStack.EMPTY;
    private ItemStack freqTwo = ItemStack.EMPTY;

    private ItemIconButton freqOneButton;
    private ItemIconButton freqTwoButton;

    private final BlockPos blockPos;

    public DigitalTransmissionScreen(
            DigitalTransmissionMenu menu,
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
    protected void init() {
        super.init();

        freqOne = stringToItem(menu.blockEntity.getFreqOne());
        freqTwo = stringToItem(menu.blockEntity.getFreqTwo());

        createFrequencyButtons();
    }

    private void createFrequencyButtons() {
        if (freqOneButton != null) {
            removeWidget(freqOneButton);
        }

        if (freqTwoButton != null) {
            removeWidget(freqTwoButton);
        }

        int x = (width-18) / 2;

        freqOneButton = new ItemIconButton(
                x-15, topPos + 9, freqOne,
                btn -> selectFrequency(true)
        );

        freqTwoButton = new ItemIconButton(
                x+15, topPos + 9, freqTwo,
                btn -> selectFrequency(false)
        );

        addRenderableWidget(freqOneButton);
        addRenderableWidget(freqTwoButton);
    }

    private ItemStack stringToItem(String id) {
        if (id == null || id.isBlank()) {
            return ItemStack.EMPTY;
        }

        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) {
            return ItemStack.EMPTY;
        }

        Item item = BuiltInRegistries.ITEM.get(location);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    private String itemToString(ItemStack stack) {
        return stack.isEmpty()
                ? ""
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private void selectFrequency(boolean first) {
        ItemStack carried = menu.getCarried();

        ItemStack selected = carried.isEmpty()
                ? ItemStack.EMPTY
                : carried.copyWithCount(1);

        if (first) {
            freqOne = selected;
        } else {
            freqTwo = selected;
        }

        createFrequencyButtons();

        // Play the normal button click sound.
        net.minecraft.client.Minecraft.getInstance().getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK,
                        1.0F
                )
        );
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !menu.getCarried().isEmpty()) {
            if (freqOneButton != null && freqOneButton.isMouseOver(mouseX, mouseY)) {
                selectFrequency(true);
                return true;
            }

            if (freqTwoButton != null && freqTwoButton.isMouseOver(mouseX, mouseY)) {
                selectFrequency(false);
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
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
        RenderSystem.setShaderTexture(0, GUI_TEXTURE);

        graphics.blit(
                GUI_TEXTURE,
                leftPos,
                topPos,
                0, 0,
                imageWidth,
                imageHeight,
                176, 119
        );
    }

    @Override
    public void render(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    public void removed() {
        PacketDistributor.sendToServer(
                new DigitalTransmissionEntryPayload(
                        blockPos,
                        itemToString(freqOne),
                        itemToString(freqTwo)
                )
        );

        super.removed();
    }
}