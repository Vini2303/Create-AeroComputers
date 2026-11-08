package net.tui.aerocomputers.script;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.tui.aerocomputers.AeroComputers;

@EventBusSubscriber(modid = AeroComputers.MOD_ID)
public final class LockEvents {
    private LockEvents() {}

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            FolderLocks.get(sp.server).relockAll(sp.getUUID());
        }
    }
}