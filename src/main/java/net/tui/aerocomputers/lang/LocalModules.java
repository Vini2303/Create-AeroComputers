package net.tui.aerocomputers.lang;

import java.util.*;

public final class LocalModules {
    private LocalModules() {}

    private static volatile Map<String, LocalModuleParser.ModuleInfo> modules = Map.of();

    /** moduleName ("lib") -> file source. Call whenever the sibling files change. */
    public static void update(Map<String, String> sources) {
        Map<String, LocalModuleParser.ModuleInfo> m = new TreeMap<>();
        sources.forEach((name, src) -> m.put(name, LocalModuleParser.parse(src)));
        modules = m;
    }

    public static boolean has(String module) { return modules.containsKey(module); }
    public static Set<String> names() { return modules.keySet(); }

    /** functions + classes + variables of a module */
    public static List<String> members(String module) {
        var info = modules.get(module);
        if (info == null) return List.of();
        Set<String> out = new TreeSet<>(info.functions().keySet());
        out.addAll(info.classes().keySet());
        out.addAll(info.variables());
        return List.copyOf(out);
    }

    /** "lib.add" or "lib.Vec" (constructor) -> parameter names, or null if unknown */
    public static List<String> params(String qualified) {
        int dot = qualified.indexOf('.');
        if (dot < 0) return null;
        var info = modules.get(qualified.substring(0, dot));
        if (info == null) return null;
        String name = qualified.substring(dot + 1);
        var fn = info.functions().get(name);
        if (fn != null) return fn;
        var cls = info.classes().get(name);
        return cls != null ? cls.ctorParams() : null;
    }

    public static boolean isClass(String module, String name) {
        var info = modules.get(module);
        return info != null && info.classes().containsKey(name);
    }

    /** methods + fields of "lib.Vec", for "obj." completion */
    public static List<String> classMembers(String module, String cls) {
        var info = modules.get(module);
        var c = info == null ? null : info.classes().get(cls);
        if (c == null) return List.of();
        Set<String> out = new TreeSet<>(c.methods());
        out.addAll(c.fields());
        return List.copyOf(out);
    }

    /** every method of every local class, for "x." where the type is unknown */
    public static Map<String, List<String>> allMethodsByClass() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        modules.forEach((mod, info) -> info.classes().forEach((name, c) -> {
            List<String> all = new ArrayList<>(c.methods());
            all.addAll(c.fields());
            out.put(mod + "." + name, all);
        }));
        return out;
    }

    public static List<String> classMethods(String module, String cls) {
        var info = modules.get(module);
        var c = info == null ? null : info.classes().get(cls);
        return c == null ? List.of() : c.methods();
    }

    public static List<String> classFields(String module, String cls) {
        var info = modules.get(module);
        var c = info == null ? null : info.classes().get(cls);
        return c == null ? List.of() : c.fields();
    }
}