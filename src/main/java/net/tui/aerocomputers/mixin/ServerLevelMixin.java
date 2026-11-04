package net.tui.aerocomputers.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.tui.aerocomputers.network.CameraChunkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forwards every block change that is sent to clients to the camera feed.
 * Injected at HEAD of ServerLevel.sendBlockUpdated, i.e. before Sable's ServerChunkCache mixin
 * (which cancels blockChanged for sub-level plots) can swallow it.
 *
 * Common mixin: register it in the "mixins" array of aerocomputers.mixin.json.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void aerocomputers$cameraBlockChanged(BlockPos pos, BlockState oldState, BlockState newState,
                                                  int flags, CallbackInfo ci) {
        CameraChunkManager.onBlockChanged((ServerLevel) (Object) this, pos);
    }
}