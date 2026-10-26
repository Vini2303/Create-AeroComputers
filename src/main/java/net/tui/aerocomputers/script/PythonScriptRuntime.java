package net.tui.aerocomputers.script;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * A persistent, cooperative GraalPy runtime.
 *
 * Scripts are transformed into a generator. Each call to step() advances
 * execution through one or more Python statements, allowing the Minecraft
 * server tick to regain control between statements.
 */
public final class PythonScriptRuntime implements AutoCloseable {
    private final Context context;
    private boolean started;
    private boolean finished;
    private final Consumer<String> output;

    private static final String BOOTSTRAP = """
        def _aero_make_runtime():
            import ast as _aero_ast
            import builtins as _aero_builtins

            _aero_original_import = _aero_builtins.__import__
            _aero_exec = _aero_builtins.exec
            _aero_compile = _aero_builtins.compile
            _aero_next = _aero_builtins.next
            _aero_globals = _aero_builtins.globals
            _aero_allowed_imports = {"math"}

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
                    "set", "sorted", "SyntaxError", "ImportError", "StopIteration"
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
                    raise SyntaxError("class definitions are not supported")
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
                    raise SyntaxError("class definitions are not supported")
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

            def _aero_step():
                if _aero_globals().get("_aero_finished", True):
                    return False
                try:
                    _aero_next(_aero_globals()["_aero_generator"])
                    return True
                except StopIteration:
                    _aero_globals()["_aero_finished"] = True
                    return False

            def _aero_get_names():
                return [k for k in _aero_globals().keys() if not k.startswith("_aero_") and k not in ("aero", "hud", "__builtins__")]
            return _aero_start, _aero_step, _aero_get_names

        _aero_start, _aero_step, _aero_get_names = _aero_make_runtime()
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
                .out(chatOutput)
                .allowHostAccess(HostAccess.EXPLICIT)
                .allowHostClassLookup(name -> false)
                .allowIO(false)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .build();
        context.getBindings("python").putMember("aero", aeroApi instanceof AeroScriptApi api ? api.asProxy() : aeroApi);
        context.getBindings("python").putMember("hud", hudApi);
        context.eval("python", BOOTSTRAP);
    }

    public void start(String source) {
        context.getBindings("python").putMember("_aero_source", source == null ? "" : source);
        context.eval("python", "_aero_start(_aero_source)");
        started = true;
        finished = false;
    }

    /**
     * Executes one cooperative step. Returns false once the script has ended.
     */
    public boolean step() {
        if (!started || finished) return false;
        try {
            finished = !context.eval("python", "_aero_step()").asBoolean();
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
        context.getBindings("python").putMember(name, value);
    }

    public Object getVariable(String name) {
        Value value = context.getBindings("python").getMember(name);
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
