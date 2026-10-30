package net.tui.aerocomputers.renderer;

import com.simibubi.create.content.kinetics.base.ShaftVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.tui.aerocomputers.block.entity.DigitalTransmissionBlockEntity;
import net.tui.aerocomputers.block.entity.SensorBlockEntity;

public class DigitalTransmissionVisual extends ShaftVisual<DigitalTransmissionBlockEntity> {
    public DigitalTransmissionVisual(VisualizationContext context, DigitalTransmissionBlockEntity blockEntity, float partialTick) {
        super(context, blockEntity, partialTick);
    }
}
