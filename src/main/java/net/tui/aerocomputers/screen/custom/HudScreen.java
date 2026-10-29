package net.tui.aerocomputers.screen.custom;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.network.PacketDistributor;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.network.HudEntryPayload;
import net.tui.aerocomputers.screen.ItemIconButton;

public class HudScreen extends AbstractContainerScreen<HudMenu> {

    private static final ResourceLocation GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    AeroComputers.MOD_ID,
                    "textures/gui/freq_setter.png"
            );

    private static final String FREQ_ONE_KEY = "FreqOne";
    private static final String FREQ_TWO_KEY = "FreqTwo";

    private final InteractionHand hand;

    private ItemStack freqOne = ItemStack.EMPTY;
    private ItemStack freqTwo = ItemStack.EMPTY;

    private ItemIconButton freqOneButton;
    private ItemIconButton freqTwoButton;

    public HudScreen(
            HudMenu menu,
            Inventory playerInventory,
            Component title
    ) {
        super(menu, playerInventory, title);

        this.hand = menu.hand;

        this.imageWidth = 176;
        this.imageHeight = 119;

        this.titleLabelY = -100;
        this.inventoryLabelY = 1000;
    }

    @Override
    protected void init() {
        super.init();

        readCurrentValues();
        createFrequencyButtons();
    }

    private void createFrequencyButtons() {
        if (freqOneButton != null) {
            removeWidget(freqOneButton);
        }

        if (freqTwoButton != null) {
            removeWidget(freqTwoButton);
        }

        int x = (width - 18) / 2;

        freqOneButton = new ItemIconButton(
                x - 15,
                topPos + 9,
                freqOne,
                btn -> selectFrequency(true)
        );

        freqTwoButton = new ItemIconButton(
                x + 15,
                topPos + 9,
                freqTwo,
                btn -> selectFrequency(false)
        );

        addRenderableWidget(freqOneButton);
        addRenderableWidget(freqTwoButton);
    }

    private ItemStack getHeldStack() {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null) {
            return ItemStack.EMPTY;
        }

        return mc.player.getItemInHand(hand);
    }

    private ItemStack getCursorStack() {
        return menu.getCarried();
    }

    private void readCurrentValues() {
        ItemStack stack = getHeldStack();

        if (stack.isEmpty()) {
            freqOne = ItemStack.EMPTY;
            freqTwo = ItemStack.EMPTY;
            return;
        }

        CustomData data = stack.getOrDefault(
                DataComponents.CUSTOM_DATA,
                CustomData.EMPTY
        );

        CompoundTag tag = data.copyTag();

        freqOne = stringToItem(
                tag.contains(FREQ_ONE_KEY)
                        ? tag.getString(FREQ_ONE_KEY)
                        : ""
        );

        freqTwo = stringToItem(
                tag.contains(FREQ_TWO_KEY)
                        ? tag.getString(FREQ_TWO_KEY)
                        : ""
        );
    }

    private ItemStack stringToItem(String id) {
        if (id == null || id.isBlank()) {
            return ItemStack.EMPTY;
        }

        ResourceLocation location = ResourceLocation.tryParse(id);

        if (location == null ||
                !BuiltInRegistries.ITEM.containsKey(location)) {
            return ItemStack.EMPTY;
        }

        Item item = BuiltInRegistries.ITEM.get(location);

        return item == Items.AIR
                ? ItemStack.EMPTY
                : new ItemStack(item);
    }

    private String itemToString(ItemStack stack) {
        return stack.isEmpty()
                ? ""
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private void selectFrequency(boolean first) {
        ItemStack carried = getCursorStack();

        ItemStack selected = carried.isEmpty()
                ? ItemStack.EMPTY
                : carried.copyWithCount(1);

        if (first) {
            freqOne = selected;
        } else {
            freqTwo = selected;
        }

        createFrequencyButtons();

        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(
                        SoundEvents.UI_BUTTON_CLICK,
                        1.0F
                )
        );
    }

    @Override
    public boolean mouseClicked(
            double mouseX,
            double mouseY,
            int button
    ) {
        if (button == 0) {
            if (freqOneButton != null &&
                    freqOneButton.isMouseOver(mouseX, mouseY)) {
                selectFrequency(true);
                return true;
            }

            if (freqTwoButton != null &&
                    freqTwoButton.isMouseOver(mouseX, mouseY)) {
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
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void removed() {
        ItemStack stack = getHeldStack();

        if (!stack.isEmpty()) {
            PacketDistributor.sendToServer(
                    new HudEntryPayload(
                            hand,
                            itemToString(freqOne),
                            itemToString(freqTwo)
                    )
            );
        }

        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}