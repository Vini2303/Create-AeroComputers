package net.tui.aerocomputers.script;

import net.tui.aerocomputers.config.AeroComputersConfig;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A persistent, cooperative GraalPy runtime.
 *
 * Scripts are transformed into a generator. Each call to step() advances
 * execution through up to N Python statements (inside Python, in ONE host call),
 * allowing the Minecraft server tick to regain control between ticks.
 */
public final class PythonScriptRuntime implements AutoCloseable {

    /** One engine shared by every computer: compiled code and warmup are reused. */
    private static final Engine ENGINE = Engine.newBuilder()
            .option("engine.WarnInterpreterOnly", "false")
            .build();

    /** Safety net for code that is not stepped cooperatively (bodies of def, while True in functions...). */
    private static final long STATEMENT_LIMIT = 50_000L;

    private final Context context;
    private final Value startFn;
    private final Value stepFn;
    private final Value bindings;
    private boolean started;
    private boolean finished;
    private final Consumer<String> output;

    private static final String BOOTSTRAP = """
        def _aero_make_runtime():
            import ast as _aero_ast
            import builtins as _aero_builtins
            import time as _aero_time

            _aero_perf = _aero_time.perf_counter
            _aero_original_import = _aero_builtins.__import__
            _aero_exec = _aero_builtins.exec
            _aero_compile = _aero_builtins.compile
            _aero_next = _aero_builtins.next
            _aero_globals = _aero_builtins.globals
            _aero_StopIteration = _aero_builtins.StopIteration
            _aero_type = _aero_builtins.type
            _aero_hasattr = _aero_builtins.hasattr
            _aero_getattr = _aero_builtins.getattr
            _aero_callable = _aero_builtins.callable
            _aero_len = _aero_builtins.len
            _aero_range = _aero_builtins.range
            _aero_str = _aero_builtins.str
            _aero_list = _aero_builtins.list
            _aero_tuple = _aero_builtins.tuple
            _aero_dict = _aero_builtins.dict
            _aero_allowed_imports = set(_aero_allowed_imports_config.split(","))

            def _aero_safe_import(name, globals=None, locals=None, fromlist=(), level=0):
                root = name.split(".")[0]
                if level != 0 or root not in _aero_allowed_imports:
                    raise ImportError("Import not allowed: " + name)
                return _aero_original_import(name, globals, locals, fromlist, level)

            _aero_safe_builtins = {
                name: getattr(_aero_builtins, name)
                for name in (
                    "abs", "all", "any", "bool", "dict", "enumerate", "float",
                    "int", "isinstance", "len", "list", "max", "min", "range",
                    "round", "str", "sum", "tuple", "zip", "print", "Exception",
                    "ValueError", "TypeError", "IndexError", "KeyError", "RuntimeError",
                    "set", "sorted", "SyntaxError", "ImportError", "StopIteration",
                    "__build_class__", "object", "super", "property", "staticmethod", "classmethod",
                    "hasattr", "AttributeError", "NotImplementedError"
                )
            }
            _aero_safe_builtins["__import__"] = _aero_safe_import

            class __AeroStepTransformer(_aero_ast.NodeTransformer):
                def visit_FunctionDef(self, node):
                    # User functions are kept as normal functions. Their bodies
                    # aren't cooperatively stepped in this first implementation.
                    return node
                def visit_AsyncFunctionDef(self, node):
                    raise SyntaxError("async functions are not supported")
                def visit_ClassDef(self, node):
                    # The class body runs once at definition time (not stepped);
                    # its methods are normal functions.
                    return node
                def visit_Lambda(self, node):
                    return node
                def visit_Yield(self, node):
                    raise SyntaxError("yield is reserved by the runtime")
                def visit_YieldFrom(self, node):
                    raise SyntaxError("yield is reserved by the runtime")
                def _body(self, body):
                    result = []
                    for statement in body:
                        transformed = self.visit(statement)
                        if isinstance(transformed, list):
                            result.extend(transformed)
                        elif transformed is not None:
                            result.append(transformed)
                        result.append(_aero_ast.Expr(value=_aero_ast.Yield(value=_aero_ast.Constant(value=None))))
                    return result
                def visit_Module(self, node):
                    node.body = self._body(node.body)
                    return node
                def visit_If(self, node):
                    node.test = self.visit(node.test)
                    node.body = self._body(node.body)
                    node.orelse = self._body(node.orelse)
                    return node
                def visit_While(self, node):
                    node.test = self.visit(node.test)
                    node.body = self._body(node.body)
                    node.orelse = self._body(node.orelse)
                    return node
                def visit_For(self, node):
                    node.target = self.visit(node.target)
                    node.iter = self.visit(node.iter)
                    node.body = self._body(node.body)
                    node.orelse = self._body(node.orelse)
                    return node
                def visit_Try(self, node):
                    node.body = self._body(node.body)
                    for handler in node.handlers:
                        handler.body = self._body(handler.body)
                    node.orelse = self._body(node.orelse)
                    node.finalbody = self._body(node.finalbody)
                    return node
                def visit_With(self, node):
                    raise SyntaxError("with statements are not supported")
                def visit_Import(self, node):
                    for alias in node.names:
                        if alias.name.split(".")[0] not in _aero_allowed_imports:
                            raise ImportError("Import not allowed: " + alias.name)
                    return node
                def visit_ImportFrom(self, node):
                    if node.level != 0 or not node.module or node.module.split(".")[0] not in _aero_allowed_imports:
                        raise ImportError("Import not allowed: " + str(node.module))
                    return node

            class __AeroAssignedNames(_aero_ast.NodeVisitor):
                def __init__(self):
                    self.names = set()
                def visit_FunctionDef(self, node):
                    # Function names are globals; function internals remain local.
                    self.names.add(node.name)
                def visit_ClassDef(self, node):
                    # the class name is a global; names inside the class body stay local to it
                    self.names.add(node.name)
                def visit_Name(self, node):
                    if node.id.startswith("_aero_"):
                        raise SyntaxError("Names beginning with _aero_ are reserved")
                    if isinstance(node.ctx, (_aero_ast.Store, _aero_ast.Del)):
                        if node.id in ("aero", "hud", "__builtins__"):
                            raise SyntaxError("Reserved runtime name: " + node.id)
                        self.names.add(node.id)
                def visit_Import(self, node):
                    for alias in node.names:
                        self.names.add(alias.asname or alias.name.split(".")[0])
                def visit_ImportFrom(self, node):
                    for alias in node.names:
                        if alias.name != "*":
                            self.names.add(alias.asname or alias.name)

            def _aero_pyval(v):
                # Java lists arrive as foreign arrays; turn them into real Python lists
                t = _aero_type(v)
                if t is _aero_str or t is _aero_list or t is _aero_tuple or t is _aero_dict:
                    return v
                if _aero_hasattr(v, "__len__") and _aero_hasattr(v, "__getitem__"):
                    return [_aero_pyval(v[i]) for i in _aero_range(_aero_len(v))]
                return v

            class _AeroApi:
                def __init__(self, target):
                    self._aero_target = target
                def __getattr__(self, name):
                    member = _aero_getattr(self._aero_target, name)
                    if _aero_callable(member):
                        def call(*args):
                            return _aero_pyval(member(*args))
                        return call
                    return _aero_pyval(member)

            def _aero_wrap_api(target):
                return _AeroApi(target)

            def _aero_start(source):
                tree = _aero_ast.parse(source, mode="exec")
                assigned = __AeroAssignedNames()
                assigned.visit(tree)
                transformed = __AeroStepTransformer().visit(tree)
                global_names = sorted(assigned.names)
                global_stmt = _aero_ast.Global(names=global_names) if global_names else None
                function_body = ([global_stmt] if global_stmt else []) + transformed.body
                function = _aero_ast.FunctionDef(
                    name="_aero_script_main",
                    args=_aero_ast.arguments(posonlyargs=[], args=[], kwonlyargs=[], kw_defaults=[], defaults=[]),
                    body=function_body or [_aero_ast.Pass()],
                    decorator_list=[]
                )
                module = _aero_ast.fix_missing_locations(_aero_ast.Module(body=[function], type_ignores=[]))
                code = _aero_compile(module, "<aerocomputers>", "exec")
                _aero_globals()["__builtins__"] = _aero_safe_builtins
                _aero_exec(code, _aero_globals(), _aero_globals())
                _aero_globals()["_aero_generator"] = _aero_globals()["_aero_script_main"]()
                _aero_globals()["_aero_finished"] = False

            def _aero_step_n(n, budget):
                g = _aero_globals()
                if g.get("_aero_finished", True):
                    return False
                gen = g["_aero_generator"]
                deadline = _aero_perf() + budget
                i = 0
                while i < n:
                    try:
                        _aero_next(gen)
                    except _aero_StopIteration:
                        g["_aero_finished"] = True
                        return False
                    i += 1
                    if (i & 7) == 0 and _aero_perf() > deadline:
                        break
                return True

            def _aero_get_names():
                return [k for k in _aero_globals().keys() if not k.startswith("_aero_") and k not in ("aero", "hud", "__builtins__")]
            return _aero_start, _aero_step_n, _aero_get_names, _aero_wrap_api

        _aero_start, _aero_step_n, _aero_get_names, _aero_wrap_api = _aero_make_runtime()
        """;

    public PythonScriptRuntime(Object aeroApi, Object hudApi) {
        this(aeroApi, hudApi, line -> {});
    }

    public PythonScriptRuntime(Object aeroApi, Object hudApi, Consumer<String> output) {
        this.output = output == null ? line -> {} : output;
        OutputStream chatOutput = new OutputStream() {
            private final ByteArrayOutputStream line = new ByteArrayOutputStream();
            @Override public synchronized void write(int b) {
                if (b == '\n') {
                    emitLine();
                } else if (b != '\r') {
                    line.write(b);
                }
            }
            @Override public synchronized void write(byte[] bytes, int off, int len) {
                for (int i = off; i < off + len; i++) write(bytes[i] & 0xff);
            }
            @Override public synchronized void flush() { if (line.size() > 0) emitLine(); }
            private void emitLine() {
                String text = line.toString(StandardCharsets.UTF_8);
                line.reset();
                PythonScriptRuntime.this.output.accept(text);
            }
        };
        this.context = Context.newBuilder("python")
                .engine(ENGINE)
                .out(chatOutput)
                // EXPLICIT + list/iterable access: Java Lists (getPos(), radar() ...) become indexable arrays
                .allowHostAccess(HostAccess.newBuilder(HostAccess.EXPLICIT)
                        .allowListAccess(true)
                        .allowIterableAccess(true)
                        .build())
                .allowHostClassLookup(name -> false)
                .allowIO(false)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .resourceLimits(ResourceLimits.newBuilder()
                        .statementLimit(STATEMENT_LIMIT, null)
                        .build())
                .build();
        this.bindings = context.getBindings("python");
        bindings.putMember("_aero_allowed_imports_config", String.join(",", AeroComputersConfig.ALLOWED_LIBRARIES.get()));
        bindings.putMember("hud", hudApi);

        context.eval("python", BOOTSTRAP);
        this.startFn = bindings.getMember("_aero_start");
        this.stepFn = bindings.getMember("_aero_step_n");

        // aero goes through the wrapper so its results are real Python lists
        Object rawAero = aeroApi instanceof AeroScriptApi api ? api.asProxy() : aeroApi;
        bindings.putMember("aero", bindings.getMember("_aero_wrap_api").execute(rawAero));
    }

    public void start(String source) {
        context.resetLimits();
        startFn.execute(source == null ? "" : source);
        started = true;
        finished = false;
    }

    /**
     * Executes up to maxSteps cooperative steps in a single host->guest call,
     * stopping early when the time budget is used up.
     * Returns false once the script has ended.
     */
    public boolean step(int maxSteps, double budgetSeconds) {
        if (!started || finished) return false;
        try {
            context.resetLimits();
            finished = !stepFn.execute(maxSteps, budgetSeconds).asBoolean();
            return !finished;
        } catch (RuntimeException exception) {
            finished = true;
            throw exception;
        }
    }

    public boolean isFinished() {
        return finished;
    }

    public void setVariable(String name, Object value) {
        bindings.putMember(name, value);
    }

    public Object getVariable(String name) {
        Value value = bindings.getMember(name);
        return value == null || value.isNull() ? null : convert(value);
    }

    public Set<String> getVariableNames() {
        Value names = context.eval("python", "_aero_get_names()");
        Set<String> result = new LinkedHashSet<>();
        for (long i = 0; i < names.getArraySize(); i++) result.add(names.getArrayElement(i).asString());
        return result;
    }

    private static Object convert(Value value) {
        if (value.isNull()) return null;
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

    @Override
    public void close() {
        context.close();
    }
}