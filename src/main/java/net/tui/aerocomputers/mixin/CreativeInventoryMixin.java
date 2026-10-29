
package net.tui.aerocomputers.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import net.tui.aerocomputers.client.AeroCreativeTabRenderer;
import net.tui.aerocomputers.item.ModCreativeModeTabs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeInventoryMixin {

    @Shadow
    private static CreativeModeTab selectedTab;

    @Shadow
    private float scrollOffs;

    @Inject(method = "render", at = @At("TAIL"))
    private void aerocomputers$renderBanners(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick,
            CallbackInfo ci
    ) {
        if (selectedTab != ModCreativeModeTabs.AEROCOMPUTERSTAB.get()) {
            return;
        }

        AeroCreativeTabRenderer.render(
                (CreativeModeInventoryScreen) (Object) this,
                graphics,
                mouseX,
                mouseY,
                scrollOffs
        );
    }
}
