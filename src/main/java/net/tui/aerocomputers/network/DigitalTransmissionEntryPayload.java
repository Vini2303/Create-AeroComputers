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
import net.tui.aerocomputers.block.entity.DigitalTransmissionBlockEntity;
import net.tui.aerocomputers.block.entity.SensorBlockEntity;

public record DigitalTransmissionEntryPayload(
        BlockPos pos,
        String freq_one,
        String freq_two
) implements CustomPacketPayload {

    public static final Type<DigitalTransmissionEntryPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AeroComputers.MOD_ID, "digital_transmission_entry_payload"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DigitalTransmissionEntryPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    DigitalTransmissionEntryPayload::pos,
                    ByteBufCodecs.stringUtf8(32767),
                    DigitalTransmissionEntryPayload::freq_one,
                    ByteBufCodecs.stringUtf8(32767),
                    DigitalTransmissionEntryPayload::freq_two,
                    DigitalTransmissionEntryPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(
            DigitalTransmissionEntryPayload payload,
            IPayloadContext context
    ) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }

            BlockEntity blockEntity =
                    player.level().getBlockEntity(payload.pos());

            if (!(blockEntity instanceof DigitalTransmissionBlockEntity digitalTransmissionBlockEntity)) {
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

            digitalTransmissionBlockEntity.setFreq(payload.freq_one(), payload.freq_two());
            digitalTransmissionBlockEntity.setChanged();
        });
    }
}