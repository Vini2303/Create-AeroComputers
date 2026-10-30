package net.tui.aerocomputers.renderer;

import com.simibubi.create.content.kinetics.base.ShaftVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.tui.aerocomputers.block.entity.CameraBlockEntity;
import net.tui.aerocomputers.block.entity.SensorBlockEntity;

public class CameraVisual extends ShaftVisual<CameraBlockEntity> {
    public CameraVisual(VisualizationContext context, CameraBlockEntity blockEntity, float partialTick) {
        super(context, blockEntity, partialTick);
    }
}
