package net.tui.aerocomputers.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tui.aerocomputers.AeroComputers;

public record HudEntryPayload(
        InteractionHand hand,
        String freq_one,
        String freq_two
) implements CustomPacketPayload {

    public static final String NBT_KEY = "HudEntry";
    public static final String FREQ_ONE_KEY = "FreqOne";
    public static final String FREQ_TWO_KEY = "FreqTwo";

    public static final Type<HudEntryPayload> TYPE =
            new Type<>(
                    ResourceLocation.fromNamespaceAndPath(
                            AeroComputers.MOD_ID,
                            "hud_entry"
                    )
            );

    public static final StreamCodec<FriendlyByteBuf, HudEntryPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.idMapper(
                            i -> InteractionHand.values()[i],
                            InteractionHand::ordinal
                    ),
                    HudEntryPayload::hand,
                    ByteBufCodecs.STRING_UTF8,
                    HudEntryPayload::freq_one,
                    ByteBufCodecs.STRING_UTF8,
                    HudEntryPayload::freq_two,
                    HudEntryPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            ItemStack stack = player.getItemInHand(hand);

            if (stack.isEmpty()) {
                return;
            }

            CustomData data = stack.getOrDefault(
                    DataComponents.CUSTOM_DATA,
                    CustomData.EMPTY
            );

            CompoundTag tag = data.copyTag();

            // Preserve the HUD text and every other existing field.
            tag.putString(FREQ_ONE_KEY, freq_one);
            tag.putString(FREQ_TWO_KEY, freq_two);

            stack.set(
                    DataComponents.CUSTOM_DATA,
                    CustomData.of(tag)
            );
        });
    }
}