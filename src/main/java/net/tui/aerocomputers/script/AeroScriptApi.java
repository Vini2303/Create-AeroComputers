package net.tui.aerocomputers.script;

import org.graalvm.polyglot.HostAccess;
import net.tui.aerocomputers.block.entity.BoardComputerBlockEntity;

public final class AeroScriptApi {

    private final BoardComputerBlockEntity computer;

    public AeroScriptApi(BoardComputerBlockEntity computer) {
        this.computer = computer;
    }

    @HostAccess.Export
    public Object getPos() {
        return computer.scriptGetPos();
    }

    @HostAccess.Export
    public Object getRot() {
        return computer.scriptGetRot();
    }

    @HostAccess.Export
    public Object getVel() {
        return computer.scriptGetVel();
    }

    @HostAccess.Export
    public Object radar() {
        return computer.scriptRadar();
    }
}