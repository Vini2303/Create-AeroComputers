package net.tui.aerocomputers.script;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

public final class PythonScriptRuntime implements AutoCloseable {

    private final Context context;

    public PythonScriptRuntime(Object aeroApi, Object hudApi) {
        this.context = Context.newBuilder("python")
                .allowHostAccess(HostAccess.EXPLICIT)
                .allowHostClassLookup(name -> false)
                .allowIO(false)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .build();

        var bindings = context.getBindings("python");
        bindings.putMember("aero", aeroApi);
        bindings.putMember("hud", hudApi);
    }

    public void execute(String source) {
        context.eval("python", source);
    }

    public void setVariable(String name, Object value) {
        context.getBindings("python").putMember(name, value);
    }

    public Object getVariable(String name) {
        Value value = context.getBindings("python").getMember(name);
        return value == null ? null : value.as(Object.class);
    }

    @Override
    public void close() {
        context.close();
    }
}