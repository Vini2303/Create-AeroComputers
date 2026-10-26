package net.tui.aerocomputers.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.tui.aerocomputers.AeroComputers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Full snapshot of the entities near the camera, sent every server tick (20/s). */
public record CameraEntitiesPayload(List<Snap> entities) implements CustomPacketPayload {

    public record Snap(int id, UUID uuid, ResourceLocation type, String name,
                       double x, double y, double z,
                       float yRot, float xRot, float headYRot,
                       float bodyRot, float attackAnim, boolean offHand, int hurtTime, int deathTime,
                       List<SynchedEntityData.DataValue<?>> data) {

        static final StreamCodec<RegistryFriendlyByteBuf, Snap> CODEC = StreamCodec.of(Snap::write, Snap::read);

        private static void write(RegistryFriendlyByteBuf b, Snap s) {
            b.writeVarInt(s.id);
            b.writeUUID(s.uuid);
            b.writeResourceLocation(s.type);
            b.writeUtf(s.name);
            b.writeDouble(s.x);
            b.writeDouble(s.y);
            b.writeDouble(s.z);
            b.writeFloat(s.yRot);
            b.writeFloat(s.xRot);
            b.writeFloat(s.headYRot);
            b.writeFloat(s.bodyRot);
            b.writeFloat(s.attackAnim);
            b.writeBoolean(s.offHand);
            b.writeVarInt(s.hurtTime);
            b.writeVarInt(s.deathTime);
            for (SynchedEntityData.DataValue<?> v : s.data) v.write(b);
            b.writeByte(255);   // terminator, same as vanilla's entity data packet
        }

        private static Snap read(RegistryFriendlyByteBuf b) {
            int id = b.readVarInt();
            UUID uuid = b.readUUID();
            ResourceLocation type = b.readResourceLocation();
            String name = b.readUtf();
            double x = b.readDouble(), y = b.readDouble(), z = b.readDouble();
            float yRot = b.readFloat(), xRot = b.readFloat(), head = b.readFloat();
            float body = b.readFloat(), attack = b.readFloat();
            boolean off = b.readBoolean();
            int hurt = b.readVarInt(), death = b.readVarInt();
            List<SynchedEntityData.DataValue<?>> data = new ArrayList<>();
            int i;
            while ((i = b.readUnsignedByte()) != 255) data.add(SynchedEntityData.DataValue.read(b, i));
            return new Snap(id, uuid, type, name, x, y, z, yRot, xRot, head, body, attack, off, hurt, death, data);
        }
    }

    public static final Type<CameraEntitiesPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AeroComputers.MOD_ID, "camera_entities"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CameraEntitiesPayload> STREAM_CODEC =
            Snap.CODEC.apply(ByteBufCodecs.list()).map(CameraEntitiesPayload::new, CameraEntitiesPayload::entities);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}