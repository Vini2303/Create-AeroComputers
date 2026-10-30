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
 *
 * Which libraries may actually be imported is decided by the server config; this class only
 * holds a CATALOG of what is known about the standard library modules (members + parameters).
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

    /** modules that exist without an import (injected by the mod) */
    public static final Set<String> PRELOADED_MODULES = Set.of("aero", "hud", "bigc", "slrw");

    /** module -> function name -> parameter names  ("name:p1,p2") */
    public static final Map<String, Map<String, List<String>>> FUNCTION_MODULES = Map.ofEntries(
            // ---- mod API ----
            Map.entry("aero", fns("getPos:sensor_freq", "getRot:sensor_freq", "getVel:sensor_freq", "radar:sensor_freq", "send:name_id,message", "getPower:link_freq", "setPower:link_freq,power", "setSpeed:freq,speed", "read", "clear")),
            Map.entry("bigc", fns("aimangel:pitch,yaw", "fire:rate", "aimpos:pos")),
            Map.entry("slrw", fns("firechin:fire", "aimchinangel:pitch,yaw", "aimchinpos:pos")),

            // ---- standard library (real Python semantics; needs "import x") ----
            Map.entry("math", fns("sqrt:x", "sin:x", "cos:x", "tan:x", "asin:x", "acos:x", "atan:x", "atan2:y,x",
                    "sinh:x", "cosh:x", "tanh:x", "floor:x", "ceil:x", "trunc:x", "fabs:x", "fmod:x,y", "pow:x,y",
                    "exp:x", "log:x,base", "log10:x", "log2:x", "hypot:x,y", "radians:x", "degrees:x",
                    "copysign:x,y", "isnan:x", "isinf:x", "isfinite:x", "isclose:a,b", "factorial:n", "gcd:a,b",
                    "dist:p,q", "prod:iterable")),
            Map.entry("cmath", fns("sqrt:x", "exp:x", "log:x", "sin:x", "cos:x", "phase:x", "polar:x", "rect:r,phi")),
            Map.entry("random", fns("random", "randint:a,b", "randrange:start,stop", "choice:seq",
                    "choices:population,k", "shuffle:x", "sample:population,k", "uniform:a,b", "gauss:mu,sigma",
                    "seed:a", "getrandbits:k", "triangular:low,high")),
            Map.entry("time", fns("time", "time_ns", "monotonic", "perf_counter", "process_time", "sleep:seconds")),
            Map.entry("statistics", fns("mean:data", "fmean:data", "median:data", "median_low:data",
                    "median_high:data", "mode:data", "stdev:data", "variance:data", "pstdev:data", "pvariance:data")),
            Map.entry("itertools", fns("count:start,step", "cycle:iterable", "repeat:object,times", "chain:a,b",
                    "accumulate:iterable", "combinations:iterable,r", "permutations:iterable,r", "product:a,b",
                    "islice:iterable,stop", "zip_longest:a,b", "groupby:iterable", "starmap:function,iterable",
                    "takewhile:predicate,iterable", "dropwhile:predicate,iterable")),
            Map.entry("functools", fns("reduce:function,iterable", "partial:func", "lru_cache:maxsize",
                    "wraps:wrapped", "cmp_to_key:mycmp")),
            Map.entry("collections", fns("namedtuple:typename,field_names")),
            Map.entry("string", fns("capwords:s")),
            Map.entry("json", fns("dumps:obj", "loads:s", "dump:obj,fp", "load:fp")),
            Map.entry("re", fns("compile:pattern", "match:pattern,string", "search:pattern,string",
                    "fullmatch:pattern,string", "findall:pattern,string", "finditer:pattern,string",
                    "sub:pattern,repl,string", "split:pattern,string", "escape:pattern")),
            Map.entry("operator", fns("add:a,b", "sub:a,b", "mul:a,b", "truediv:a,b", "floordiv:a,b", "mod:a,b",
                    "neg:obj", "eq:a,b", "ne:a,b", "lt:a,b", "le:a,b", "gt:a,b", "ge:a,b", "not_:obj",
                    "itemgetter:item", "attrgetter:attr")),
            Map.entry("heapq", fns("heappush:heap,item", "heappop:heap", "heapify:x", "heappushpop:heap,item",
                    "heapreplace:heap,item", "nlargest:n,iterable", "nsmallest:n,iterable")),
            Map.entry("bisect", fns("bisect:a,x", "bisect_left:a,x", "bisect_right:a,x", "insort:a,x",
                    "insort_left:a,x", "insort_right:a,x")),
            Map.entry("copy", fns("copy:x", "deepcopy:x"))
    );

    /** module -> class name -> constructor parameter names */
    public static final Map<String, Map<String, List<String>>> CLASS_MODULES = Map.ofEntries(
            Map.entry("hud", fns("Label:hud_freq,x,y,text,color,font_size",
                    "Line:hud_freq,x1,y1,x2,y2,width,color",
                    "Rect:hud_freq,x,y,w,h,border_width,center_color,border_color,render_center,render_border",
                    "Poly:hud_freq,xs,ys,border_width,border_color",
                    "Cam:hud_freq,x,y,width,height")),
            Map.entry("collections", fns("deque:iterable,maxlen", "Counter:iterable", "OrderedDict",
                    "defaultdict:default_factory")),
            Map.entry("random", fns("Random:seed")),
            Map.entry("datetime", fns("datetime:year,month,day", "date:year,month,day", "timedelta:days,seconds")),
            Map.entry("fractions", fns("Fraction:numerator,denominator")),
            Map.entry("decimal", fns("Decimal:value"))
    );

    /** module -> non-callable members (math.pi) */
    public static final Map<String, List<String>> MODULE_CONSTANTS = Map.ofEntries(
            Map.entry("math", List.of("pi", "e", "tau", "inf", "nan")),
            Map.entry("cmath", List.of("pi", "e", "tau", "inf", "nan")),
            Map.entry("string", List.of("ascii_letters", "ascii_lowercase", "ascii_uppercase", "digits",
                    "hexdigits", "octdigits", "punctuation", "printable", "whitespace")),
            Map.entry("re", List.of("IGNORECASE", "MULTILINE", "DOTALL", "VERBOSE", "ASCII"))
    );

    /** every library the catalog knows (what the config MAY enable); the config decides what is allowed */
    public static final Set<String> IMPORTABLE_MODULES;

    /** qualified name ("math.sqrt") or global name ("print") -> parameter names */
    public static final Map<String, List<String>> BUILTIN_FUNCTIONS;

    /** qualified class name ("hud.Label", "collections.deque") -> constructor parameter names */
    public static final Map<String, List<String>> NATIVE_CLASSES;

    /** all module names the catalog knows */
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
        modules.addAll(MODULE_CONSTANTS.keySet());
        MODULES = Collections.unmodifiableSet(modules);

        Set<String> importable = new TreeSet<>(modules);
        importable.removeAll(PRELOADED_MODULES);
        IMPORTABLE_MODULES = Collections.unmodifiableSet(importable);
    }

    // Sorted lists so autocomplete order is stable (Set.of / Map.of iteration order is not).
    public static final List<String> GLOBAL_FUNCTION_NAMES = sorted(GLOBAL_FUNCTIONS.keySet());
    public static final List<String> MODULE_NAMES = sorted(MODULES);
    public static final List<String> KEYWORD_NAMES = keywordNames();

    public static boolean isKeyword(String s) {
        return CONTROL_KEYWORDS.contains(s) || DECLARATION_KEYWORDS.contains(s);
    }

    /** Python builtins and the standard library are positional-only for our purposes. */
    public static boolean isPositionalOnly(String qualifiedName) {
        return GLOBAL_FUNCTIONS.containsKey(qualifiedName)
                || (qualifiedName.contains(".") && !qualifiedName.startsWith("hud.")
                && !PRELOADED_MODULES.contains(qualifiedName.substring(0, qualifiedName.indexOf('.'))));
    }

    /** function, class and constant names inside a module, sorted (empty for modules the catalog doesn't know) */
    public static List<String> moduleMembers(String module) {
        if (LocalModules.has(module) && !FUNCTION_MODULES.containsKey(module)) return LocalModules.members(module);

        Set<String> members = new TreeSet<>();

        Map<String, List<String>> fns = FUNCTION_MODULES.get(module);
        if (fns != null) members.addAll(fns.keySet());

        Map<String, List<String>> cls = CLASS_MODULES.get(module);
        if (cls != null) members.addAll(cls.keySet());

        List<String> consts = MODULE_CONSTANTS.get(module);
        if (consts != null) members.addAll(consts);

        return List.copyOf(members);
    }

    public static List<String> importableNames() {
        Set<String> s = new TreeSet<>(IMPORTABLE_MODULES);
        s.addAll(LocalModules.names());
        return List.copyOf(s);
    }

    /** fields of a native object (hud.Label ...): constructor parameters + remove() */
    public static List<String> nativeClassFields(String qualifiedClass) {
        List<String> params = NATIVE_CLASSES.get(qualifiedClass);
        List<String> out = new ArrayList<>(params == null ? List.of() : params);
        out.add("remove");
        return out;
    }

    /** "name:p1,p2" -> name -> [p1, p2]; "name" -> no parameters */
    private static Map<String, List<String>> fns(String... specs) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (String spec : specs) {
            int c = spec.indexOf(':');
            String name = c < 0 ? spec : spec.substring(0, c);
            String params = c < 0 ? "" : spec.substring(c + 1);
            m.put(name, params.isEmpty() ? List.of() : List.of(params.split(",")));
        }
        return Collections.unmodifiableMap(m);
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