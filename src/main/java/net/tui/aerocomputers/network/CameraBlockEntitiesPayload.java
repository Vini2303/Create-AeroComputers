package net.tui.aerocomputers.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.tui.aerocomputers.AeroComputers;

import java.util.List;

/** Update tags of block entities near one camera (kinetic speed, signs, ...). Only changed ones are sent. */
public record CameraBlockEntitiesPayload(BlockPos cam, List<Entry> entries) implements CustomPacketPayload {

    public record Entry(BlockPos pos, CompoundTag tag) {}

    public static final Type<CameraBlockEntitiesPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AeroComputers.MOD_ID, "camera_block_entities"));

    private static final StreamCodec<ByteBuf, Entry> ENTRY_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, Entry::pos,
            ByteBufCodecs.COMPOUND_TAG, Entry::tag,
            Entry::new);

    private static final StreamCodec<ByteBuf, List<Entry>> ENTRY_LIST =
            ENTRY_CODEC.apply(ByteBufCodecs.list());

    public static final StreamCodec<ByteBuf, CameraBlockEntitiesPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, CameraBlockEntitiesPayload::cam,
            ENTRY_LIST, CameraBlockEntitiesPayload::entries,
            CameraBlockEntitiesPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}