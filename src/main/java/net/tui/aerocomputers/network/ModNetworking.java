package net.tui.aerocomputers.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.tui.aerocomputers.AeroComputers;

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

    }
}