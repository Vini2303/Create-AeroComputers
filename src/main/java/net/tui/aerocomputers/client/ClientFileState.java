package net.tui.aerocomputers.client;

import net.tui.aerocomputers.network.FileTreePayload;
import java.util.List;

public final class ClientFileState {
    public static List<FileTreePayload.Entry> entries = List.of();
    public static int treeVersion = 0;
    public static String openPath = "";
    public static String openContent = "";
    public static int contentVersion = 0;
    private ClientFileState() {}
}