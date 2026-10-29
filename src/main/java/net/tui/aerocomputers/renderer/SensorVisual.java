package net.tui.aerocomputers.renderer;

import com.simibubi.create.content.kinetics.base.ShaftVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.tui.aerocomputers.block.entity.BoardComputerBlockEntity;
import net.tui.aerocomputers.block.entity.SensorBlockEntity;

public class SensorVisual extends ShaftVisual<SensorBlockEntity> {
    public SensorVisual(VisualizationContext context, SensorBlockEntity blockEntity, float partialTick) {
        super(context, blockEntity, partialTick);
    }
}
