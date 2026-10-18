package net.tui.aerocomputers.lang;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Single source of truth for the board computer language.
 * Used by the interpreter AND the editor (highlighting + autocomplete).
 *
 * Built-ins live in modules: math.sqrt(4), ship.getPos(), hud.Label(...).
 * BUILTIN_FUNCTIONS / NATIVE_CLASSES are keyed by the QUALIFIED name ("math.sqrt").
 * Global functions (print) have no prefix.
 */
public final class LanguageSpec {
    private LanguageSpec() {}

    public static final Set<String> CONTROL_KEYWORDS = Set.of(
            "if", "endif",
            "while", "endwhile",
            "for", "endfor",
            "return"
    );

    public static final Set<String> DECLARATION_KEYWORDS = Set.of(
            "def", "enddef",
            "class", "endclass",
            "and", "or", "not", "in",
            "true", "false"
    );

    /** functions without a prefix */
    public static final Map<String, List<String>> GLOBAL_FUNCTIONS = Map.of(
            "print", List.of("text")
    );

    /** module -> function name -> parameter names */
    public static final Map<String, Map<String, List<String>>> FUNCTION_MODULES = Map.of(
            "math", Map.ofEntries(
                    Map.entry("sqrt", List.of("value")),
                    Map.entry("sin", List.of("degrees")),
                    Map.entry("cos", List.of("degrees")),
                    Map.entry("asin", List.of("value")),
                    Map.entry("acos", List.of("value")),
                    Map.entry("abs", List.of("value")),
                    Map.entry("round", List.of("value")),
                    Map.entry("ceil", List.of("value")),
                    Map.entry("floor", List.of("value")),
                    Map.entry("min", List.of("a", "b")),
                    Map.entry("max", List.of("a", "b"))
            ),
            "aero", Map.of(
                    "getPos", List.of(),
                    "getRot", List.of(),
                    "getVel", List.of(),
                    "radar", List.of()
            )
    );

    /** module -> class name -> constructor parameter names */
    public static final Map<String, Map<String, List<String>>> CLASS_MODULES = Map.of(
            "hud", Map.of(
                    "Label", List.of("x", "y", "text", "color", "font_size"),
                    "Line", List.of("x1", "y1", "x2", "y2", "width", "color"),
                    "Rect", List.of("x", "y", "w", "h", "border_width", "center_color", "border_color", "render_center", "render_border"),
                    "Poly", List.of("xs", "ys", "border_width", "border_color")
            )
    );

    /** qualified name ("math.sqrt") or global name ("print") -> parameter names */
    public static final Map<String, List<String>> BUILTIN_FUNCTIONS;

    /** qualified class name ("hud.Label") -> constructor parameter names */
    public static final Map<String, List<String>> NATIVE_CLASSES;

    /** all module names */
    public static final Set<String> MODULES;

    static {
        Map<String, List<String>> functions = new LinkedHashMap<>(GLOBAL_FUNCTIONS);
        FUNCTION_MODULES.forEach((module, fns) ->
                fns.forEach((name, params) -> functions.put(module + "." + name, params)));
        BUILTIN_FUNCTIONS = Collections.unmodifiableMap(functions);

        Map<String, List<String>> classes = new LinkedHashMap<>();
        CLASS_MODULES.forEach((module, cls) ->
                cls.forEach((name, params) -> classes.put(module + "." + name, params)));
        NATIVE_CLASSES = Collections.unmodifiableMap(classes);

        Set<String> modules = new TreeSet<>(FUNCTION_MODULES.keySet());
        modules.addAll(CLASS_MODULES.keySet());
        MODULES = Collections.unmodifiableSet(modules);
    }

    // Sorted lists so autocomplete order is stable (Set.of / Map.of iteration order is not).
    public static final List<String> GLOBAL_FUNCTION_NAMES = sorted(GLOBAL_FUNCTIONS.keySet());
    public static final List<String> MODULE_NAMES = sorted(MODULES);
    public static final List<String> KEYWORD_NAMES = sortedUnion(CONTROL_KEYWORDS, DECLARATION_KEYWORDS);

    /** function and class names inside a module, sorted */
    public static List<String> moduleMembers(String module) {
        Set<String> members = new TreeSet<>();

        Map<String, List<String>> fns = FUNCTION_MODULES.get(module);
        if (fns != null) members.addAll(fns.keySet());

        Map<String, List<String>> cls = CLASS_MODULES.get(module);
        if (cls != null) members.addAll(cls.keySet());

        return List.copyOf(members);
    }

    private static List<String> sorted(Collection<String> c) {
        List<String> l = new ArrayList<>(c);
        Collections.sort(l);
        return List.copyOf(l);
    }

    private static List<String> sortedUnion(Collection<String> a, Collection<String> b) {
        List<String> l = new ArrayList<>(a);
        l.addAll(b);
        Collections.sort(l);
        return List.copyOf(l);
    }
}