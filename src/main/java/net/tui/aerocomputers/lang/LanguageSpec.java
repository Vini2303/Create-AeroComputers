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
 * Single source of truth for the editor (highlighting + autocomplete + signature help).
 * The language is real Python (GraalPy), so this mirrors what the runtime sandbox allows.
 *
 * BUILTIN_FUNCTIONS / NATIVE_CLASSES are keyed by the QUALIFIED name ("math.sqrt", "hud.Label").
 * Global functions (print, len, ...) have no prefix.
 * Must stay free of any Minecraft classes.
 */
public final class LanguageSpec {
    private LanguageSpec() {}

    /** purple in Dark+ */
    public static final Set<String> CONTROL_KEYWORDS = Set.of(
            "if", "elif", "else", "while", "for", "return", "break", "continue", "pass",
            "try", "except", "finally", "raise", "import", "from", "as", "assert", "del",
            "with", "async", "await", "yield"
    );

    /** blue in Dark+ */
    public static final Set<String> DECLARATION_KEYWORDS = Set.of(
            "def", "class", "lambda", "global", "nonlocal",
            "and", "or", "not", "in", "is",
            "True", "False", "None"
    );

    /** Highlighted like Python, but the runtime rejects them -> never suggested. */
    public static final Set<String> UNSUPPORTED_KEYWORDS = Set.of(
            "with", "async", "await", "yield"
    );

    /** Statements that open an indented block when the line ends with ':' */
    public static final Set<String> BLOCK_STARTERS = Set.of(
            "if", "elif", "else", "while", "for", "try", "except", "finally", "with", "def", "class"
    );

    /** After these statements the next line is outdented one level. */
    public static final Set<String> DEDENT_AFTER = Set.of(
            "return", "pass", "break", "continue", "raise"
    );

    /** Dedent automatically when the line starts with one of these and ':' is typed. */
    public static final Set<String> DEDENT_ON_COLON = Set.of(
            "else", "elif", "except", "finally"
    );

    /** Highlighted like classes (int(...), str(...)) */
    public static final Set<String> BUILTIN_TYPES = Set.of(
            "bool", "dict", "enumerate", "float", "int", "list", "range", "set", "str", "tuple", "zip"
    );

    /** functions without a prefix (the names the sandbox exposes as builtins) */
    public static final Map<String, List<String>> GLOBAL_FUNCTIONS = Map.ofEntries(
            Map.entry("print", List.of("value")),
            Map.entry("abs", List.of("x")),
            Map.entry("all", List.of("iterable")),
            Map.entry("any", List.of("iterable")),
            Map.entry("bool", List.of("x")),
            Map.entry("dict", List.of()),
            Map.entry("enumerate", List.of("iterable")),
            Map.entry("float", List.of("x")),
            Map.entry("int", List.of("x")),
            Map.entry("isinstance", List.of("obj", "cls")),
            Map.entry("len", List.of("obj")),
            Map.entry("list", List.of("iterable")),
            Map.entry("max", List.of("a", "b")),
            Map.entry("min", List.of("a", "b")),
            Map.entry("range", List.of("stop")),
            Map.entry("round", List.of("number", "ndigits")),
            Map.entry("set", List.of("iterable")),
            Map.entry("sorted", List.of("iterable")),
            Map.entry("str", List.of("object")),
            Map.entry("sum", List.of("iterable")),
            Map.entry("tuple", List.of("iterable")),
            Map.entry("zip", List.of("a", "b"))
    );

    /** module -> function name -> parameter names */
    public static final Map<String, Map<String, List<String>>> FUNCTION_MODULES = Map.of(
            // the REAL python math module: radians, needs "import math"
            "math", Map.ofEntries(
                    Map.entry("sqrt", List.of("x")),
                    Map.entry("sin", List.of("x")),
                    Map.entry("cos", List.of("x")),
                    Map.entry("tan", List.of("x")),
                    Map.entry("asin", List.of("x")),
                    Map.entry("acos", List.of("x")),
                    Map.entry("atan", List.of("x")),
                    Map.entry("atan2", List.of("y", "x")),
                    Map.entry("floor", List.of("x")),
                    Map.entry("ceil", List.of("x")),
                    Map.entry("fabs", List.of("x")),
                    Map.entry("pow", List.of("x", "y")),
                    Map.entry("exp", List.of("x")),
                    Map.entry("log", List.of("x")),
                    Map.entry("hypot", List.of("x", "y")),
                    Map.entry("radians", List.of("x")),
                    Map.entry("degrees", List.of("x")),
                    Map.entry("copysign", List.of("x", "y"))
            ),
            "aero", Map.of(
                    "getPos", List.of(),
                    "getRot", List.of(),
                    "getVel", List.of(),
                    "radar", List.of(),
                    "send", List.of("name_id", "message"),
                    "read", List.of(),
                    "clear", List.of()
            ),
            "bigc", Map.of(
                    "aimangel", List.of("pitch", "yaw"),
                    "fire", List.of("rate"),
                    "aimpos", List.of("pos")
            ),
            "slrw", Map.of(
                    "firechin", List.of("fire"),
                    "aimchinangel", List.of("pitch", "yaw"),
                    "aimchinpos", List.of("pos")
            )
    );

    /** module -> class name -> constructor parameter names */
    public static final Map<String, Map<String, List<String>>> CLASS_MODULES = Map.of(
            "hud", Map.of(
                    "Label", List.of("x", "y", "text", "color", "font_size"),
                    "Line", List.of("x1", "y1", "x2", "y2", "width", "color"),
                    "Rect", List.of("x", "y", "w", "h", "border_width", "center_color", "border_color", "render_center", "render_border"),
                    "Poly", List.of("xs", "ys", "border_width", "border_color"),
                    "Cam", List.of("x", "y", "width", "height")
            )
    );

    /** module -> non-callable members (math.pi) */
    public static final Map<String, List<String>> MODULE_CONSTANTS = Map.of(
            "math", List.of("pi", "e", "tau", "inf")
    );

    /** modules that need "import x" (the runtime only allows these) */
    public static final Set<String> IMPORTABLE_MODULES = Set.of("math");

    /** qualified name ("math.sqrt") or global name ("print") -> parameter names */
    public static final Map<String, List<String>> BUILTIN_FUNCTIONS;

    /** qualified class name ("hud.Label") -> constructor parameter names */
    public static final Map<String, List<String>> NATIVE_CLASSES;

    /** all module names */
    public static final Set<String> MODULES;

    /** modules that exist without an import (aero, hud, ...) */
    public static final Set<String> PRELOADED_MODULES;

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
        modules.addAll(MODULE_CONSTANTS.keySet());
        MODULES = Collections.unmodifiableSet(modules);

        Set<String> preloaded = new TreeSet<>(modules);
        preloaded.removeAll(IMPORTABLE_MODULES);
        PRELOADED_MODULES = Collections.unmodifiableSet(preloaded);
    }

    // Sorted lists so autocomplete order is stable (Set.of / Map.of iteration order is not).
    public static final List<String> GLOBAL_FUNCTION_NAMES = sorted(GLOBAL_FUNCTIONS.keySet());
    public static final List<String> MODULE_NAMES = sorted(MODULES);
    public static final List<String> KEYWORD_NAMES = keywordNames();

    public static boolean isKeyword(String s) {
        return CONTROL_KEYWORDS.contains(s) || DECLARATION_KEYWORDS.contains(s);
    }

    /** Python builtins and math are positional-only; named-argument suggestions make no sense there. */
    public static boolean isPositionalOnly(String qualifiedName) {
        return GLOBAL_FUNCTIONS.containsKey(qualifiedName) || qualifiedName.startsWith("math.");
    }

    /** function, class and constant names inside a module, sorted */
    public static List<String> moduleMembers(String module) {
        Set<String> members = new TreeSet<>();

        Map<String, List<String>> fns = FUNCTION_MODULES.get(module);
        if (fns != null) members.addAll(fns.keySet());

        Map<String, List<String>> cls = CLASS_MODULES.get(module);
        if (cls != null) members.addAll(cls.keySet());

        List<String> consts = MODULE_CONSTANTS.get(module);
        if (consts != null) members.addAll(consts);

        return List.copyOf(members);
    }

    /** fields of a native object (hud.Label ...): constructor parameters + remove() */
    public static List<String> nativeClassFields(String qualifiedClass) {
        List<String> params = NATIVE_CLASSES.get(qualifiedClass);
        List<String> out = new ArrayList<>(params == null ? List.of() : params);
        out.add("remove");
        return out;
    }

    private static List<String> keywordNames() {
        List<String> l = new ArrayList<>();
        for (String k : CONTROL_KEYWORDS) if (!UNSUPPORTED_KEYWORDS.contains(k)) l.add(k);
        for (String k : DECLARATION_KEYWORDS) if (!UNSUPPORTED_KEYWORDS.contains(k)) l.add(k);
        Collections.sort(l);
        return List.copyOf(l);
    }

    private static List<String> sorted(Collection<String> c) {
        List<String> l = new ArrayList<>(c);
        Collections.sort(l);
        return List.copyOf(l);
    }
}