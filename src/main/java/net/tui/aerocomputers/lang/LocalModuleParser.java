package net.tui.aerocomputers.lang;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalModuleParser {
    private LocalModuleParser() {}

    public record ClassInfo(List<String> ctorParams,
                            List<String> methods,
                            Map<String, List<String>> methodParams,
                            List<String> fields) {}

    public record ModuleInfo(Map<String, List<String>> functions,
                             Map<String, ClassInfo> classes,
                             List<String> variables) {}

    private static final Pattern DEF = Pattern.compile("^def\\s+([A-Za-z_]\\w*)\\s*\\(([^)]*)\\)");
    private static final Pattern CLASS = Pattern.compile("^class\\s+([A-Za-z_]\\w*)");
    private static final Pattern ASSIGN = Pattern.compile("^([A-Za-z_]\\w*)\\s*(?::[^=]+)?=(?!=)");
    private static final Pattern SELF_FIELD = Pattern.compile("\\bself\\.([A-Za-z_]\\w*)\\s*(?::[^=]+)?=(?!=)");

    private static final class ClassBuilder {
        List<String> ctor = new ArrayList<>();
        Set<String> methods = new LinkedHashSet<>();
        Map<String, List<String>> methodParams = new LinkedHashMap<>();
        Set<String> fields = new LinkedHashSet<>();
        ClassInfo build() {
            return new ClassInfo(List.copyOf(ctor), List.copyOf(methods),
                    Map.copyOf(methodParams), List.copyOf(fields));
        }
    }

    public static ModuleInfo parse(String source) {
        Map<String, List<String>> functions = new LinkedHashMap<>();
        Map<String, ClassBuilder> classes = new LinkedHashMap<>();
        Set<String> variables = new LinkedHashSet<>();

        ClassBuilder cls = null;
        int classIndent = -1;
        boolean inMethod = false;
        int methodIndent = -1;

        for (String raw : source.split("\n", -1)) {
            String line = raw.replace("\t", "    ").stripTrailing();
            String text = line.stripLeading();
            if (text.isEmpty() || text.startsWith("#")) continue;
            int indent = line.length() - text.length();

            if (cls != null && indent <= classIndent) { cls = null; inMethod = false; }
            if (inMethod && indent <= methodIndent) inMethod = false;

            Matcher m;
            if ((m = DEF.matcher(text)).find()) {
                String name = m.group(1);
                List<String> params = params(m.group(2));
                if (cls != null && !inMethod) {
                    if (name.equals("__init__")) cls.ctor = params;
                    else if (!name.startsWith("_")) {
                        cls.methods.add(name);
                        cls.methodParams.put(name, params);
                    }
                    inMethod = true;
                    methodIndent = indent;
                } else if (indent == 0 && !name.startsWith("_")) {
                    functions.put(name, params);
                }
            } else if ((m = CLASS.matcher(text)).find()) {
                if (indent == 0 && !m.group(1).startsWith("_")) {
                    cls = new ClassBuilder();
                    classIndent = indent;
                    classes.put(m.group(1), cls);
                }
            } else if (cls != null && inMethod) {
                Matcher f = SELF_FIELD.matcher(text);
                while (f.find()) cls.fields.add(f.group(1));
            } else if (cls != null) {
                if ((m = ASSIGN.matcher(text)).find() && !m.group(1).startsWith("_")) cls.fields.add(m.group(1));
            } else if (indent == 0 && (m = ASSIGN.matcher(text)).find() && !m.group(1).startsWith("_")) {
                variables.add(m.group(1));
            }
        }

        Map<String, ClassInfo> built = new LinkedHashMap<>();
        classes.forEach((k, v) -> built.put(k, v.build()));
        return new ModuleInfo(Map.copyOf(functions), Map.copyOf(built), List.copyOf(variables));
    }

    /** "self, a, b=2, *args, c: int = 3" -> [a, b, args, c] */
    private static List<String> params(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i <= s.length(); i++) {
            char c = i < s.length() ? s.charAt(i) : ',';
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                String p = s.substring(start, i).trim();
                start = i + 1;
                int cut = p.length();
                for (char stop : new char[]{'=', ':'}) {
                    int idx = p.indexOf(stop);
                    if (idx >= 0) cut = Math.min(cut, idx);
                }
                p = p.substring(0, cut).trim().replaceFirst("^\\*+", "");
                if (!p.isEmpty() && !p.equals("self") && !p.equals("cls") && !p.equals("/")) out.add(p);
            }
        }
        return out;
    }
}