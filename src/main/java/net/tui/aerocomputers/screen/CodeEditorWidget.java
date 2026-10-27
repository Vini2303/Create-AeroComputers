package net.tui.aerocomputers.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.tui.aerocomputers.block.entity.BoardComputerBlockEntity;
import net.tui.aerocomputers.lang.LanguageSpec;
import net.tui.aerocomputers.lang.ProgramAnalyzer;
import net.tui.aerocomputers.lang.ProgramInfo;
import net.tui.aerocomputers.lang.PyLexer;
import net.tui.aerocomputers.lang.PyLexer.Token;
import net.tui.aerocomputers.lang.PyLexer.Type;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.Consumer;

import static net.minecraft.client.gui.screens.Screen.hasAltDown;
import static net.minecraft.client.gui.screens.Screen.hasControlDown;
import static net.minecraft.client.gui.screens.Screen.hasShiftDown;

/**
 * A VS Code (Dark+) style Python editor.
 *
 * Highlighting: semantic colours, bracket pair colourisation, indent guides, matching bracket box.
 * Editing: python aware auto indent, auto closing pairs, undo/redo, comment toggle, move/duplicate lines,
 *          word navigation, multi click selection, smart Home, tab stops.
 * Intellisense: fuzzy completion with icons, member completion for modules / hud objects,
 *               import completion, signature help with active parameter.
 */
public class CodeEditorWidget extends AbstractWidget {

    // =====================================================================
    // THEME (VS Code Dark+)
    // =====================================================================
    private static final int C_BG = 0xFF1E1E1E;
    private static final int C_GUTTER_NUM = 0xFF858585;
    private static final int C_GUTTER_NUM_ACTIVE = 0xFFC6C6C6;
    private static final int C_LINE_HL = 0xFF282828;
    private static final int C_SEL = 0xFF264F78;
    private static final int C_SEL_INACTIVE = 0xFF3A3D41;
    private static final int C_CURSOR = 0xFFAEAFAD;
    private static final int C_GUIDE = 0xFF404040;
    private static final int C_GUIDE_ACTIVE = 0xFF707070;
    private static final int C_TEXT = 0xFFD4D4D4;
    private static final int C_KEYWORD_CTRL = 0xFFC586C0;
    private static final int C_KEYWORD = 0xFF569CD6;
    private static final int C_FUNCTION = 0xFFDCDCAA;
    private static final int C_CLASS = 0xFF4EC9B0;
    private static final int C_VARIABLE = 0xFF9CDCFE;
    private static final int C_CONSTANT = 0xFF4FC1FF;
    private static final int C_STRING = 0xFFCE9178;
    private static final int C_NUMBER = 0xFFB5CEA8;
    private static final int C_COMMENT = 0xFF6A9955;
    private static final int[] C_BRACKETS = {0xFFFFD700, 0xFFDA70D6, 0xFF179FFF};
    private static final int C_BRACKET_UNMATCHED = 0xFFFF1212;
    private static final int C_BRACKET_MATCH_BORDER = 0xFF888888;
    private static final int C_BRACKET_MATCH_FILL = 0x30FFFFFF;

    private static final int C_POPUP_BG = 0xFF252526;
    private static final int C_POPUP_BORDER = 0xFF454545;
    private static final int C_POPUP_SEL = 0xFF04395E;
    private static final int C_POPUP_HOVER = 0xFF2A2D2E;
    private static final int C_POPUP_TEXT = 0xFFCCCCCC;
    private static final int C_POPUP_DETAIL = 0xFF8C8C8C;
    private static final int C_POPUP_MATCH = 0xFF18A3FF;

    private static final int LH = 10;               // line height
    private static final int SB = 7;                // scrollbar size
    private static final int SB_MIN_THUMB = 15;
    private static final String INDENT = "    ";     // 4 spaces like VS Code's Python default
    private static final int TAB = 4;

    private static final int KIND_NONE = 0;         // never merged into the previous undo step
    private static final int KIND_TYPE = 1;
    private static final int KIND_DELETE = 2;

    private static final int AC_ROWS = 8;
    private static final int AC_ROW_H = 12;

    /** Hud / aero objects are host proxies; keyword arguments are most likely not supported there. */
    private static final boolean SUGGEST_NAMED_ARGS_FOR_API = false;

    public static final Map<String, String> HELP_LOOKUP = Map.ofEntries(
            Map.entry("if", "help.aero.if.text"),
            Map.entry("while", "help.aero.while.text"),
            Map.entry("for", "help.aero.for.text"),
            Map.entry("def", "help.aero.def.text"),
            Map.entry("class", "help.aero.class.text"),
            Map.entry("Label", "help.aero.label.text"),
            Map.entry("Line", "help.aero.line.text"),
            Map.entry("Rect", "help.aero.rect.text"),
            Map.entry("Poly", "help.aero.poly.text"),
            Map.entry("print", "help.aero.print.text"),
            Map.entry("getPos", "help.aero.getpos.text"),
            Map.entry("getRot", "help.aero.getrot.text"),
            Map.entry("getVel", "help.aero.getvel.text")
    );

    // =====================================================================
    // STATE
    // =====================================================================
    private final List<String> lines = new ArrayList<>();

    private int cursorLine = 0;
    private int cursorColumn = 0;
    private int selLine = -1;           // selection anchor
    private int selColumn = -1;
    private int desiredColumn = -1;

    private int scrollX = 0;
    private int scrollY = 0;

    private boolean readOnly = false;
    private final Consumer<String> helpCallback;

    private boolean isDragging = false;
    private boolean draggingV = false;
    private boolean draggingH = false;
    private int scrollbarDragOffset = 0;

    private long lastClickTime = 0;
    private int lastClickLine = -1;
    private int lastClickCol = -1;
    private int clickCount = 0;

    private long blinkBase = System.currentTimeMillis();

    // undo / redo
    private record Snapshot(List<String> lines, int cl, int cc, int sl, int sc) {}
    private final Deque<Snapshot> undoStack = new ArrayDeque<>();
    private final Deque<Snapshot> redoStack = new ArrayDeque<>();
    private int lastEditKind = KIND_NONE;
    private long lastEditTime = 0;
    private String lastLineCopy = null;

    // analysis (rebuilt after every edit)
    private List<List<Token>> lineTokens = new ArrayList<>();
    private PyLexer.State[] lineStart = new PyLexer.State[0];
    private int[] lineIndent = new int[0];
    private int contentWidth = 0;
    private ProgramInfo info;
    private final Map<Long, Integer> bracketDepth = new HashMap<>();
    private final Map<Long, Long> bracketPair = new HashMap<>();
    private final Map<String, String> moduleAliases = new HashMap<>();    // alias -> module
    private final Map<String, String> importedNames = new HashMap<>();    // name  -> math.sqrt
    private final Map<String, String> variableClasses = new HashMap<>();  // var   -> hud.Label / Player
    private final Map<String, List<String>> ctorParams = new HashMap<>();   // class -> __init__ params
    private final Map<String, List<String>> methodParams = new HashMap<>(); // method name -> params
    private final Map<String, Set<String>> classMethodNames = new HashMap<>();
    private final Map<String, Set<String>> classFields = new HashMap<>();
    private String[] lineClass = new String[0];                             // class a line belongs to
    private List<String> extraVariables = List.of();
    private List<String> variables = List.of();
    private List<String> functions = List.of();
    private List<String> classes = List.of();
    private Map<String, List<String>> callableParams = Map.of();

    // autocomplete
    private enum Kind { KEYWORD, FUNCTION, CLASS, VARIABLE, MODULE, CONSTANT, PROPERTY, PARAM }
    private record Candidate(String label, Kind kind, String detail) {}
    private record Suggestion(Candidate c, int[] match, int score) {}

    private boolean autocompleteOpen = false;
    private final List<Suggestion> suggestions = new ArrayList<>();
    private int autocompleteIndex = 0;
    private int autocompleteTop = 0;
    private int autocompleteStartColumn = 0;
    private boolean suppressSuffix = false;

    // ctrl + click help
    private record HelpWordHit(String word, int line, int startColumn, int endColumn) {}
    private HelpWordHit hoveredHelpWord;

    // =====================================================================
    // CONSTRUCTION
    // =====================================================================
    public CodeEditorWidget(int x, int y, int width, int height, String text,
                            BoardComputerBlockEntity.EditorCompletions completions,
                            Consumer<String> helpCallback) {
        super(x, y, width, height, Component.empty());
        lines.addAll(List.of(text.split("\n", -1)));
        this.extraVariables = completions.extraVariables();
        this.helpCallback = helpCallback;
        rescan();
    }

    public void updateCompletions(BoardComputerBlockEntity.EditorCompletions completions) {
        this.extraVariables = completions.extraVariables();
        rescan();
        if (autocompleteOpen) updateAutocomplete(false);
    }

    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public boolean isReadOnly() { return readOnly; }

    public String getText() { return String.join("\n", lines); }

    public void setText(String text) {
        lines.clear();
        lines.addAll(List.of(text.split("\n", -1)));
        undoStack.clear();
        redoStack.clear();
        lastEditKind = KIND_NONE;
        clearSelection();
        cursorLine = Math.min(cursorLine, lines.size() - 1);
        cursorColumn = Math.min(cursorColumn, lines.get(cursorLine).length());
        closeAutocomplete();
        rescan();
        clampScroll();
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (focused) resetBlink(); else closeAutocomplete();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {}

    // =====================================================================
    // GEOMETRY
    // =====================================================================
    private Font font() { return Minecraft.getInstance().font; }
    private int gutter() { return Math.max(34, font().width(String.valueOf(lines.size())) + 22); }
    private int textLeft() { return getX() + gutter(); }
    private int textOrigin() { return textLeft() + 6 - scrollX; }
    private int lineTop(int line) { return getY() + 5 + line * LH - scrollY; }
    private int viewW() { return width - gutter() - SB; }
    private int viewH() { return height - SB; }

    private int xOf(int line, int col) {
        String s = lines.get(line);
        return textOrigin() + font().width(s.substring(0, Math.min(col, s.length())));
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private int maxScrollY() {
        int content = 5 + lines.size() * LH;
        int vh = viewH();
        return content <= vh ? 0 : content - vh + LH * 3;
    }

    private int maxScrollX() { return Math.max(0, contentWidth - viewW()); }

    private void clampScroll() {
        scrollY = clamp(scrollY, 0, maxScrollY());
        scrollX = clamp(scrollX, 0, maxScrollX());
    }

    private void ensureCursorVisible() {
        Font f = font();
        String line = lines.get(cursorLine);
        cursorColumn = Math.min(cursorColumn, line.length());

        int cx = f.width(line.substring(0, cursorColumn));
        int area = viewW() - 14;
        if (cx < scrollX) scrollX = Math.max(0, cx - 20);
        if (cx > scrollX + area) scrollX = cx - area;

        int top = 5 + cursorLine * LH;
        int bottom = top + LH;
        int vh = viewH();
        if (top - 2 < scrollY) scrollY = top - 2;
        if (bottom + 2 > scrollY + vh) scrollY = bottom + 2 - vh;

        clampScroll();
    }

    private void resetBlink() { blinkBase = System.currentTimeMillis(); }

    // =====================================================================
    // ANALYSIS
    // =====================================================================
    private static long key(int line, int col) { return ((long) line << 20) | col; }

    private static int indentOf(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') n++; else if (c == '\t') n += TAB; else break;
        }
        return n;
    }

    private static String leadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return s.substring(0, i);
    }

    private void rescan() {
        int n = lines.size();
        Font f = font();

        lineTokens = new ArrayList<>(n);
        lineStart = new PyLexer.State[n];
        PyLexer.State st = PyLexer.State.NONE;
        int maxW = 0;

        for (int i = 0; i < n; i++) {
            lineStart[i] = st;
            PyLexer.Result r = PyLexer.lex(lines.get(i), st);
            lineTokens.add(r.tokens());
            st = r.endState();
            maxW = Math.max(maxW, f.width(lines.get(i)));
        }
        contentWidth = maxW + 30;

        info = ProgramAnalyzer.analyze(lines.toArray(new String[0]), lineTokens, lineStart);

        // effective indent per line (blank lines continue the guides)
        int[] raw = new int[n];
        for (int i = 0; i < n; i++) raw[i] = lines.get(i).isBlank() ? -1 : indentOf(lines.get(i));
        int[] prev = new int[n];
        int[] next = new int[n];
        int p = 0;
        for (int i = 0; i < n; i++) { if (raw[i] >= 0) p = raw[i]; prev[i] = p; }
        int q = 0;
        for (int i = n - 1; i >= 0; i--) { if (raw[i] >= 0) q = raw[i]; next[i] = q; }
        lineIndent = new int[n];
        for (int i = 0; i < n; i++) lineIndent[i] = raw[i] >= 0 ? raw[i] : Math.min(prev[i], next[i]);

        // bracket pairs + depth (for colourisation and matching)
        bracketDepth.clear();
        bracketPair.clear();
        Deque<long[]> stack = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            for (Token t : lineTokens.get(i)) {
                if (t.type() != Type.BRACKET) continue;
                long k = key(i, t.start());
                char ch = t.text().charAt(0);
                if (ch == '(' || ch == '[' || ch == '{') {
                    bracketDepth.put(k, stack.size());
                    stack.push(new long[]{k, ch});
                } else if (!stack.isEmpty() && matches((char) stack.peek()[1], ch)) {
                    long[] o = stack.pop();
                    bracketDepth.put(k, stack.size());
                    bracketPair.put(k, o[0]);
                    bracketPair.put(o[0], k);
                } else {
                    bracketDepth.put(k, -1);
                }
            }
        }
        for (long[] o : stack) bracketDepth.put(o[0], -1);

        analyzeClasses();
        analyzeImports();

        Set<String> vars = new LinkedHashSet<>(extraVariables);
        vars.addAll(info.variables());
        variables = List.copyOf(vars);
        functions = List.copyOf(info.functions().keySet());
        classes = List.copyOf(info.classes().keySet());
        callableParams = info.callableParams();
    }

    private static boolean matches(char open, char close) {
        return (open == '(' && close == ')') || (open == '[' && close == ']') || (open == '{' && close == '}');
    }

    private void analyzeImports() {
        moduleAliases.clear();
        importedNames.clear();
        variableClasses.clear();

        for (int i = 0; i < lines.size(); i++) {
            if (lineStart[i] != PyLexer.State.NONE) continue;
            List<Token> t = PyLexer.significant(lineTokens.get(i));
            if (t.isEmpty()) continue;
            String first = t.get(0).text();

            if (first.equals("import")) {
                int k = 1;
                while (k < t.size()) {
                    if (t.get(k).type() != Type.IDENT) { k++; continue; }
                    String mod = t.get(k).text();
                    String alias = mod;
                    int m = k + 1;
                    while (m + 1 < t.size() && t.get(m).is(".")) m += 2;
                    if (m + 1 < t.size() && t.get(m).is("as") && t.get(m + 1).type() == Type.IDENT) {
                        alias = t.get(m + 1).text();
                        m += 2;
                    }
                    if (LanguageSpec.IMPORTABLE_MODULES.contains(mod)) moduleAliases.put(alias, mod);
                    k = m;
                    while (k < t.size() && !t.get(k).is(",")) k++;
                    k++;
                }
            } else if (first.equals("from") && t.size() >= 4 && t.get(2).is("import")) {
                String mod = t.get(1).text();
                if (!LanguageSpec.IMPORTABLE_MODULES.contains(mod)) continue;
                for (int k = 3; k < t.size(); k++) {
                    Token tk = t.get(k);
                    if (tk.type() != Type.IDENT) continue;
                    String name = tk.text();
                    String alias = name;
                    if (k + 2 < t.size() && t.get(k + 1).is("as") && t.get(k + 2).type() == Type.IDENT) {
                        alias = t.get(k + 2).text();
                        k += 2;
                    }
                    importedNames.put(alias, mod + "." + name);
                }
            } else if (t.size() >= 6 && t.get(0).type() == Type.IDENT && t.get(1).is("=")
                    && t.get(2).type() == Type.IDENT && t.get(3).is(".")
                    && t.get(4).type() == Type.IDENT && t.get(5).is("(")) {
                String mod = resolveModule(t.get(2).text());
                if (mod != null) {
                    String qualified = mod + "." + t.get(4).text();
                    if (LanguageSpec.NATIVE_CLASSES.containsKey(qualified)) {
                        variableClasses.put(t.get(0).text(), qualified);
                    }
                }
            } else if (t.size() >= 4 && t.get(0).type() == Type.IDENT && t.get(1).is("=")
                    && t.get(2).type() == Type.IDENT && t.get(3).is("(")
                    && info.classes().containsKey(t.get(2).text())) {
                variableClasses.put(t.get(0).text(), t.get(2).text());
            }
        }
    }

    /** Collects methods, constructor parameters and self.x fields of user classes. */
    private void analyzeClasses() {
        ctorParams.clear();
        methodParams.clear();
        classMethodNames.clear();
        classFields.clear();

        int n = lines.size();
        lineClass = new String[n];
        String cur = null;
        int curIndent = 0;
        int bodyIndent = -1;

        for (int i = 0; i < n; i++) {
            lineClass[i] = cur;
            if (lineStart[i] != PyLexer.State.NONE) continue;

            List<Token> t = PyLexer.significant(lineTokens.get(i));
            if (t.isEmpty()) continue;

            int ind = indentOf(lines.get(i));
            if (cur != null && ind <= curIndent) { cur = null; bodyIndent = -1; }

            if (t.get(0).is("class") && t.size() > 1 && t.get(1).type() == Type.IDENT) {
                cur = t.get(1).text();
                curIndent = ind;
                bodyIndent = -1;
                classMethodNames.computeIfAbsent(cur, k -> new TreeSet<>());
                classFields.computeIfAbsent(cur, k -> new TreeSet<>());
                lineClass[i] = cur;
                continue;
            }

            lineClass[i] = cur;
            if (cur == null) continue;
            if (bodyIndent < 0) bodyIndent = ind;

            if (t.get(0).is("def") && t.size() > 2 && t.get(1).type() == Type.IDENT && t.get(2).is("(")) {
                String m = t.get(1).text();
                List<String> params = paramNames(t, 3);
                classMethodNames.get(cur).add(m);
                methodParams.put(m, params);
                if (m.equals("__init__")) ctorParams.put(cur, params);
            } else if (ind == bodyIndent && t.size() > 1 && t.get(0).type() == Type.IDENT && t.get(1).is("=")) {
                classFields.get(cur).add(t.get(0).text()); // class level attribute
            }

            for (int k = 0; k + 3 < t.size(); k++) {
                if (t.get(k).is("self") && t.get(k + 1).is(".") && t.get(k + 2).type() == Type.IDENT) {
                    String op = t.get(k + 3).text();
                    boolean assign = op.endsWith("=") && !op.equals("==") && !op.equals("!=")
                            && !op.equals("<=") && !op.equals(">=");
                    if (assign) classFields.get(cur).add(t.get(k + 2).text());
                }
            }
        }
    }

    /** Parameter names (without self) of a def; "from" is the index right after the '('. */
    private static List<String> paramNames(List<Token> t, int from) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        for (int i = from; i < t.size(); i++) {
            Token x = t.get(i);
            if (x.type() == Type.BRACKET) {
                char c = x.text().charAt(0);
                if (c == '(' || c == '[' || c == '{') depth++;
                else { if (depth == 0) break; depth--; }
                continue;
            }
            if (depth == 0 && x.type() == Type.IDENT && i > 0) {
                Token p = t.get(i - 1);
                if ((p.is("(") || p.is(",") || p.is("*") || p.is("**")) && !x.text().equals("self")) {
                    out.add(x.text());
                }
            }
        }
        return out;
    }

    private String resolveModule(String name) {
        String m = moduleAliases.get(name);
        if (m != null) return m;
        return LanguageSpec.PRELOADED_MODULES.contains(name) ? name : null;
    }

    // =====================================================================
    // RENDERING
    // =====================================================================
    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Font f = font();
        int x0 = getX();
        int y0 = getY();
        int gut = gutter();
        int vh = viewH();
        boolean active = isFocused() && !readOnly;

        hoveredHelpWord = hasControlDown() ? getHelpWordAt(mouseX, mouseY) : null;

        g.fill(x0, y0, x0 + width, y0 + height, C_BG);

        int first = Math.max(0, Math.floorDiv(scrollY - 5, LH));
        int last = Math.min(lines.size() - 1, Math.floorDiv(scrollY + vh - 5, LH));

        // ---- current line border ----
        g.enableScissor(x0, y0, x0 + width - SB, y0 + vh);
        if (active && !hasSelection()) {
            int ly = lineTop(cursorLine);
            g.fill(x0, ly - 1, x0 + width - SB, ly, C_LINE_HL);
            g.fill(x0, ly + LH, x0 + width - SB, ly + LH + 1, C_LINE_HL);
        }
        g.disableScissor();

        // ---- gutter ----
        g.enableScissor(x0, y0, x0 + gut, y0 + vh);
        for (int i = first; i <= last; i++) {
            String num = String.valueOf(i + 1);
            int color = (i == cursorLine && !readOnly) ? C_GUTTER_NUM_ACTIVE : C_GUTTER_NUM;
            g.drawString(f, num, x0 + gut - 10 - f.width(num), lineTop(i), color, false);
        }
        g.disableScissor();

        // ---- text area ----
        g.enableScissor(x0 + gut, y0, x0 + width - SB, y0 + vh);

        g.pose().pushPose();
        g.pose().translate(0, 0, 10);
        drawSelection(g, f, first, last);
        drawIndentGuides(g, f, first, last);
        g.pose().popPose();

        g.pose().pushPose();
        g.pose().translate(0, 0, 50);
        int clipL = x0 + gut;
        int clipR = x0 + width - SB;
        for (int i = first; i <= last; i++) {
            drawLine(g, f, i, textOrigin(), lineTop(i), clipL, clipR);
        }
        g.pose().popPose();

        g.pose().pushPose();
        g.pose().translate(0, 0, 100);
        if (active) drawBracketMatch(g, f);
        if (active && ((System.currentTimeMillis() - blinkBase) / 500) % 2 == 0) {
            int cx = xOf(cursorLine, cursorColumn);
            int cy = lineTop(cursorLine);
            g.fill(cx, cy - 1, cx + 1, cy + LH, C_CURSOR);
        }
        g.pose().popPose();

        g.disableScissor();

        drawScrollbars(g, mouseX, mouseY);

        // ---- popups ----
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        if (active) {
            if (autocompleteOpen && !suggestions.isEmpty()) drawAutocomplete(g, f, mouseX, mouseY);
            else drawSignatureHelp(g, f);
        }
        g.pose().popPose();
    }

    private void drawSelection(GuiGraphics g, Font f, int first, int last) {
        if (!hasSelection()) return;
        int[] s = normalizedSelection();
        int color = isFocused() ? C_SEL : C_SEL_INACTIVE;

        for (int i = Math.max(first, s[0]); i <= Math.min(last, s[2]); i++) {
            String text = lines.get(i);
            int from = i == s[0] ? s[1] : 0;
            int to = i == s[2] ? s[3] : text.length();
            int x1 = textOrigin() + f.width(text.substring(0, Math.min(from, text.length())));
            int x2 = textOrigin() + f.width(text.substring(0, Math.min(to, text.length())));
            if (i < s[2]) x2 += 4; // selected line break
            int y = lineTop(i);
            g.fill(x1, y - 1, x2, y + LH, color);
        }
    }

    private void drawIndentGuides(GuiGraphics g, Font f, int first, int last) {
        int space = f.width(INDENT) / TAB;
        if (space <= 0) space = 4;

        // active guide: the block the cursor is in
        int activeLevel = -1;
        int from = -1;
        int to = -1;
        if (!readOnly && cursorLine < lineIndent.length) {
            boolean opener = lines.get(cursorLine).stripTrailing().endsWith(":");
            int curLevel = lineIndent[cursorLine] / TAB;
            int lvl = opener ? curLevel : curLevel - 1;
            if (lvl >= 0) {
                int minIndent = (lvl + 1) * TAB;
                from = cursorLine;
                to = cursorLine;
                for (int j = cursorLine + 1; j < lines.size() && lineIndent[j] >= minIndent; j++) to = j;
                for (int j = cursorLine - 1; j >= 0 && lineIndent[j] >= minIndent; j--) from = j;
                activeLevel = lvl;
            }
        }

        for (int i = first; i <= last; i++) {
            int levels = lineIndent[i] / TAB;
            int y = lineTop(i);
            for (int k = 0; k < levels; k++) {
                boolean act = k == activeLevel && i >= from && i <= to;
                int x = textOrigin() + k * TAB * space;
                g.fill(x, y - 1, x + 1, y + LH, act ? C_GUIDE_ACTIVE : C_GUIDE);
            }
        }
    }

    private void drawLine(GuiGraphics g, Font f, int lineIdx, int x, int y, int clipL, int clipR) {
        List<Token> toks = lineTokens.get(lineIdx);

        for (int ti = 0; ti < toks.size(); ti++) {
            Token t = toks.get(ti);
            int w = f.width(t.text());

            if (t.type() != Type.WHITESPACE && x + w > clipL && x < clipR) {
                g.drawString(f, t.text(), x, y, colorFor(toks, ti, lineIdx), false);

                if (hoveredHelpWord != null && hoveredHelpWord.line() == lineIdx
                        && hoveredHelpWord.startColumn() == t.start()
                        && hoveredHelpWord.endColumn() == t.end()) {
                    g.fill(x, y + f.lineHeight - 1, x + w, y + f.lineHeight, 0xFF3399FF);
                }
            }
            x += w;
        }
    }

    private void drawBracketMatch(GuiGraphics g, Font f) {
        long k = -1;
        for (int c : new int[]{cursorColumn - 1, cursorColumn}) {
            if (c >= 0 && bracketPair.containsKey(key(cursorLine, c))) { k = key(cursorLine, c); break; }
        }
        if (k < 0) return;

        for (long kk : new long[]{k, bracketPair.get(k)}) {
            int line = (int) (kk >> 20);
            int col = (int) (kk & 0xFFFFF);
            if (line < 0 || line >= lines.size() || col >= lines.get(line).length()) continue;
            int x = xOf(line, col);
            int w = f.width(String.valueOf(lines.get(line).charAt(col)));
            int y = lineTop(line);
            g.fill(x, y - 1, x + w, y + LH, C_BRACKET_MATCH_FILL);
            g.renderOutline(x, y - 1, w, LH + 1, C_BRACKET_MATCH_BORDER);
        }
    }

    private void drawScrollbars(GuiGraphics g, int mx, int my) {
        int[] v = vThumb();
        if (v != null) {
            int x = getX() + width - SB;
            boolean hover = mx >= x && mx <= x + SB && my >= v[0] && my <= v[0] + v[1];
            int color = draggingV ? 0x66BFBFBF : hover ? 0xB3646464 : 0x66797979;
            g.fill(x + 1, v[0], x + SB - 1, v[0] + v[1], color);
        }
        int[] h = hThumb();
        if (h != null) {
            int y = getY() + height - SB;
            boolean hover = my >= y && my <= y + SB && mx >= h[0] && mx <= h[0] + h[1];
            int color = draggingH ? 0x66BFBFBF : hover ? 0xB3646464 : 0x66797979;
            g.fill(h[0], y + 1, h[0] + h[1], y + SB - 1, color);
        }
    }

    private int[] vThumb() {
        int max = maxScrollY();
        if (max <= 0) return null;
        int vh = viewH();
        int contentH = max + vh;
        int thumbH = Math.max(SB_MIN_THUMB, (int) ((long) vh * vh / contentH));
        int travel = Math.max(1, vh - thumbH);
        int thumbY = getY() + (int) ((long) scrollY * travel / max);
        return new int[]{thumbY, thumbH, travel};
    }

    private int[] hThumb() {
        int max = maxScrollX();
        if (max <= 0) return null;
        int vw = viewW();
        int contentW = max + vw;
        int thumbW = Math.max(SB_MIN_THUMB, (int) ((long) vw * vw / contentW));
        int travel = Math.max(1, vw - thumbW);
        int thumbX = textLeft() + (int) ((long) scrollX * travel / max);
        return new int[]{thumbX, thumbW, travel};
    }

    // =====================================================================
    // SYNTAX COLOURS
    // =====================================================================
    private int colorFor(List<Token> toks, int i, int lineIdx) {
        Token t = toks.get(i);
        return switch (t.type()) {
            case COMMENT -> C_COMMENT;
            case STRING -> C_STRING;
            case NUMBER -> C_NUMBER;
            case BRACKET -> {
                Integer d = bracketDepth.get(key(lineIdx, t.start()));
                yield (d == null || d < 0) ? C_BRACKET_UNMATCHED : C_BRACKETS[d % C_BRACKETS.length];
            }
            case IDENT -> identifierColor(toks, i);
            default -> C_TEXT;
        };
    }

    private static int prevSig(List<Token> toks, int i) {
        for (int j = i - 1; j >= 0; j--) if (toks.get(j).type() != Type.WHITESPACE) return j;
        return -1;
    }

    private static int nextSig(List<Token> toks, int i) {
        for (int j = i + 1; j < toks.size(); j++) if (toks.get(j).type() != Type.WHITESPACE) return j;
        return -1;
    }

    private static String textAt(List<Token> toks, int i) { return i < 0 ? "" : toks.get(i).text(); }

    private String receiverBefore(List<Token> toks, int i) {
        int dot = prevSig(toks, i);
        if (dot < 0 || !toks.get(dot).is(".")) return null;
        int recv = prevSig(toks, dot);
        return (recv >= 0 && toks.get(recv).type() == Type.IDENT) ? toks.get(recv).text() : null;
    }

    private int identifierColor(List<Token> toks, int i) {
        String w = toks.get(i).text();

        if (LanguageSpec.CONTROL_KEYWORDS.contains(w)) return C_KEYWORD_CTRL;
        if (LanguageSpec.DECLARATION_KEYWORDS.contains(w)) return C_KEYWORD;
        if (w.equals("self")) return C_KEYWORD;

        String p = textAt(toks, prevSig(toks, i));
        String n = textAt(toks, nextSig(toks, i));

        if (p.equals("def")) return C_FUNCTION;
        if (p.equals("class")) return C_CLASS;

        if ((p.equals("import") || p.equals("from")) && LanguageSpec.MODULES.contains(w)) return C_CLASS;

        // member access:  math.sqrt   hud.Label   label.text
        if (p.equals(".")) {
            String recv = receiverBefore(toks, i);
            String mod = recv == null ? null : resolveModule(recv);
            if (mod != null) {
                String q = mod + "." + w;
                if (LanguageSpec.NATIVE_CLASSES.containsKey(q)) return C_CLASS;
                if (LanguageSpec.BUILTIN_FUNCTIONS.containsKey(q)) return C_FUNCTION;
                if (LanguageSpec.MODULE_CONSTANTS.getOrDefault(mod, List.of()).contains(w)) return C_CONSTANT;
            }
            return n.equals("(") ? C_FUNCTION : C_VARIABLE;
        }

        if (n.equals(".") && resolveModule(w) != null) return C_CLASS;

        String imported = importedNames.get(w);
        if (imported != null) {
            if (LanguageSpec.NATIVE_CLASSES.containsKey(imported)) return C_CLASS;
            if (LanguageSpec.BUILTIN_FUNCTIONS.containsKey(imported)) return C_FUNCTION;
            return C_CONSTANT;
        }

        if (info.classes().containsKey(w) || LanguageSpec.BUILTIN_TYPES.contains(w)) return C_CLASS;
        if (info.functions().containsKey(w) || LanguageSpec.GLOBAL_FUNCTIONS.containsKey(w)) return C_FUNCTION;
        if (n.equals("(")) return C_FUNCTION;
        if (isConstantName(w)) return C_CONSTANT;

        return C_VARIABLE;
    }

    private static boolean isConstantName(String w) {
        if (w.length() < 2) return false;
        boolean letter = false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (Character.isLetter(c)) { if (!Character.isUpperCase(c)) return false; letter = true; }
            else if (!Character.isDigit(c) && c != '_') return false;
        }
        return letter;
    }

    // =====================================================================
    // SELECTION / TEXT HELPERS
    // =====================================================================
    private boolean hasSelection() {
        return selLine != -1 && (selLine != cursorLine || selColumn != cursorColumn);
    }

    private void clearSelection() { selLine = -1; selColumn = -1; }

    private void startSelection() {
        if (selLine == -1) { selLine = cursorLine; selColumn = cursorColumn; }
    }

    private int[] normalizedSelection() {
        int sl = selLine, sc = selColumn, el = cursorLine, ec = cursorColumn;
        if (sl > el || (sl == el && sc > ec)) {
            int tl = sl, tc = sc;
            sl = el; sc = ec; el = tl; ec = tc;
        }
        return new int[]{sl, sc, el, ec};
    }

    private String textBetween(int sl, int sc, int el, int ec) {
        if (sl == el) return lines.get(sl).substring(sc, ec);
        StringBuilder sb = new StringBuilder(lines.get(sl).substring(sc));
        for (int i = sl + 1; i < el; i++) sb.append('\n').append(lines.get(i));
        sb.append('\n').append(lines.get(el), 0, ec);
        return sb.toString();
    }

    private String getSelectedText() {
        if (!hasSelection()) return "";
        int[] s = normalizedSelection();
        return textBetween(s[0], s[1], s[2], s[3]);
    }

    private static boolean isWordChar(char c) { return Character.isLetterOrDigit(c) || c == '_'; }

    private void setCursor(int line, int col, boolean extend) {
        if (extend) startSelection(); else clearSelection();
        cursorLine = clamp(line, 0, lines.size() - 1);
        cursorColumn = clamp(col, 0, lines.get(cursorLine).length());
        desiredColumn = -1;
        closeAutocomplete();
        ensureCursorVisible();
        resetBlink();
    }

    private int[] wordLeft(int l, int c) {
        if (c == 0) return l > 0 ? new int[]{l - 1, lines.get(l - 1).length()} : new int[]{0, 0};
        String s = lines.get(l);
        c = Math.min(c, s.length());
        while (c > 0 && s.charAt(c - 1) == ' ') c--;
        if (c == 0) return new int[]{l, 0};
        boolean w = isWordChar(s.charAt(c - 1));
        while (c > 0 && s.charAt(c - 1) != ' ' && isWordChar(s.charAt(c - 1)) == w) c--;
        return new int[]{l, c};
    }

    private int[] wordRight(int l, int c) {
        String s = lines.get(l);
        if (c >= s.length()) return l < lines.size() - 1 ? new int[]{l + 1, 0} : new int[]{l, s.length()};
        while (c < s.length() && s.charAt(c) == ' ') c++;
        if (c >= s.length()) return new int[]{l, s.length()};
        boolean w = isWordChar(s.charAt(c));
        while (c < s.length() && s.charAt(c) != ' ' && isWordChar(s.charAt(c)) == w) c++;
        return new int[]{l, c};
    }

    private void moveHorizontal(int dir, boolean extend, boolean byWord) {
        if (!extend && hasSelection() && !byWord) {
            int[] s = normalizedSelection();
            if (dir < 0) setCursor(s[0], s[1], false); else setCursor(s[2], s[3], false);
            return;
        }
        int l = cursorLine, c = cursorColumn;
        if (byWord) {
            int[] p = dir < 0 ? wordLeft(l, c) : wordRight(l, c);
            l = p[0]; c = p[1];
        } else if (dir < 0) {
            if (c > 0) c--; else if (l > 0) { l--; c = lines.get(l).length(); }
        } else {
            if (c < lines.get(l).length()) c++; else if (l < lines.size() - 1) { l++; c = 0; }
        }
        setCursor(l, c, extend);
    }

    private void moveVertical(int delta, boolean extend) {
        int desired = desiredColumn >= 0 ? desiredColumn : cursorColumn;
        int target = cursorLine + delta;
        if (target < 0) setCursor(0, 0, extend);
        else if (target >= lines.size()) setCursor(lines.size() - 1, lines.get(lines.size() - 1).length(), extend);
        else setCursor(target, Math.min(desired, lines.get(target).length()), extend);
        desiredColumn = desired;
    }

    private void selectAll() {
        selLine = 0;
        selColumn = 0;
        cursorLine = lines.size() - 1;
        cursorColumn = lines.get(cursorLine).length();
        closeAutocomplete();
        resetBlink();
    }

    private void selectWordAt(int line, int col) {
        String s = lines.get(line);
        if (s.isEmpty()) return;
        col = clamp(col, 0, s.length());
        int idx = col < s.length() ? col : col - 1;
        if (idx > 0 && !isWordChar(s.charAt(idx)) && isWordChar(s.charAt(idx - 1))) idx--;
        boolean w = isWordChar(s.charAt(idx));
        boolean sp = s.charAt(idx) == ' ';
        int a = idx, b = idx + 1;
        while (a > 0 && (s.charAt(a - 1) == ' ') == sp && (sp || isWordChar(s.charAt(a - 1)) == w)) a--;
        while (b < s.length() && (s.charAt(b) == ' ') == sp && (sp || isWordChar(s.charAt(b)) == w)) b++;
        selLine = line;
        selColumn = a;
        cursorLine = line;
        cursorColumn = b;
        resetBlink();
    }

    // =====================================================================
    // EDIT PIPELINE
    // =====================================================================
    private Snapshot snapshot() {
        return new Snapshot(new ArrayList<>(lines), cursorLine, cursorColumn, selLine, selColumn);
    }

    private void recordUndo(int kind) {
        long now = System.currentTimeMillis();
        boolean merge = kind != KIND_NONE && kind == lastEditKind
                && now - lastEditTime < 1000 && !undoStack.isEmpty() && !hasSelection();
        if (!merge) {
            undoStack.push(snapshot());
            while (undoStack.size() > 200) undoStack.removeLast();
        }
        redoStack.clear();
        lastEditKind = kind;
        lastEditTime = now;
    }

    private void restore(Snapshot s) {
        lines.clear();
        lines.addAll(s.lines());
        cursorLine = s.cl();
        cursorColumn = s.cc();
        selLine = s.sl();
        selColumn = s.sc();
        lastEditKind = KIND_NONE;
        closeAutocomplete();
        afterEdit();
    }

    private void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(snapshot());
        restore(undoStack.pop());
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(snapshot());
        restore(redoStack.pop());
    }

    private void afterEdit() {
        desiredColumn = -1;
        rescan();
        ensureCursorVisible();
        resetBlink();
    }

    /** The one place that mutates the text. Cursor ends up after the inserted text. */
    private void replaceRange(int sl, int sc, int el, int ec, String text) {
        String firstPart = lines.get(sl).substring(0, sc);
        String lastPart = lines.get(el).substring(ec);
        String[] parts = text.split("\n", -1);

        for (int i = el; i > sl; i--) lines.remove(i);

        if (parts.length == 1) {
            lines.set(sl, firstPart + parts[0] + lastPart);
            cursorLine = sl;
            cursorColumn = firstPart.length() + parts[0].length();
        } else {
            lines.set(sl, firstPart + parts[0]);
            for (int i = 1; i < parts.length - 1; i++) lines.add(sl + i, parts[i]);
            lines.add(sl + parts.length - 1, parts[parts.length - 1] + lastPart);
            cursorLine = sl + parts.length - 1;
            cursorColumn = parts[parts.length - 1].length();
        }

        clearSelection();
        afterEdit();
    }

    private void insertAtCursor(String text, int kind) {
        recordUndo(hasSelection() ? KIND_NONE : kind);
        int[] s = hasSelection()
                ? normalizedSelection()
                : new int[]{cursorLine, cursorColumn, cursorLine, cursorColumn};
        replaceRange(s[0], s[1], s[2], s[3], text);
    }

    private void deleteSelection() {
        if (!hasSelection()) return;
        recordUndo(KIND_NONE);
        int[] s = normalizedSelection();
        replaceRange(s[0], s[1], s[2], s[3], "");
    }

    private int[] selectedLineRange() {
        if (hasSelection()) {
            int[] s = normalizedSelection();
            int l2 = s[2];
            if (s[2] > s[0] && s[3] == 0) l2--;
            return new int[]{s[0], l2};
        }
        return new int[]{cursorLine, cursorLine};
    }

    // ---- clipboard ----
    private void copySelection() {
        String t;
        if (hasSelection()) {
            t = getSelectedText();
            lastLineCopy = null;
        } else {
            t = lines.get(cursorLine) + "\n";
            lastLineCopy = t;
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(t);
    }

    private void cutSelection() {
        copySelection();
        if (hasSelection()) deleteSelection(); else deleteLines(cursorLine, cursorLine);
    }

    private void paste() {
        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        if (clip == null || clip.isEmpty()) return;
        clip = clip.replace("\r\n", "\n").replace('\r', '\n').replace("\t", INDENT);

        if (!hasSelection() && lastLineCopy != null && clip.equals(lastLineCopy)) {
            int col = cursorColumn;
            recordUndo(KIND_NONE);
            replaceRange(cursorLine, 0, cursorLine, 0, clip);
            cursorColumn = Math.min(col, lines.get(cursorLine).length());
            ensureCursorVisible();
        } else {
            insertAtCursor(clip, KIND_NONE);
        }
        closeAutocomplete();
    }

    // ---- line operations ----
    private void deleteLines(int l1, int l2) {
        recordUndo(KIND_NONE);
        for (int i = l2; i >= l1; i--) lines.remove(i);
        if (lines.isEmpty()) lines.add("");
        cursorLine = Math.min(l1, lines.size() - 1);
        cursorColumn = Math.min(cursorColumn, lines.get(cursorLine).length());
        clearSelection();
        afterEdit();
    }

    private void moveLines(int delta) {
        int[] r = selectedLineRange();
        if (delta < 0 && r[0] == 0) return;
        if (delta > 0 && r[1] == lines.size() - 1) return;
        recordUndo(KIND_NONE);
        List<String> block = new ArrayList<>(lines.subList(r[0], r[1] + 1));
        for (int i = 0; i < block.size(); i++) lines.remove(r[0]);
        lines.addAll(r[0] + delta, block);
        cursorLine += delta;
        if (selLine != -1) selLine += delta;
        closeAutocomplete();
        afterEdit();
    }

    private void duplicateLines(boolean down) {
        int[] r = selectedLineRange();
        recordUndo(KIND_NONE);
        List<String> block = new ArrayList<>(lines.subList(r[0], r[1] + 1));
        if (down) {
            lines.addAll(r[1] + 1, block);
            cursorLine += block.size();
            if (selLine != -1) selLine += block.size();
        } else {
            lines.addAll(r[0], block);
        }
        closeAutocomplete();
        afterEdit();
    }

    private void insertLineBelow() {
        setCursor(cursorLine, lines.get(cursorLine).length(), false);
        handleEnter();
    }

    private void insertLineAbove() {
        recordUndo(KIND_NONE);
        String indent = leadingSpaces(lines.get(cursorLine));
        int l = cursorLine;
        replaceRange(l, 0, l, 0, indent + "\n");
        cursorLine = l;
        cursorColumn = indent.length();
        ensureCursorVisible();
    }

    private void indentSelection(boolean outdent) {
        int[] r = selectedLineRange();
        boolean sel = hasSelection();
        recordUndo(KIND_NONE);

        int[] shift = new int[r[1] - r[0] + 1];
        for (int l = r[0]; l <= r[1]; l++) {
            String s = lines.get(l);
            if (outdent) {
                int lead = leadingSpaces(s).length();
                int rm = lead == 0 ? 0 : (lead % TAB == 0 ? TAB : lead % TAB);
                lines.set(l, s.substring(rm));
                shift[l - r[0]] = -rm;
            } else if (!s.isEmpty()) {
                lines.set(l, INDENT + s);
                shift[l - r[0]] = TAB;
            }
        }

        if (cursorLine >= r[0] && cursorLine <= r[1]) cursorColumn = Math.max(0, cursorColumn + shift[cursorLine - r[0]]);
        if (sel && selLine >= r[0] && selLine <= r[1]) selColumn = Math.max(0, selColumn + shift[selLine - r[0]]);
        closeAutocomplete();
        afterEdit();
    }

    private void toggleComment() {
        int[] r = selectedLineRange();
        boolean any = false;
        boolean all = true;
        int minIndent = Integer.MAX_VALUE;

        for (int l = r[0]; l <= r[1]; l++) {
            String s = lines.get(l);
            if (s.isBlank()) continue;
            any = true;
            int ind = leadingSpaces(s).length();
            minIndent = Math.min(minIndent, ind);
            if (!s.substring(ind).startsWith("#")) all = false;
        }
        if (!any) return;

        recordUndo(KIND_NONE);
        int n = r[1] - r[0] + 1;
        int[] hashPos = new int[n];
        int[] removed = new int[n];
        boolean sel = hasSelection();

        for (int l = r[0]; l <= r[1]; l++) {
            String s = lines.get(l);
            if (s.isBlank()) continue;
            int ind = leadingSpaces(s).length();
            if (all) {
                int rm = (s.length() > ind + 1 && s.charAt(ind + 1) == ' ') ? 2 : 1;
                lines.set(l, s.substring(0, ind) + s.substring(ind + rm));
                hashPos[l - r[0]] = ind;
                removed[l - r[0]] = rm;
            } else {
                lines.set(l, s.substring(0, minIndent) + "# " + s.substring(minIndent));
                hashPos[l - r[0]] = minIndent;
                removed[l - r[0]] = -2;
            }
        }

        cursorColumn = shiftComment(cursorLine, cursorColumn, r, hashPos, removed);
        if (sel) selColumn = shiftComment(selLine, selColumn, r, hashPos, removed);
        closeAutocomplete();
        afterEdit();
    }

    private int shiftComment(int line, int col, int[] r, int[] hashPos, int[] removed) {
        if (line < r[0] || line > r[1]) return col;
        int i = line - r[0];
        if (removed[i] == 0) return col;
        if (removed[i] < 0) return col >= hashPos[i] ? col + 2 : col;
        return col > hashPos[i] ? Math.max(hashPos[i], col - removed[i]) : col;
    }

    // ---- smart typing ----
    private static char closerOf(char c) {
        return switch (c) { case '(' -> ')'; case '[' -> ']'; case '{' -> '}'; default -> c; };
    }

    private static boolean isPair(char l, char r) {
        return (l == '(' && r == ')') || (l == '[' && r == ']') || (l == '{' && r == '}')
                || (l == '"' && r == '"') || (l == '\'' && r == '\'');
    }

    private static boolean shouldAutoClose(char next) {
        return next == 0 || Character.isWhitespace(next) || ")]},:;".indexOf(next) >= 0;
    }

    private static boolean isStringPrefixAt(String line, int col) {
        int k = col;
        int cnt = 0;
        while (k > 0 && cnt < 2 && "rRbBuUfF".indexOf(line.charAt(k - 1)) >= 0) { k--; cnt++; }
        return cnt > 0 && (k == 0 || !isWordChar(line.charAt(k - 1)));
    }

    private boolean surround(char open, char close) {
        int[] s = normalizedSelection();
        String sel = getSelectedText();
        recordUndo(KIND_NONE);
        replaceRange(s[0], s[1], s[2], s[3], open + sel + close);
        selLine = s[0];
        selColumn = s[1] + 1;
        cursorColumn -= 1;
        return true;
    }

    private boolean typeOpener(char c, char next) {
        if (hasSelection()) return surround(c, closerOf(c));
        if (shouldAutoClose(next)) {
            insertAtCursor("" + c + closerOf(c), KIND_NONE);
            cursorColumn--;
        } else {
            insertAtCursor(String.valueOf(c), KIND_NONE);
        }
        closeAutocomplete();
        return true;
    }

    private boolean typeQuote(char c, String line, int col, char next) {
        if (hasSelection()) return surround(c, c);

        boolean inside = insideStringOrComment();
        if (inside && next == c) {
            setCursor(cursorLine, col + 1, false);
            return true;
        }

        if (!inside) {
            char prev = col > 0 ? line.charAt(col - 1) : 0;
            boolean prevWord = isWordChar(prev) && !isStringPrefixAt(line, col);
            boolean triple = col >= 2 && line.charAt(col - 1) == c && line.charAt(col - 2) == c;
            if (!prevWord && !triple && shouldAutoClose(next)) {
                insertAtCursor("" + c + c, KIND_NONE);
                cursorColumn--;
                closeAutocomplete();
                return true;
            }
        }

        insertAtCursor(String.valueOf(c), KIND_NONE);
        closeAutocomplete();
        return true;
    }

    /** typing ':' after else / elif / except / finally aligns the line with its if / try. */
    private void autoDedentKeywordLine() {
        String line = lines.get(cursorLine);
        if (cursorColumn != line.length()) return;

        String trimmed = line.trim();
        String firstWord = trimmed.split("[\\s:(]", 2)[0];
        if (!LanguageSpec.DEDENT_ON_COLON.contains(firstWord)) return;

        int indent = leadingSpaces(line).length();
        Set<String> partners = Set.of("if", "elif", "for", "while", "try", "except", "else");

        for (int i = cursorLine - 1; i >= 0; i--) {
            if (lineStart[i] != PyLexer.State.NONE) continue;
            String l = lines.get(i);
            if (l.isBlank() || l.trim().startsWith("#")) continue;

            int ind = leadingSpaces(l).length();
            String w = l.trim().split("[\\s:(]", 2)[0];
            boolean partner = partners.contains(w);

            if (ind > indent) continue;
            if (ind == indent) { if (partner) return; continue; }
            if (!partner) return;

            lines.set(cursorLine, " ".repeat(ind) + trimmed);
            cursorColumn = lines.get(cursorLine).length();
            afterEdit();
            return;
        }
    }

    private void handleEnter() {
        recordUndo(KIND_NONE);
        if (hasSelection()) {
            int[] s = normalizedSelection();
            replaceRange(s[0], s[1], s[2], s[3], "");
        }

        String line = lines.get(cursorLine);
        int col = Math.min(cursorColumn, line.length());
        String before = line.substring(0, col);
        String baseIndent = leadingSpaces(line);

        List<Token> bt = PyLexer.significant(PyLexer.lex(before, lineStart[cursorLine]).tokens());
        String lastTok = bt.isEmpty() ? "" : bt.get(bt.size() - 1).text();
        String firstWord = (!bt.isEmpty() && bt.get(0).type() == Type.IDENT) ? bt.get(0).text() : "";

        boolean hang = lastTok.equals("(") || lastTok.equals("[") || lastTok.equals("{");
        boolean block = lastTok.equals(":") && LanguageSpec.BLOCK_STARTERS.contains(firstWord);

        String indent = baseIndent;
        if (hang || block) {
            indent = baseIndent + INDENT;
        } else if (LanguageSpec.DEDENT_AFTER.contains(firstWord) && !baseIndent.isEmpty()) {
            int rm = baseIndent.length() % TAB == 0 ? TAB : baseIndent.length() % TAB;
            indent = baseIndent.substring(0, baseIndent.length() - rm);
        }

        int skip = 0;
        while (col + skip < line.length() && line.charAt(col + skip) == ' ') skip++;
        String rest = line.substring(col + skip);

        if (hang && !rest.isEmpty() && rest.charAt(0) == closerOf(lastTok.charAt(0))) {
            String inner = baseIndent + INDENT;
            replaceRange(cursorLine, col, cursorLine, col + skip, "\n" + inner + "\n" + baseIndent);
            cursorLine -= 1;
            cursorColumn = inner.length();
            ensureCursorVisible();
        } else {
            replaceRange(cursorLine, col, cursorLine, col + skip, "\n" + indent);
        }
        closeAutocomplete();
    }

    private void handleBackspace(boolean ctrl) {
        boolean wasOpen = autocompleteOpen;

        if (hasSelection()) { deleteSelection(); closeAutocomplete(); return; }

        String line = lines.get(cursorLine);
        int col = Math.min(cursorColumn, line.length());

        if (ctrl) {
            int[] p = wordLeft(cursorLine, col);
            if (p[0] == cursorLine && p[1] == col) return;
            recordUndo(KIND_NONE);
            replaceRange(p[0], p[1], cursorLine, col, "");
            closeAutocomplete();
            return;
        }

        if (col == 0) {
            if (cursorLine == 0) return;
            recordUndo(KIND_NONE);
            replaceRange(cursorLine - 1, lines.get(cursorLine - 1).length(), cursorLine, 0, "");
            closeAutocomplete();
            return;
        }

        if (col < line.length() && isPair(line.charAt(col - 1), line.charAt(col))) {
            recordUndo(KIND_NONE);
            replaceRange(cursorLine, col - 1, cursorLine, col + 1, "");
            closeAutocomplete();
            return;
        }

        int rm = 1;
        if (line.substring(0, col).isBlank()) rm = Math.min(col, col % TAB == 0 ? TAB : col % TAB);

        recordUndo(KIND_DELETE);
        replaceRange(cursorLine, col - rm, cursorLine, col, "");
        if (wasOpen) updateAutocomplete(false);
    }

    private void handleDelete(boolean ctrl) {
        if (hasSelection()) { deleteSelection(); return; }

        String line = lines.get(cursorLine);
        int col = Math.min(cursorColumn, line.length());

        if (ctrl) {
            int[] p = wordRight(cursorLine, col);
            if (p[0] == cursorLine && p[1] == col) return;
            recordUndo(KIND_NONE);
            replaceRange(cursorLine, col, p[0], p[1], "");
        } else if (col < line.length()) {
            recordUndo(KIND_DELETE);
            replaceRange(cursorLine, col, cursorLine, col + 1, "");
        } else if (cursorLine < lines.size() - 1) {
            recordUndo(KIND_NONE);
            replaceRange(cursorLine, col, cursorLine + 1, 0, "");
        }
        closeAutocomplete();
    }

    private void handleTab(boolean shift) {
        if (shift) { indentSelection(true); return; }

        if (hasSelection()) {
            int[] s = normalizedSelection();
            if (s[0] != s[2]) { indentSelection(false); return; }
        }
        int n = TAB - (cursorColumn % TAB);
        insertAtCursor(" ".repeat(n), KIND_NONE);
        closeAutocomplete();
    }

    // =====================================================================
    // KEYBOARD
    // =====================================================================
    private static String keyName(int keyCode, int scanCode) {
        String n = GLFW.glfwGetKeyName(keyCode, scanCode);
        return n == null ? "" : n.toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (readOnly || !isFocused()) return false;
        if (c < 32 || c == 127) return false;

        String line = lines.get(cursorLine);
        int col = Math.min(cursorColumn, line.length());
        char next = col < line.length() ? line.charAt(col) : 0;

        if (!hasSelection() && (c == ')' || c == ']' || c == '}') && next == c) {
            setCursor(cursorLine, col + 1, false);
            return true;
        }
        if (c == '"' || c == '\'') return typeQuote(c, line, col, next);
        if (c == '(' || c == '[' || c == '{') return typeOpener(c, next);

        insertAtCursor(String.valueOf(c), isWordChar(c) ? KIND_TYPE : KIND_NONE);
        if (c == ':') autoDedentKeywordLine();

        if (isWordChar(c) || c == '.') updateAutocomplete(false); else closeAutocomplete();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!isFocused()) return false;

        boolean ctrl = hasControlDown();
        boolean shift = hasShiftDown();
        boolean alt = hasAltDown();
        String name = keyName(keyCode, scanCode);

        if (readOnly) {
            if (ctrl && name.equals("c")) { copySelection(); return true; }
            if (ctrl && name.equals("a")) { selectAll(); return true; }
            return false;
        }

        // ---- suggestion list ----
        if (autocompleteOpen && !suggestions.isEmpty()) {
            if (keyCode == GLFW.GLFW_KEY_UP) {
                autocompleteIndex = (autocompleteIndex - 1 + suggestions.size()) % suggestions.size();
                keepAcVisible();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN) {
                autocompleteIndex = (autocompleteIndex + 1) % suggestions.size();
                keepAcVisible();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_TAB || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                acceptAutocomplete();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) { closeAutocomplete(); return true; }
        }

        // ---- ctrl shortcuts ----
        if (ctrl) {
            if (name.equals("a")) { selectAll(); return true; }
            if (name.equals("c")) { copySelection(); return true; }
            if (name.equals("x")) { cutSelection(); return true; }
            if (name.equals("v")) { paste(); return true; }
            if (name.equals("z")) { if (shift) redo(); else undo(); return true; }
            if (name.equals("y")) { redo(); return true; }
            if (name.equals("k") && shift) {
                int[] r = selectedLineRange();
                deleteLines(r[0], r[1]);
                return true;
            }
            if (name.equals("/") || name.equals("#") || (shift && name.equals("7"))
                    || keyCode == GLFW.GLFW_KEY_SLASH) {
                toggleComment();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_SPACE) { updateAutocomplete(true); return true; }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (shift) insertLineAbove(); else insertLineBelow();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_LEFT) { moveHorizontal(-1, shift, true); return true; }
            if (keyCode == GLFW.GLFW_KEY_RIGHT) { moveHorizontal(1, shift, true); return true; }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) { handleBackspace(true); return true; }
            if (keyCode == GLFW.GLFW_KEY_DELETE) { handleDelete(true); return true; }
            if (keyCode == GLFW.GLFW_KEY_HOME) { setCursor(0, 0, shift); return true; }
            if (keyCode == GLFW.GLFW_KEY_END) {
                setCursor(lines.size() - 1, lines.get(lines.size() - 1).length(), shift);
                return true;
            }
        }

        // ---- alt shortcuts ----
        if (alt && (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN)) {
            if (shift) duplicateLines(keyCode == GLFW.GLFW_KEY_DOWN);
            else moveLines(keyCode == GLFW.GLFW_KEY_UP ? -1 : 1);
            return true;
        }

        // ---- plain keys ----
        switch (keyCode) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> handleEnter();
            case GLFW.GLFW_KEY_TAB -> handleTab(shift);
            case GLFW.GLFW_KEY_BACKSPACE -> handleBackspace(false);
            case GLFW.GLFW_KEY_DELETE -> handleDelete(false);
            case GLFW.GLFW_KEY_LEFT -> moveHorizontal(-1, shift, false);
            case GLFW.GLFW_KEY_RIGHT -> moveHorizontal(1, shift, false);
            case GLFW.GLFW_KEY_UP -> moveVertical(-1, shift);
            case GLFW.GLFW_KEY_DOWN -> moveVertical(1, shift);
            case GLFW.GLFW_KEY_PAGE_UP -> moveVertical(-Math.max(1, viewH() / LH - 1), shift);
            case GLFW.GLFW_KEY_PAGE_DOWN -> moveVertical(Math.max(1, viewH() / LH - 1), shift);
            case GLFW.GLFW_KEY_HOME -> {
                String l = lines.get(cursorLine);
                int firstNonWs = leadingSpaces(l).length();
                setCursor(cursorLine, cursorColumn != firstNonWs ? firstNonWs : 0, shift);
            }
            case GLFW.GLFW_KEY_END -> setCursor(cursorLine, lines.get(cursorLine).length(), shift);
            default -> { }
        }

        return true; // swallow everything while focused (E, number keys ...)
    }

    // =====================================================================
    // MOUSE
    // =====================================================================
    private int[] getLineColumnAt(double mouseX, double mouseY) {
        Font f = font();
        int line = clamp(Math.floorDiv((int) mouseY - getY() - 5 + scrollY, LH), 0, lines.size() - 1);
        String s = lines.get(line);
        int relX = (int) mouseX - textOrigin();

        int col = 0;
        int acc = 0;
        for (int i = 0; i < s.length(); i++) {
            int w = f.width(String.valueOf(s.charAt(i)));
            if (relX < acc + w / 2) break;
            acc += w;
            col = i + 1;
        }
        return new int[]{line, col};
    }

    private boolean overVerticalBar(double mx, double my) {
        return mx >= getX() + width - SB && mx <= getX() + width && my >= getY() && my <= getY() + viewH();
    }

    private boolean overHorizontalBar(double mx, double my) {
        return mx >= textLeft() && mx <= getX() + width - SB && my >= getY() + height - SB && my <= getY() + height;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // ---- scrollbars ----
        if (button == 0) {
            if (overVerticalBar(mouseX, mouseY)) {
                int[] t = vThumb();
                if (t != null) {
                    if (mouseY >= t[0] && mouseY <= t[0] + t[1]) {
                        draggingV = true;
                        scrollbarDragOffset = (int) mouseY - t[0];
                    } else {
                        double pct = (mouseY - getY()) / (double) viewH();
                        scrollY = (int) (pct * maxScrollY());
                        clampScroll();
                    }
                }
                return true;
            }
            if (overHorizontalBar(mouseX, mouseY)) {
                int[] t = hThumb();
                if (t != null) {
                    if (mouseX >= t[0] && mouseX <= t[0] + t[1]) {
                        draggingH = true;
                        scrollbarDragOffset = (int) mouseX - t[0];
                    } else {
                        double pct = (mouseX - textLeft()) / (double) viewW();
                        scrollX = (int) (pct * maxScrollX());
                        clampScroll();
                    }
                }
                return true;
            }
        }

        // ---- suggestion list ----
        if (autocompleteOpen && !suggestions.isEmpty() && button == 0) {
            int idx = autocompleteIndexAt(mouseX, mouseY);
            if (idx >= 0) {
                autocompleteIndex = idx;
                acceptAutocomplete();
                return true;
            }
        }

        // ---- ctrl + click help ----
        if (hasControlDown() && button == 0) {
            HelpWordHit hit = getHelpWordAt(mouseX, mouseY);
            if (hit != null) {
                if (helpCallback != null) helpCallback.accept(HELP_LOOKUP.get(hit.word()));
                return true;
            }
        }

        if (!isMouseOver(mouseX, mouseY)) {
            setFocused(false);
            clearSelection();
            return false;
        }

        setFocused(true);

        if (button != 0) return false;

        int[] pos = getLineColumnAt(mouseX, mouseY);
        long now = System.currentTimeMillis();
        boolean again = now - lastClickTime < 400 && pos[0] == lastClickLine && Math.abs(pos[1] - lastClickCol) <= 1;
        clickCount = again ? clickCount + 1 : 1;
        lastClickTime = now;
        lastClickLine = pos[0];
        lastClickCol = pos[1];

        closeAutocomplete();
        desiredColumn = -1;

        if (clickCount == 2) {
            selectWordAt(pos[0], pos[1]);
            isDragging = false;
        } else if (clickCount >= 3) {
            selLine = pos[0];
            selColumn = 0;
            if (pos[0] < lines.size() - 1) { cursorLine = pos[0] + 1; cursorColumn = 0; }
            else { cursorLine = pos[0]; cursorColumn = lines.get(pos[0]).length(); }
            isDragging = false;
        } else {
            if (hasShiftDown()) startSelection(); else clearSelection();
            cursorLine = pos[0];
            cursorColumn = pos[1];
            isDragging = true;
        }

        ensureCursorVisible();
        resetBlink();
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button != 0) return false;

        if (draggingV) {
            int[] t = vThumb();
            if (t != null) {
                int newTop = clamp((int) mouseY - getY() - scrollbarDragOffset, 0, t[2]);
                scrollY = (int) ((long) newTop * maxScrollY() / Math.max(1, t[2]));
                clampScroll();
            }
            return true;
        }

        if (draggingH) {
            int[] t = hThumb();
            if (t != null) {
                int newLeft = clamp((int) mouseX - textLeft() - scrollbarDragOffset, 0, t[2]);
                scrollX = (int) ((long) newLeft * maxScrollX() / Math.max(1, t[2]));
                clampScroll();
            }
            return true;
        }

        if (!isDragging) return false;

        if (!hasSelection()) startSelection();

        // auto scroll when dragging past the edges
        if (mouseY < getY()) scrollY -= 4;
        else if (mouseY > getY() + viewH()) scrollY += 4;
        if (mouseX < textLeft()) scrollX -= 6;
        else if (mouseX > getX() + width - SB) scrollX += 6;
        clampScroll();

        int[] pos = getLineColumnAt(mouseX, mouseY);
        cursorLine = pos[0];
        cursorColumn = pos[1];
        resetBlink();
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean was = isDragging || draggingV || draggingH;
        if (button == 0) {
            draggingV = false;
            draggingH = false;
            isDragging = false;
            if (selLine == cursorLine && selColumn == cursorColumn) clearSelection();
        }
        return was;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollXd, double scrollYd) {
        if (autocompleteOpen && autocompleteIndexAt(mouseX, mouseY) >= 0) {
            int n = suggestions.size();
            autocompleteIndex = clamp(autocompleteIndex - (int) Math.signum(scrollYd), 0, n - 1);
            keepAcVisible();
            return true;
        }

        if (!isMouseOver(mouseX, mouseY)) return false;

        if (hasShiftDown()) scrollX -= (int) (scrollYd * 30);
        else scrollY -= (int) (scrollYd * LH * 3);

        clampScroll();
        return true;
    }

    // =====================================================================
    // HELP LOOKUP (ctrl + hover)
    // =====================================================================
    private HelpWordHit getHelpWordAt(double mouseX, double mouseY) {
        int relY = (int) mouseY - getY() - 5 + scrollY;
        if (relY < 0) return null;
        int lineIndex = relY / LH;
        if (lineIndex >= lines.size()) return null;
        if (mouseX < textLeft()) return null;

        int[] pos = getLineColumnAt(mouseX, mouseY);
        if (pos[0] != lineIndex) return null;

        // getLineColumnAt rounds to the nearest boundary; use the char under the mouse instead
        String line = lines.get(lineIndex);
        Font f = font();
        int relX = (int) mouseX - textOrigin();
        int acc = 0;
        int column = line.length();
        for (int i = 0; i < line.length(); i++) {
            acc += f.width(String.valueOf(line.charAt(i)));
            if (acc > relX) { column = i; break; }
        }

        for (Token tok : lineTokens.get(lineIndex)) {
            if (tok.type() == Type.IDENT && column >= tok.start() && column < tok.end()
                    && HELP_LOOKUP.containsKey(tok.text())) {
                return new HelpWordHit(tok.text(), lineIndex, tok.start(), tok.end());
            }
        }
        return null;
    }

    // =====================================================================
    // AUTOCOMPLETE
    // =====================================================================
    public boolean isAutocompleteOpen() { return autocompleteOpen; }

    public void closeAutocomplete() {
        autocompleteOpen = false;
        suggestions.clear();
        autocompleteIndex = 0;
        autocompleteTop = 0;
    }

    private void keepAcVisible() {
        if (autocompleteIndex < autocompleteTop) autocompleteTop = autocompleteIndex;
        if (autocompleteIndex >= autocompleteTop + AC_ROWS) autocompleteTop = autocompleteIndex - AC_ROWS + 1;
    }

    private boolean insideStringOrComment() {
        String line = lines.get(cursorLine);
        int col = Math.min(cursorColumn, line.length());
        PyLexer.Result r = PyLexer.lex(line.substring(0, col), lineStart[cursorLine]);
        if (r.endState() != PyLexer.State.NONE) return true;
        if (r.tokens().isEmpty()) return false;
        Token last = r.tokens().get(r.tokens().size() - 1);
        return last.type() == Type.COMMENT || (last.type() == Type.STRING && last.unterminated());
    }

    private String getCurrentWord() {
        String line = lines.get(cursorLine);
        int end = Math.min(cursorColumn, line.length());
        int start = end;
        while (start > 0 && isWordChar(line.charAt(start - 1))) start--;
        autocompleteStartColumn = start;
        return line.substring(start, end);
    }

    private static int findDotBeforeWord(String line, int col) {
        int start = col;
        while (start > 0 && isWordChar(line.charAt(start - 1))) start--;
        return (start > 0 && line.charAt(start - 1) == '.') ? start : -1;
    }

    private static String receiverAt(String line, int wordStart) {
        int end = wordStart - 1;
        int start = end;
        while (start > 0 && isWordChar(line.charAt(start - 1))) start--;
        return start < end ? line.substring(start, end) : null;
    }

    private void updateAutocomplete(boolean force) {
        closeAutocomplete();
        suppressSuffix = false;
        if (readOnly || insideStringOrComment()) return;

        String line = lines.get(cursorLine);
        int col = Math.min(cursorColumn, line.length());
        List<Token> prefix = PyLexer.significant(PyLexer.lex(line.substring(0, col), lineStart[cursorLine]).tokens());
        String firstWord = (!prefix.isEmpty() && prefix.get(0).type() == Type.IDENT) ? prefix.get(0).text() : "";

        // declaring a new name: nothing to suggest
        if (firstWord.equals("def") || firstWord.equals("class")) return;
        if (firstWord.equals("for") && prefix.stream().noneMatch(t -> t.is("in"))) return;

        // import x / from x import y
        if (firstWord.equals("import") || firstWord.equals("from")) {
            String word = getCurrentWord();
            if (word.isEmpty() && !force) return;

            List<Candidate> cands = new ArrayList<>();
            boolean afterImport = firstWord.equals("from") && prefix.stream().anyMatch(t -> t.is("import"));

            if (afterImport && prefix.size() > 1 && LanguageSpec.IMPORTABLE_MODULES.contains(prefix.get(1).text())) {
                String mod = prefix.get(1).text();
                for (String m : LanguageSpec.moduleMembers(mod)) cands.add(memberCandidate(mod, m));
            } else {
                for (String m : new TreeSet<>(LanguageSpec.IMPORTABLE_MODULES)) cands.add(new Candidate(m, Kind.MODULE, "module"));
            }
            suppressSuffix = true;
            filterAndOpen(cands, word);
            return;
        }

        // member access
        int dot = findDotBeforeWord(line, col);
        if (dot != -1) {
            String receiver = receiverAt(line, dot);
            if (receiver == null) return;
            autocompleteStartColumn = dot;
            String word = line.substring(dot, col);

            List<Candidate> cands = new ArrayList<>();
            String mod = resolveModule(receiver);
            if (mod != null) {
                for (String m : LanguageSpec.moduleMembers(mod)) cands.add(memberCandidate(mod, m));
            } else {
                String cls = receiver.equals("self") && cursorLine < lineClass.length
                        ? lineClass[cursorLine] : variableClasses.get(receiver);
                if (cls != null && LanguageSpec.NATIVE_CLASSES.containsKey(cls)) {
                    for (String fld : LanguageSpec.nativeClassFields(cls)) {
                        cands.add(new Candidate(fld, fld.equals("remove") ? Kind.FUNCTION : Kind.PROPERTY, "hud object"));
                    }
                } else if (cls != null) {
                    for (String m : classMethodNames.getOrDefault(cls, Set.of())) {
                        if (!m.startsWith("__")) cands.add(new Candidate(m, Kind.FUNCTION, cls));
                    }
                    for (String fld : classFields.getOrDefault(cls, Set.of())) {
                        cands.add(new Candidate(fld, Kind.PROPERTY, cls));
                    }
                }
            }
            filterAndOpen(cands, word);
            return;
        }

        // normal identifier completion
        String word = getCurrentWord();
        if (word.isEmpty() && !force) return;
        if (!word.isEmpty() && Character.isDigit(word.charAt(0))) return;

        CallContext call = findCallContext(line, col);
        filterAndOpen(buildNormalCandidates(call), word);
    }

    private Candidate memberCandidate(String module, String name) {
        if (LanguageSpec.CLASS_MODULES.getOrDefault(module, Map.of()).containsKey(name)) {
            return new Candidate(name, Kind.CLASS, module);
        }
        if (LanguageSpec.FUNCTION_MODULES.getOrDefault(module, Map.of()).containsKey(name)) {
            return new Candidate(name, Kind.FUNCTION, module);
        }
        return new Candidate(name, Kind.CONSTANT, module);
    }

    private List<Candidate> buildNormalCandidates(CallContext call) {
        Map<String, Candidate> out = new LinkedHashMap<>();

        // keyword arguments of the call we are inside (only for user functions, see flag)
        if (call != null) {
            boolean user = info.functions().containsKey(call.name()) || ctorParams.containsKey(call.name());
            boolean allowed = user || (SUGGEST_NAMED_ARGS_FOR_API && !LanguageSpec.isPositionalOnly(call.name()));
            List<String> params = allowed ? paramsFor(call.name()) : null;
            if (params != null) {
                for (String p : params) out.putIfAbsent(p, new Candidate(p, Kind.PARAM, "parameter"));
            }
        }

        for (String v : variables) out.putIfAbsent(v, new Candidate(v, Kind.VARIABLE, "variable"));
        for (String fn : functions) out.putIfAbsent(fn, new Candidate(fn, Kind.FUNCTION, "def"));
        for (String c : classes) out.putIfAbsent(c, new Candidate(c, Kind.CLASS, "class"));
        for (String fn : LanguageSpec.GLOBAL_FUNCTION_NAMES) {
            Kind k = LanguageSpec.BUILTIN_TYPES.contains(fn) ? Kind.CLASS : Kind.FUNCTION;
            out.putIfAbsent(fn, new Candidate(fn, k, "builtin"));
        }
        for (String m : LanguageSpec.PRELOADED_MODULES) out.putIfAbsent(m, new Candidate(m, Kind.MODULE, "module"));
        for (Map.Entry<String, String> e : moduleAliases.entrySet()) {
            out.putIfAbsent(e.getKey(), new Candidate(e.getKey(), Kind.MODULE, "module"));
        }
        for (Map.Entry<String, String> e : importedNames.entrySet()) {
            String q = e.getValue();
            Kind k = LanguageSpec.NATIVE_CLASSES.containsKey(q) ? Kind.CLASS
                    : LanguageSpec.BUILTIN_FUNCTIONS.containsKey(q) ? Kind.FUNCTION : Kind.CONSTANT;
            out.putIfAbsent(e.getKey(), new Candidate(e.getKey(), k, q));
        }
        for (String kw : LanguageSpec.KEYWORD_NAMES) out.putIfAbsent(kw, new Candidate(kw, Kind.KEYWORD, "keyword"));

        return new ArrayList<>(out.values());
    }

    private void filterAndOpen(List<Candidate> cands, String pattern) {
        List<Suggestion> list = new ArrayList<>();
        for (Candidate c : cands) {
            Suggestion s = score(c, pattern);
            if (s != null) list.add(s);
        }
        if (list.isEmpty()) return;

        list.sort((a, b) -> {
            if (a.score() != b.score()) return Integer.compare(b.score(), a.score());
            if (a.c().kind() != b.c().kind()) return Integer.compare(a.c().kind().ordinal(), b.c().kind().ordinal());
            return a.c().label().compareToIgnoreCase(b.c().label());
        });

        // the only suggestion is exactly what was typed: nothing to complete
        if (list.size() == 1 && list.get(0).c().label().equals(pattern)) return;

        suggestions.clear();
        for (int i = 0; i < Math.min(list.size(), 100); i++) suggestions.add(list.get(i));
        autocompleteIndex = 0;
        autocompleteTop = 0;
        autocompleteOpen = true;
    }

    private static boolean isWordStart(String l, int i) {
        return i == 0 || l.charAt(i - 1) == '_'
                || (Character.isUpperCase(l.charAt(i)) && Character.isLowerCase(l.charAt(i - 1)));
    }

    /** VS Code style fuzzy matching: prefix > word-start substring > word-start subsequence. */
    private static Suggestion score(Candidate c, String pattern) {
        if (pattern.isEmpty()) return new Suggestion(c, new int[0], 0);

        String l = c.label();
        String lp = l.toLowerCase(Locale.ROOT);
        String pp = pattern.toLowerCase(Locale.ROOT);

        if (lp.startsWith(pp)) {
            int[] m = new int[pp.length()];
            for (int i = 0; i < m.length; i++) m[i] = i;
            return new Suggestion(c, m, 2000 - l.length() + (l.startsWith(pattern) ? 50 : 0));
        }

        int idx = lp.indexOf(pp);
        while (idx >= 0) {
            if (isWordStart(l, idx)) {
                int[] m = new int[pp.length()];
                for (int i = 0; i < m.length; i++) m[i] = idx + i;
                return new Suggestion(c, m, 1000 - idx * 10 - l.length());
            }
            idx = lp.indexOf(pp, idx + 1);
        }

        for (int start = 0; start < lp.length(); start++) {
            if (lp.charAt(start) != pp.charAt(0) || !isWordStart(l, start)) continue;
            int[] m = new int[pp.length()];
            int j = 0;
            for (int i = start; i < lp.length() && j < pp.length(); i++) {
                if (lp.charAt(i) == pp.charAt(j)) m[j++] = i;
            }
            if (j == pp.length()) return new Suggestion(c, m, 500 - (m[m.length - 1] - m[0]) - l.length());
        }
        return null;
    }

    private void acceptAutocomplete() {
        if (!autocompleteOpen || suggestions.isEmpty()) return;

        Candidate c = suggestions.get(autocompleteIndex).c();
        String line = lines.get(cursorLine);
        int len = line.length();
        int start = clamp(autocompleteStartColumn, 0, len);
        int end = Math.max(start, Math.min(cursorColumn, len));
        char after = end < len ? line.charAt(end) : 0;

        String suffix = "";
        int back = 0;

        if (!suppressSuffix) {
            switch (c.kind()) {
                case FUNCTION, CLASS -> { if (after != '(') { suffix = "()"; back = 1; } }
                case MODULE -> { if (after != '.') suffix = "."; }
                case PARAM -> { if (after != '=') suffix = "="; }
                default -> { }
            }
        }

        recordUndo(KIND_NONE);
        replaceRange(cursorLine, start, cursorLine, end, c.label() + suffix);
        cursorColumn -= back;
        ensureCursorVisible();
        closeAutocomplete();

        if (c.kind() == Kind.MODULE && suffix.equals(".")) updateAutocomplete(false);
    }

    // ---- popup geometry / drawing ----
    private int[] popupRect() {
        Font f = font();
        int rows = Math.min(AC_ROWS, suggestions.size());
        int w = 170;
        for (Suggestion s : suggestions) {
            int lw = f.width(s.c().label()) + 30 + (s.c().detail().isEmpty() ? 0 : 14 + f.width(s.c().detail()));
            w = Math.max(w, lw);
        }
        w = Math.min(w, 300);

        String line = lines.get(cursorLine);
        int sc = Math.min(autocompleteStartColumn, line.length());
        int x = textOrigin() + f.width(line.substring(0, sc)) - 18;
        int h = rows * AC_ROW_H + 2;
        int y = lineTop(cursorLine) + LH + 2;
        if (y + h > getY() + height) y = lineTop(cursorLine) - h - 2;

        x = Math.max(getX() + 2, Math.min(x, getX() + width - w - 2));
        return new int[]{x, y, w, h, rows};
    }

    private int autocompleteIndexAt(double mx, double my) {
        if (!autocompleteOpen || suggestions.isEmpty()) return -1;
        int[] r = popupRect();
        if (mx < r[0] || mx > r[0] + r[2] || my < r[1] || my > r[1] + r[3]) return -1;
        int row = (int) ((my - r[1] - 1) / AC_ROW_H);
        int idx = autocompleteTop + row;
        return (row >= 0 && row < r[4] && idx < suggestions.size()) ? idx : -1;
    }

    private static int iconColor(Kind k) {
        return switch (k) {
            case FUNCTION -> 0xFFB180D7;
            case CLASS -> 0xFFEE9D28;
            case VARIABLE, PARAM -> 0xFF75BEFF;
            case PROPERTY -> 0xFF75BEFF;
            case CONSTANT -> 0xFF4FC1FF;
            case MODULE -> 0xFFCCCCCC;
            case KEYWORD -> 0xFFAAAAAA;
        };
    }

    private void drawAutocomplete(GuiGraphics g, Font f, int mx, int my) {
        int[] r = popupRect();
        int x = r[0], y = r[1], w = r[2], h = r[3], rows = r[4];

        g.fill(x, y, x + w, y + h, C_POPUP_BG);
        g.renderOutline(x, y, w, h, C_POPUP_BORDER);

        int hover = autocompleteIndexAt(mx, my);

        for (int i = 0; i < rows; i++) {
            int idx = autocompleteTop + i;
            if (idx >= suggestions.size()) break;
            Suggestion s = suggestions.get(idx);
            int ry = y + 1 + i * AC_ROW_H;

            if (idx == autocompleteIndex) g.fill(x + 1, ry, x + w - 1, ry + AC_ROW_H, C_POPUP_SEL);
            else if (idx == hover) g.fill(x + 1, ry, x + w - 1, ry + AC_ROW_H, C_POPUP_HOVER);

            g.fill(x + 5, ry + 3, x + 12, ry + 10, iconColor(s.c().kind()));

            // label with the matching characters highlighted
            String label = s.c().label();
            int lx = x + 18;
            int[] m = s.match();
            int mi = 0;
            int runStart = 0;
            while (runStart < label.length()) {
                boolean hit = mi < m.length && m[mi] == runStart;
                int runEnd = runStart;
                while (runEnd < label.length()) {
                    boolean h2 = mi < m.length && m[mi] == runEnd;
                    if (h2 != hit) break;
                    if (h2) mi++;
                    runEnd++;
                }
                String piece = label.substring(runStart, runEnd);
                g.drawString(f, piece, lx, ry + 2, hit ? C_POPUP_MATCH : C_POPUP_TEXT, false);
                lx += f.width(piece);
                runStart = runEnd;
            }

            String detail = s.c().detail();
            if (!detail.isEmpty()) {
                g.drawString(f, detail, x + w - 6 - f.width(detail), ry + 2, C_POPUP_DETAIL, false);
            }
        }
    }

    // =====================================================================
    // SIGNATURE HELP
    // =====================================================================
    private record CallContext(String name, int argIndex, String namedArg) {}

    private CallContext findCallContext(String line, int col) {
        List<Token> toks = PyLexer.significant(
                PyLexer.lex(line.substring(0, Math.min(col, line.length())), lineStart[cursorLine]).tokens());

        int depth = 0;
        int commas = 0;
        int lastComma = -1;

        for (int i = toks.size() - 1; i >= 0; i--) {
            Token t = toks.get(i);

            if (t.type() == Type.BRACKET) {
                char ch = t.text().charAt(0);
                if (ch == ')' || ch == ']' || ch == '}') {
                    depth++;
                } else if (depth > 0) {
                    depth--;
                } else if (ch == '(' && i > 0 && toks.get(i - 1).type() == Type.IDENT
                        && !LanguageSpec.isKeyword(toks.get(i - 1).text())
                        && !(i > 1 && toks.get(i - 2).is("def"))) {

                    String name = toks.get(i - 1).text();
                    if (i >= 3 && toks.get(i - 2).is(".") && toks.get(i - 3).type() == Type.IDENT) {
                        String mod = resolveModule(toks.get(i - 3).text());
                        if (mod != null) name = mod + "." + name;
                    }

                    int argStart = lastComma >= 0 ? lastComma + 1 : i + 1;
                    String named = null;
                    if (argStart + 1 < toks.size() && toks.get(argStart).type() == Type.IDENT
                            && toks.get(argStart + 1).is("=")) {
                        named = toks.get(argStart).text();
                    }
                    return new CallContext(name, commas, named);
                } else {
                    // grouping bracket / list: keep looking outwards
                    commas = 0;
                    lastComma = -1;
                }
            } else if (depth == 0 && t.is(",")) {
                commas++;
                if (lastComma < 0) lastComma = i;
            }
        }
        return null;
    }

    private List<String> paramsFor(String name) {
        List<String> p = ctorParams.get(name);          // Player(  -> __init__ parameters
        if (p != null) return p;
        if (info.classes().containsKey(name)) return List.of();   // class without __init__
        p = callableParams.get(name);
        if (p != null) return p;
        p = LanguageSpec.BUILTIN_FUNCTIONS.get(name);
        if (p != null) return p;
        p = LanguageSpec.NATIVE_CLASSES.get(name);
        if (p != null) return p;
        String q = importedNames.get(name);
        if (q != null && !q.equals(name)) return paramsFor(q);
        return methodParams.get(name);                   // obj.method(  /  self.method(
    }

    private void drawSignatureHelp(GuiGraphics g, Font f) {
        String line = lines.get(cursorLine);
        int col = Math.min(cursorColumn, line.length());
        CallContext ctx = findCallContext(line, col);
        if (ctx == null) return;

        List<String> params = paramsFor(ctx.name());
        if (params == null) return;

        int active = ctx.argIndex();
        if (ctx.namedArg() != null) active = params.indexOf(ctx.namedArg());

        // measure
        String head = ctx.name() + "(";
        int total = f.width(head) + f.width(")");
        for (int i = 0; i < params.size(); i++) {
            total += f.width(params.get(i)) + (i < params.size() - 1 ? f.width(", ") : 0);
        }

        int w = total + 10;
        int h = 14;
        int x = xOf(cursorLine, col) - 4;
        int y = lineTop(cursorLine) - h - 3;
        if (y < getY()) y = lineTop(cursorLine) + LH + 3;
        x = Math.max(getX() + 2, Math.min(x, getX() + width - w - 2));

        g.fill(x, y, x + w, y + h, C_POPUP_BG);
        g.renderOutline(x, y, w, h, C_POPUP_BORDER);

        int tx = x + 5;
        int ty = y + 3;
        g.drawString(f, ctx.name(), tx, ty, C_FUNCTION, false);
        tx += f.width(ctx.name());
        g.drawString(f, "(", tx, ty, C_POPUP_TEXT, false);
        tx += f.width("(");

        for (int i = 0; i < params.size(); i++) {
            String p = params.get(i);
            boolean act = i == active;
            g.drawString(f, p, tx, ty, act ? 0xFFFFFFFF : C_POPUP_DETAIL, false);
            if (act) g.fill(tx, ty + 8, tx + f.width(p), ty + 9, 0xFFFFFFFF);
            tx += f.width(p);
            if (i < params.size() - 1) {
                g.drawString(f, ", ", tx, ty, C_POPUP_TEXT, false);
                tx += f.width(", ");
            }
        }
        g.drawString(f, ")", tx, ty, C_POPUP_TEXT, false);
    }
}