package net.tui.aerocomputers.lang;

import java.util.ArrayList;
import java.util.List;

/**
 * Small Python lexer used by the editor and the analyzer.
 * Lossless per line (concatenating the token texts gives the line back) and aware of
 * multi-line triple-quoted strings through {@link State}.
 *
 * Must stay free of any Minecraft classes.
 */
public final class PyLexer {
    private PyLexer() {}

    public enum Type { WHITESPACE, IDENT, NUMBER, STRING, COMMENT, BRACKET, OP }

    /** What the line STARTS in: normal code or inside a multi-line string. */
    public enum State { NONE, TRIPLE_DOUBLE, TRIPLE_SINGLE }

    public record Token(Type type, String text, int start, int end, boolean unterminated) {
        public boolean is(String s) { return text.equals(s); }
    }

    public record Result(List<Token> tokens, State endState) {}

    private static final String[] OPS3 = {"**=", "//=", ">>=", "<<=", "..."};
    private static final String[] OPS2 = {"**", "//", "==", "!=", "<=", ">=", "->", "+=", "-=", "*=", "/=", "%=",
            "&=", "|=", "^=", ":=", "<<", ">>", "@="};

    public static List<Token> lex(String line) {
        return lex(line, State.NONE).tokens();
    }

    public static Result lex(String s, State in) {
        List<Token> out = new ArrayList<>();
        int n = s.length();
        int i = 0;

        if (in != State.NONE) {
            char q = in == State.TRIPLE_DOUBLE ? '"' : '\'';
            int close = findTripleEnd(s, 0, q);
            if (close < 0) {
                if (n > 0) out.add(new Token(Type.STRING, s, 0, n, false));
                return new Result(out, in);
            }
            int end = close + 3;
            out.add(new Token(Type.STRING, s.substring(0, end), 0, end, false));
            i = end;
        }

        while (i < n) {
            char c = s.charAt(i);

            if (Character.isWhitespace(c)) {
                int j = i;
                while (j < n && Character.isWhitespace(s.charAt(j))) j++;
                out.add(new Token(Type.WHITESPACE, s.substring(i, j), i, j, false));
                i = j;
                continue;
            }

            if (c == '#') {
                out.add(new Token(Type.COMMENT, s.substring(i), i, n, false));
                i = n;
                continue;
            }

            int prefix = stringPrefixLength(s, i);
            if (prefix >= 0) {
                int qi = i + prefix;
                char q = s.charAt(qi);
                boolean triple = qi + 2 < n && s.charAt(qi + 1) == q && s.charAt(qi + 2) == q;

                if (triple) {
                    int close = findTripleEnd(s, qi + 3, q);
                    if (close < 0) {
                        out.add(new Token(Type.STRING, s.substring(i), i, n, false));
                        return new Result(out, q == '"' ? State.TRIPLE_DOUBLE : State.TRIPLE_SINGLE);
                    }
                    int end = close + 3;
                    out.add(new Token(Type.STRING, s.substring(i, end), i, end, false));
                    i = end;
                    continue;
                }

                int j = qi + 1;
                boolean closed = false;
                while (j < n) {
                    char d = s.charAt(j);
                    if (d == '\\') { j += 2; continue; }
                    if (d == q) { j++; closed = true; break; }
                    j++;
                }
                if (j > n) j = n;
                out.add(new Token(Type.STRING, s.substring(i, j), i, j, !closed));
                i = j;
                continue;
            }

            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                int j = i;
                if (c == '0' && j + 1 < n && "xXbBoO".indexOf(s.charAt(j + 1)) >= 0) {
                    j += 2;
                    while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                } else {
                    while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                    if (j < n && s.charAt(j) == '.') {
                        j++;
                        while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                    }
                    if (j < n && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
                        int k = j + 1;
                        if (k < n && (s.charAt(k) == '+' || s.charAt(k) == '-')) k++;
                        if (k < n && Character.isDigit(s.charAt(k))) {
                            j = k;
                            while (j < n && Character.isDigit(s.charAt(j))) j++;
                        }
                    }
                    if (j < n && (s.charAt(j) == 'j' || s.charAt(j) == 'J')) j++;
                }
                out.add(new Token(Type.NUMBER, s.substring(i, j), i, j, false));
                i = j;
                continue;
            }

            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                out.add(new Token(Type.IDENT, s.substring(i, j), i, j, false));
                i = j;
                continue;
            }

            if ("()[]{}".indexOf(c) >= 0) {
                out.add(new Token(Type.BRACKET, String.valueOf(c), i, i + 1, false));
                i++;
                continue;
            }

            String op = null;
            for (String o : OPS3) if (s.startsWith(o, i)) { op = o; break; }
            if (op == null) for (String o : OPS2) if (s.startsWith(o, i)) { op = o; break; }
            if (op == null) op = String.valueOf(c);
            out.add(new Token(Type.OP, op, i, i + op.length(), false));
            i += op.length();
        }

        return new Result(out, State.NONE);
    }

    /** Everything except whitespace and comments. */
    public static List<Token> significant(List<Token> tokens) {
        List<Token> out = new ArrayList<>(tokens.size());
        for (Token t : tokens) {
            if (t.type() != Type.WHITESPACE && t.type() != Type.COMMENT) out.add(t);
        }
        return out;
    }

    /** Length of a string prefix (r, b, f, rb ...) if a string literal starts at i, else -1. */
    private static int stringPrefixLength(String s, int i) {
        int n = s.length();
        int k = 0;
        while (k < 2 && i + k < n && "rRbBuUfF".indexOf(s.charAt(i + k)) >= 0) k++;
        if (i + k < n && (s.charAt(i + k) == '"' || s.charAt(i + k) == '\'')) return k;
        return -1;
    }

    private static int findTripleEnd(String s, int from, char q) {
        int n = s.length();
        int j = from;
        while (j + 2 < n + 0 && j <= n - 3) {
            char c = s.charAt(j);
            if (c == '\\') { j += 2; continue; }
            if (c == q && s.charAt(j + 1) == q && s.charAt(j + 2) == q) return j;
            j++;
        }
        return -1;
    }
}