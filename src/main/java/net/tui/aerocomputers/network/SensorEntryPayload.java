package net.tui.aerocomputers.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.block.entity.HudDisplayBlockEntity;
import net.tui.aerocomputers.block.entity.SensorBlockEntity;

public record SensorEntryPayload(
        BlockPos pos,
        String freq_one,
        String freq_two
) implements CustomPacketPayload {

    public static final Type<SensorEntryPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AeroComputers.MOD_ID, "sensor_entry_payload"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SensorEntryPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    SensorEntryPayload::pos,
                    ByteBufCodecs.stringUtf8(32767),
                    SensorEntryPayload::freq_one,
                    ByteBufCodecs.stringUtf8(32767),
                    SensorEntryPayload::freq_two,
                    SensorEntryPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(
            SensorEntryPayload payload,
            IPayloadContext context
    ) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }

            BlockEntity blockEntity =
                    player.level().getBlockEntity(payload.pos());

            if (!(blockEntity instanceof SensorBlockEntity sensor)) {
                return;
            }

            // Make sure the player is actually close enough
            if (player.distanceToSqr(
                    payload.pos().getX() + 0.5,
                    payload.pos().getY() + 0.5,
                    payload.pos().getZ() + 0.5
            ) > 64) {
                return;
            }

            sensor.setFreq(payload.freq_one(), payload.freq_two());
            sensor.setChanged();
        });
    }
}