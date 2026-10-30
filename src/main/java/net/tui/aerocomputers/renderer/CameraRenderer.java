package net.tui.aerocomputers.renderer;

import com.simibubi.create.content.kinetics.base.ShaftRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.tui.aerocomputers.block.entity.CameraBlockEntity;
import net.tui.aerocomputers.block.entity.SensorBlockEntity;

public class CameraRenderer extends ShaftRenderer<CameraBlockEntity> {
    public CameraRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }
}
