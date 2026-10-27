package net.tui.aerocomputers.lang;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans a Python program (indentation based blocks) and extracts functions, classes and variables.
 * Must stay free of any Minecraft classes.
 */
public final class ProgramAnalyzer {
    private ProgramAnalyzer() {}

    private enum Kind { DEF, CLASS, OTHER }

    private static final class Frame {
        final int line;
        final int indent;
        final Kind kind;
        final String name;
        final List<ProgramInfo.ParamInfo> params;

        Frame(int line, int indent, Kind kind, String name, List<ProgramInfo.ParamInfo> params) {
            this.line = line;
            this.indent = indent;
            this.kind = kind;
            this.name = name;
            this.params = params;
        }
    }

    public static ProgramInfo analyze(String program) {
        String[] lines = program.split("\n", -1);
        List<List<PyLexer.Token>> tokens = new ArrayList<>(lines.length);
        PyLexer.State[] starts = new PyLexer.State[lines.length];
        PyLexer.State state = PyLexer.State.NONE;

        for (int i = 0; i < lines.length; i++) {
            starts[i] = state;
            PyLexer.Result r = PyLexer.lex(lines[i], state);
            tokens.add(r.tokens());
            state = r.endState();
        }

        return analyze(lines, tokens, starts);
    }

    /** Variant for callers that already lexed the text (the editor). */
    public static ProgramInfo analyze(String[] lines, List<List<PyLexer.Token>> tokens, PyLexer.State[] starts) {
        Map<Integer, Integer> blockEnd = new HashMap<>();
        Map<String, ProgramInfo.FunctionInfo> functions = new LinkedHashMap<>();
        Map<String, ProgramInfo.ClassInfo> classes = new LinkedHashMap<>();
        Set<String> variables = new LinkedHashSet<>();
        String[] firstWords = new String[lines.length];
        Deque<Frame> stack = new ArrayDeque<>();
        int lastCodeLine = -1;

        for (int i = 0; i < lines.length; i++) {
            firstWords[i] = "";

            // inside a multi-line string: not code
            if (starts[i] != PyLexer.State.NONE) continue;

            List<PyLexer.Token> toks = PyLexer.significant(tokens.get(i));
            if (toks.isEmpty()) continue; // blank or comment-only

            int indent = indentOf(lines[i]);

            while (!stack.isEmpty() && stack.peek().indent >= indent) {
                closeFrame(stack.pop(), lastCodeLine, stack, blockEnd, functions, classes);
            }

            PyLexer.Token first = toks.get(0);
            String word = first.type() == PyLexer.Type.IDENT ? first.text() : "";
            firstWords[i] = word;

            boolean endsWithColon = toks.get(toks.size() - 1).is(":");
            boolean opens = endsWithColon && LanguageSpec.BLOCK_STARTERS.contains(word);

            switch (word) {
                case "def" -> {
                    String name = toks.size() > 1 && toks.get(1).type() == PyLexer.Type.IDENT
                            ? toks.get(1).text() : "";
                    List<ProgramInfo.ParamInfo> params = parseParams(lines[i], toks);

                    for (ProgramInfo.ParamInfo p : params) {
                        if (!p.name().equals("self")) variables.add(p.name());
                    }

                    if (opens) {
                        stack.push(new Frame(i, indent, Kind.DEF, name, params));
                    } else if (!name.isEmpty()) {
                        functions.put(name, new ProgramInfo.FunctionInfo(i, i, params));
                    }
                }

                case "class" -> {
                    String name = toks.size() > 1 && toks.get(1).type() == PyLexer.Type.IDENT
                            ? toks.get(1).text() : "";
                    if (!name.isEmpty()) {
                        classes.computeIfAbsent(name,
                                k -> new ProgramInfo.ClassInfo(k, new LinkedHashMap<>()));
                    }
                    if (opens) stack.push(new Frame(i, indent, Kind.CLASS, name, null));
                }

                case "for" -> {
                    for (int k = 1; k < toks.size(); k++) {
                        PyLexer.Token t = toks.get(k);
                        if (t.is("in")) break;
                        if (t.type() == PyLexer.Type.IDENT && !LanguageSpec.isKeyword(t.text())) {
                            variables.add(t.text());
                        }
                    }
                    if (opens) stack.push(new Frame(i, indent, Kind.OTHER, null, null));
                }

                case "except" -> {
                    for (int k = 1; k + 1 < toks.size(); k++) {
                        if (toks.get(k).is("as") && toks.get(k + 1).type() == PyLexer.Type.IDENT) {
                            variables.add(toks.get(k + 1).text());
                        }
                    }
                    if (opens) stack.push(new Frame(i, indent, Kind.OTHER, null, null));
                }

                case "import", "from" -> { }

                case "if", "elif", "while", "else", "try", "finally", "with" -> {
                    if (opens) stack.push(new Frame(i, indent, Kind.OTHER, null, null));
                }

                default -> collectAssigned(toks, variables);
            }

            lastCodeLine = i;
        }

        while (!stack.isEmpty()) {
            closeFrame(stack.pop(), lastCodeLine, stack, blockEnd, functions, classes);
        }

        return new ProgramInfo(lines, firstWords, blockEnd, new HashMap<>(), functions, classes, variables);
    }

    private static void closeFrame(Frame f, int endLine, Deque<Frame> stack,
                                   Map<Integer, Integer> blockEnd,
                                   Map<String, ProgramInfo.FunctionInfo> functions,
                                   Map<String, ProgramInfo.ClassInfo> classes) {
        int end = Math.max(endLine, f.line);
        blockEnd.put(f.line, end);

        if (f.kind == Kind.DEF && f.name != null && !f.name.isEmpty()) {
            ProgramInfo.FunctionInfo fn = new ProgramInfo.FunctionInfo(f.line, end, f.params);

            Frame parent = stack.peek();
            if (parent != null && parent.kind == Kind.CLASS && parent.name != null && !parent.name.isEmpty()) {
                classes.computeIfAbsent(parent.name,
                        k -> new ProgramInfo.ClassInfo(k, new LinkedHashMap<>())).methods().put(f.name, fn);
            } else {
                functions.put(f.name, fn);
            }
        }
    }

    static int indentOf(String line) {
        int n = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') n++;
            else if (c == '\t') n += 4;
            else break;
        }
        return n;
    }

    private static boolean isOpener(PyLexer.Token t) {
        return t.type() == PyLexer.Type.BRACKET && "([{".contains(t.text());
    }

    private static boolean isCloser(PyLexer.Token t) {
        return t.type() == PyLexer.Type.BRACKET && ")]}".contains(t.text());
    }

    /**
     * "a = ...", "a, b = ...", "a = b = ...", "a: int = ...".
     * Does NOT match f(x=1), a.b = 1 or a[0] = 1.
     */
    private static void collectAssigned(List<PyLexer.Token> toks, Set<String> out) {
        int depth = 0;
        int segStart = 0;

        for (int i = 0; i < toks.size(); i++) {
            PyLexer.Token t = toks.get(i);
            if (isOpener(t)) depth++;
            else if (isCloser(t)) depth--;
            else if (depth == 0 && t.is("=")) {
                addTargets(toks, segStart, i, out);
                segStart = i + 1;
            }
        }
    }

    private static void addTargets(List<PyLexer.Token> toks, int from, int to, Set<String> out) {
        int i = from;
        while (i < to) {
            PyLexer.Token t = toks.get(i);

            if (t.is(":")) break; // annotation

            // "f(...)" / "a[...]" : skip the whole group, its content is not assigned
            if ((t.is("(") || t.is("[")) && i > from) {
                PyLexer.Token prev = toks.get(i - 1);
                if (prev.type() == PyLexer.Type.IDENT || isCloser(prev)) {
                    int d = 0;
                    while (i < to) {
                        PyLexer.Token x = toks.get(i);
                        if (isOpener(x)) d++;
                        else if (isCloser(x)) { d--; if (d == 0) break; }
                        i++;
                    }
                    i++;
                    continue;
                }
            }

            if (t.type() == PyLexer.Type.IDENT && !LanguageSpec.isKeyword(t.text())) {
                PyLexer.Token prev = i > from ? toks.get(i - 1) : null;
                PyLexer.Token next = i + 1 < to ? toks.get(i + 1) : null;

                boolean attribute = prev != null && prev.is(".");
                boolean base = next != null && (next.is(".") || next.is("[") || next.is("("));

                if (!attribute && !base) out.add(t.text());
            }
            i++;
        }
    }

    /** Parses "def name(a, b=2, *args, **kw)" into parameter definitions. */
    private static List<ProgramInfo.ParamInfo> parseParams(String line, List<PyLexer.Token> toks) {
        int open = 2; // def NAME (
        if (toks.size() <= open || !toks.get(open).is("(")) return List.of();

        List<ProgramInfo.ParamInfo> result = new ArrayList<>();
        int depth = 0;
        int segmentStart = open + 1;
        boolean closed = false;

        for (int i = open + 1; i < toks.size(); i++) {
            PyLexer.Token t = toks.get(i);

            if (isOpener(t)) {
                depth++;
            } else if (isCloser(t)) {
                if (depth == 0) {
                    addParam(line, toks, segmentStart, i, result);
                    closed = true;
                    break;
                }
                depth--;
            } else if (depth == 0 && t.is(",")) {
                addParam(line, toks, segmentStart, i, result);
                segmentStart = i + 1;
            }
        }

        if (!closed) addParam(line, toks, segmentStart, toks.size(), result);

        return result;
    }

    private static void addParam(String line, List<PyLexer.Token> toks, int from, int to,
                                 List<ProgramInfo.ParamInfo> out) {
        int k = from;
        while (k < to && (toks.get(k).is("*") || toks.get(k).is("**"))) k++;
        if (k >= to || toks.get(k).type() != PyLexer.Type.IDENT) return;

        String name = toks.get(k).text();
        String defaultExpr = null;

        for (int m = k + 1; m < to; m++) {
            if (toks.get(m).is("=")) {
                if (m + 1 < to) {
                    defaultExpr = line.substring(toks.get(m + 1).start(), toks.get(to - 1).end());
                }
                break;
            }
        }

        out.add(new ProgramInfo.ParamInfo(name, defaultExpr));
    }
}