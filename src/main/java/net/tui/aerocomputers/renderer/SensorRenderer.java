package net.tui.aerocomputers.renderer;

import com.simibubi.create.content.kinetics.base.ShaftRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.tui.aerocomputers.block.entity.BoardComputerBlockEntity;
import net.tui.aerocomputers.block.entity.SensorBlockEntity;

public class SensorRenderer extends ShaftRenderer<SensorBlockEntity> {
    public SensorRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }
}
