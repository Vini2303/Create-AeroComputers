package net.tui.aerocomputers.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.tui.aerocomputers.screen.custom.HudScreen;

public final class HUDHelmetClient {
    private HUDHelmetClient() {}

    public static void requestOpen(InteractionHand hand) {
        Minecraft.getInstance().setScreen(new HudScreen(hand));
    }
}