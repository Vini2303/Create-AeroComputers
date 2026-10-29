package net.tui.aerocomputers.script;

import net.tui.aerocomputers.block.entity.BoardComputerBlockEntity;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit, narrow Python-facing API. No Java classes or arbitrary host objects are exposed. */
public final class AeroScriptApi {
    private final BoardComputerBlockEntity computer;
    private final ProxyObject proxy;

    public AeroScriptApi(BoardComputerBlockEntity computer) {
        this.computer = computer;
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("getPos", call(args -> computer.scriptGetPos(stringArrayArg(args, 0))));
        members.put("getRot", call(args -> computer.scriptGetRot(stringArrayArg(args, 0))));
        members.put("getVel", call(args -> computer.scriptGetVel(stringArrayArg(args, 0))));
        members.put("radar", call(args -> computer.scriptRadar(stringArrayArg(args, 0))));
        members.put("send", call(args -> computer.scriptSend(stringArg(args, 0), stringArg(args, 1))));
        members.put("read", call(args -> computer.scriptRead()));
        members.put("clear", call(args -> computer.scriptClear()));
        Map<String, Object> bigc = new LinkedHashMap<>();
        bigc.put("aimangel", call(args -> computer.scriptCommand("bigc.aimangel", args)));
        bigc.put("fire", call(args -> computer.scriptCommand("bigc.fire", args)));
        bigc.put("aimpos", call(args -> computer.scriptCommand("bigc.aimpos", args)));
        members.put("bigc", ProxyObject.fromMap(bigc));

        Map<String, Object> slrw = new LinkedHashMap<>();
        slrw.put("firechin", call(args -> computer.scriptCommand("slrw.firechin", args)));
        slrw.put("aimchinangel", call(args -> computer.scriptCommand("slrw.aimchinangel", args)));
        slrw.put("aimchinpos", call(args -> computer.scriptCommand("slrw.aimchinpos", args)));
        members.put("slrw", ProxyObject.fromMap(slrw));
        proxy = ProxyObject.fromMap(members);
    }

    public Object asProxy() {
        return proxy;
    }

    private interface Invocation {
        Object run(List<Object> args);
    }

    private static ProxyExecutable call(Invocation invocation) {
        return values -> {
            List<Object> args = new ArrayList<>();
            for (Value value : values) args.add(convert(value));
            return invocation.run(args);
        };
    }

    private static String stringArg(List<Object> args, int index) {
        return index < args.size() && args.get(index) != null ? args.get(index).toString() : "";
    }

    private static Object convert(Value value) {
        if (value == null || value.isNull()) return null;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isString()) return value.asString();
        if (value.fitsInInt()) return value.asInt();
        if (value.fitsInLong()) return value.asLong();
        if (value.fitsInDouble()) return value.asDouble();
        if (value.hasArrayElements()) {
            List<Object> list = new ArrayList<>();
            for (long i = 0; i < value.getArraySize(); i++) list.add(convert(value.getArrayElement(i)));
            return list;
        }
        return value.toString();
    }

    private static String[] stringArrayArg(List<Object> args, int index) {
        if (index >= args.size() || !(args.get(index) instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "Expected one argument: [\"freq_one\", \"freq_two\"]"
            );
        }

        if (list.size() != 2 ||
                !(list.get(0) instanceof String) ||
                !(list.get(1) instanceof String)) {
            throw new IllegalArgumentException(
                    "Expected a list containing exactly two strings"
            );
        }

        return new String[] {
                (String) list.get(0),
                (String) list.get(1)
        };
    }
}
