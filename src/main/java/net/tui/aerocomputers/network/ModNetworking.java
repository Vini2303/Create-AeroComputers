package net.tui.aerocomputers.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.client.CameraFeed;

public class ModNetworking {


    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {

        var registrar = event.registrar(
                AeroComputers.MOD_ID
        );


        registrar.playToServer(
                BoardComputerEditPayload.TYPE,
                BoardComputerEditPayload.STREAM_CODEC,
                BoardComputerEditPayload::handle
        );

        registrar.playToServer(
                BoardComputerControlPayload.TYPE,
                BoardComputerControlPayload.STREAM_CODEC,
                BoardComputerControlPayload::handle
        );

        registrar.playToServer(
                BoardComputerChannelPayload.TYPE,
                BoardComputerChannelPayload.STREAM_CODEC,
                BoardComputerChannelPayload::handle
        );

        registrar.playToServer(
                BoardComputerVariablePayload.TYPE,
                BoardComputerVariablePayload.STREAM_CODEC,
                BoardComputerVariablePayload::handle
        );

        registrar.playToServer(
                HudEntryPayload.TYPE,
                HudEntryPayload.STREAM_CODEC,
                HudEntryPayload::handle
        );

        registrar.playToServer(
                HudDisplayEntryPayload.TYPE,
                HudDisplayEntryPayload.STREAM_CODEC,
                HudDisplayEntryPayload::handle
        );

        registrar.playToServer(
                WeaponLinkerEntryPayload.TYPE,
                WeaponLinkerEntryPayload.STREAM_CODEC,
                WeaponLinkerEntryPayload::handle
        );

        registrar.playToClient(
                CameraChunkPayload.TYPE,
                CameraChunkPayload.STREAM_CODEC,
                ClientPayloads::handleCameraChunk
        );

        registrar.playToClient(
                CameraEntitiesPayload.TYPE,
                CameraEntitiesPayload.STREAM_CODEC,
                ClientPayloads::handleCameraEntities
        );
        registrar.playToClient(
                CameraBlocksPayload.TYPE,
                CameraBlocksPayload.STREAM_CODEC,
                ClientPayloads::handleCameraBlocks
        );

    }

    public final class ClientPayloads {
        public static void handleCameraChunk(CameraChunkPayload p, IPayloadContext ctx) {
            ctx.enqueueWork(() -> CameraFeed.acceptChunk(p.packet()));
        }

        public static void handleCameraEntities(CameraEntitiesPayload p, IPayloadContext ctx) {
            ctx.enqueueWork(() -> CameraFeed.acceptEntities(p.entities()));
        }
        public static void handleCameraBlocks(CameraBlocksPayload p, IPayloadContext ctx) {
            ctx.enqueueWork(() -> CameraFeed.acceptBlocks(p.changes()));
        }
    }
}