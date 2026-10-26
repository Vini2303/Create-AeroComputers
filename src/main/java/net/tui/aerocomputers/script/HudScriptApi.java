package net.tui.aerocomputers.script;

import net.tui.aerocomputers.block.entity.BoardComputerBlockEntity;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Python-facing HUD facade; uses proxies so ali.text = value updates the live HUD object. */
public final class HudScriptApi {
    private final BoardComputerBlockEntity computer;
    private final ProxyObject root;

    public HudScriptApi(BoardComputerBlockEntity computer) {
        this.computer = computer;
        Map<String, Object> methods = new LinkedHashMap<>();
        methods.put("Label", creator("hud.Label"));
        methods.put("Line", creator("hud.Line"));
        methods.put("Poly", creator("hud.Poly"));
        methods.put("Rect", creator("hud.Rect"));
        methods.put("Cam", creator("hud.Cam"));
        root = ProxyObject.fromMap(methods);
    }

    public Object asProxy() {
        return root;
    }

    private ProxyExecutable creator(String type) {
        return values -> {
            List<Object> args = new ArrayList<>();
            for (Value value : values) args.add(convert(value));
            String id = computer.scriptCreateHudObject(type, args);
            return drawObject(id);
        };
    }

    private ProxyObject drawObject(String id) {
        return new ProxyObject() {
            @Override
            public Object getMember(String key) {
                if ("remove".equals(key)) {
                    return (ProxyExecutable) args -> {
                        computer.scriptRemoveHudObject(id);
                        return null;
                    };
                }
                return computer.scriptGetHudField(id, key);
            }

            @Override
            public Object getMemberKeys() {
                return new String[]{"x", "x1", "y", "y1", "x2", "y2", "text", "color",
                        "center_color", "border_color", "font_size", "width", "border_width",
                        "w", "h", "xs", "ys", "render_center", "render_border", "remove"};
            }

            @Override
            public boolean hasMember(String key) {
                return true;
            }

            @Override
            public void putMember(String key, Value value) {
                computer.scriptSetHudField(id, key, convert(value));
            }
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
            for (long i = 0; i < value.getArraySize(); i++) list.add(convert(value.getArrayElement(i)));
            return list;
        }
        return value.toString();
    }
}
