package net.tui.aerocomputers.script;

import net.tui.aerocomputers.block.entity.BoardComputerBlockEntity;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.*;

public final class SlrwApi {

    private final ProxyObject proxy;

    public SlrwApi(BoardComputerBlockEntity computer) {
        Map<String, Object> members = new LinkedHashMap<>();

        members.put("fire", call(args ->
                computer.scriptChinFire(stringArrayArg(args, 0),
                        args.size() > 1 ? args.get(1) : 0)));

        members.put("aimangle", call(args ->
                computer.scriptChinAimAngle(stringArrayArg(args, 0),
                        args.size() > 1 ? args.get(1) : 0,
                        args.size() > 2 ? args.get(2) : 0)));

        members.put("aimpos", call(args ->
                computer.scriptChinAimPos(stringArrayArg(args, 0),
                        args.size() > 1 ? args.get(1) : 0,
                        args.size() > 2 ? args.get(2) : 0,
                        args.size() > 3 ? args.get(3) : 0)));

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

    private static Object convert(Value value) {
        if (value == null || value.isNull()) return null;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isString()) return value.asString();
        if (value.fitsInInt()) return value.asInt();
        if (value.fitsInLong()) return value.asLong();
        if (value.fitsInDouble()) return value.asDouble();

        if (value.hasArrayElements()) {
            List<Object> list = new ArrayList<>();
            for (long i = 0; i < value.getArraySize(); i++)
                list.add(convert(value.getArrayElement(i)));
            return list;
        }

        return value.toString();
    }

    private static String[] stringArrayArg(List<Object> args, int index) {
        if (index >= args.size() || !(args.get(index) instanceof List<?> list)
                || list.size() != 2
                || !(list.get(0) instanceof String)
                || !(list.get(1) instanceof String)) {
            throw new IllegalArgumentException(
                    "Expected [\"freq_one\", \"freq_two\"]"
            );
        }

        return new String[]{
                (String) list.get(0),
                (String) list.get(1)
        };
    }
}