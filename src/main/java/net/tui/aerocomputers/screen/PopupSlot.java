package net.tui.aerocomputers.screen;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;

/** An inventory slot that only exists while the frequency popup is open, and can be moved. */
public class PopupSlot extends Slot {
    private boolean enabled = false;

    public PopupSlot(Container container, int index) {
        super(container, index, -10000, -10000);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void moveTo(int x, int y) {
        this.x = x;
        this.y = y;
    }

    @Override
    public boolean isActive() {
        return enabled;
    }
}