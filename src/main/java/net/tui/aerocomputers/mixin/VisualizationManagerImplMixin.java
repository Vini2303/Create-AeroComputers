package net.tui.aerocomputers.mixin;

import net.minecraft.world.level.LevelAccessor;
import net.tui.aerocomputers.client.CameraFeed;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Flywheel's supportsVisualization(level) returns "level == Minecraft.getInstance().level".
 * CameraFeed swaps mc.level to the camera world while it renders, so Flywheel (and Create's
 * block entity renderers, which bail out when it is true) believe a Flywheel visual will draw
 * the block entity. The camera world has no visualization manager, so nothing gets drawn.
 * Answering "false" for the camera world makes everything fall back to the normal vanilla renderers.
 *
 * Client mixin: register it in the "client" array of aerocomputers.mixin.json.
 */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl", remap = false)
public abstract class VisualizationManagerImplMixin {

    @Inject(method = "supportsVisualization", at = @At("HEAD"), cancellable = true)
    private static void aerocomputers$notForCameraWorld(@Nullable LevelAccessor level,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (CameraFeed.isCameraLevel(level)) {
            cir.setReturnValue(false);
        }
    }
}