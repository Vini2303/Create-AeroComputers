package net.tui.aerocomputers.renderer;

import com.simibubi.create.content.kinetics.base.ShaftRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.tui.aerocomputers.block.entity.DigitalTransmissionBlockEntity;
import net.tui.aerocomputers.block.entity.SensorBlockEntity;

public class DigitalTransmissionRenderer extends ShaftRenderer<DigitalTransmissionBlockEntity> {
    public DigitalTransmissionRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }
}
