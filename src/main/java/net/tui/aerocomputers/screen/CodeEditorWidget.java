package net.tui.aerocomputers.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.tui.aerocomputers.block.entity.BoardComputerBlockEntity;
import net.tui.aerocomputers.config.AeroComputersConfig;
import net.tui.aerocomputers.lang.*;
import net.tui.aerocomputers.lang.PyLexer.Token;
import net.tui.aerocomputers.lang.PyLexer.Type;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static net.minecraft.client.gui.screens.Screen.hasAltDown;
import static net.minecraft.client.gui.screens.Screen.hasControlDown;
import static net.minecraft.client.gui.screens.Screen.hasShiftDown;

/**
 * A VS Code (Dark+) style Python editor.
 *
 * Highlighting: semantic colours, bracket pair colourisation, indent guides, matching bracket box,
 *               f-string replacement fields.
 * Editing: python aware auto indent, auto closing pairs, undo/redo, comment toggle, move/duplicate lines,
 *          word navigation, multi click selection, smart Home, tab stops.
 * Intellisense: fuzzy completion with icons, member completion for modules / hud objects,
 *               import completion, signature help with active parameter.
 *               Local .py files next to the program are known through LocalModules.
 * Inline widgets: colour literals (colour picker) and frequency pairs ["a:b", "c:d"] (antenna, popup still empty).
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
    private static final int C_FSTRING_BRACE = 0xFF569CD6;
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
    private final Map<String, String> variableClasses = new HashMap<>();  // var   -> hud.Label / Player / lib.Vec
    private final Map<String, List<String>> ctorParams = new HashMap<>();   // class -> __init__ params
    private final Map<String, List<String>> methodParams = new HashMap<>(); // method name -> params
    private final Map<String, Set<String>> classMethodNames = new HashMap<>();
    private final Map<String, Set<String>> classFields = new HashMap<>();
    private String[] lineClass = new String[0];                             // class a line belongs to

    // libraries the server config allows (what "import x" may use)
    private Set<String> enabledLibs = new TreeSet<>();

    private boolean pythonMode = true;
    public void setPythonMode(boolean on) {
        if (pythonMode == on) return;
        pythonMode = on;
        closeAutocomplete();
        rescan();
    }

    private static final int FQ_W = 174;
    private static final int FQ_H = 128;
    private int freqActive = 0;     // which of the two slots receives the next picked item

    // function scopes: parameters and locals are only visible inside their function
    private static final class Scope {
        final int start;
        final int indent;
        final int parent;
        final List<String> params;
        final Set<String> locals = new LinkedHashSet<>();
        int end;

        Scope(int start, int indent, int parent, List<String> params) {
            this.start = start;
            this.indent = indent;
            this.parent = parent;
            this.params = params;
            this.end = start;
        }
    }
    private final List<Scope> scopes = new ArrayList<>();
    private int[] lineScope = new int[0];
    private final Set<String> globalNames = new LinkedHashSet<>();

    // inline swatches: colour literals (0xFF0000 and "#FF0000") and frequency pairs (["a:b", "c:d"])
    // freq == true -> rgb is unused, the swatch shows an antenna and opens the (still empty) frequency popup
    private record ColorLit(int line, int start, int end, int rgb, boolean freq) {}
    private List<List<ColorLit>> colorsByLine = new ArrayList<>();
    private int maxColorsPerLine = 0;

    /** a frequency item: empty string, or "something:something" */
    private static final Pattern FREQ_ID =
            Pattern.compile("^([A-Za-z0-9_.-]+:[A-Za-z0-9_./-]+)?$");

    private static final int PK_SV_W = 90;
    private static final int PK_SV_H = 80;
    private static final int PK_HUE_W = 10;
    private static final int PK_W = 118;
    private static final int PK_H = 110;


    private Supplier<ItemStack> carriedSupplier = () -> ItemStack.EMPTY;
    private final ItemIconButton[] freqButtons = new ItemIconButton[2];

    public void setCarriedSupplier(Supplier<ItemStack> s) { this.carriedSupplier = s; }

    /** top-left of the first inventory cell while the frequency popup is open, else null */
    public int[] freqInventoryOrigin() {
        if (!pickerOpen || !pickerFreq) return null;
        int[] r = pickerRect();
        return new int[]{r[0] + 6, r[1] + 48};
    }


    private boolean pickerOpen = false;
    private boolean pickerFreq = false;         // the open popup is the (empty) frequency popup
    private int pickerDrag = 0;                 // 0 none, 1 saturation/value, 2 hue
    private int pickerLine, pickerStart, pickerEnd;
    private boolean pickerString, pickerLower, pickerUndoDone;
    private char pickerQuote;
    private String pickerPrefix = "0x";
    private float pickerH, pickerS, pickerV;

    private static final int SWATCH_W = 10;     // colour square (9px) + 1px gap, drawn inline before the literal
    private static final int FOLD_ZONE = 12;    // gutter space for the fold arrows
    private ColorLit hoverSwatch;

    // code folding (indentation based, like VS Code for Python)
    // collapsed regions survive closing and re-opening the GUI (the widget itself is recreated every time)
    private record FoldMemory(int textHash, Set<Integer> folds) {}
    private static final Map<String, FoldMemory> FOLD_MEMORY = new HashMap<>();
    private String foldKey;

    private final Set<Integer> folded = new TreeSet<>();   // start lines of collapsed regions
    private int[] foldEnd = new int[0];                    // per line: last line of the region it opens, or -1
    private int[] rowOf = new int[0];                      // document line -> visual row
    private int[] lineOfRow = new int[0];                  // visual row -> document line
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
        foldKey = null;   // the screen sets the fold key for the new file right after this
        lines.clear();
        lines.addAll(List.of(text.replace("\r", "").split("\n", -1)));
        folded.clear();
        undoStack.clear();
        redoStack.clear();
        lastEditKind = KIND_NONE;
        clearSelection();
        cursorLine = 0;
        cursorColumn = 0;
        scrollX = 0;
        scrollY = 0;
        closeAutocomplete();
        rescan();
        clampScroll();
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        // NOTE: do NOT close popups here. The screen calls setFocused(false) immediately followed by
        // setFocused(true) on every click, which used to close the colour picker right after opening it.
        // Real focus loss is detected in renderWidget().
        if (focused) resetBlink();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {}

    // =====================================================================
    // GEOMETRY
    // =====================================================================
    private Font font() { return Minecraft.getInstance().font; }
    private int gutter() { return Math.max(34, font().width(String.valueOf(lines.size())) + 22) + FOLD_ZONE; }
    private int textLeft() { return getX() + gutter(); }
    private int textOrigin() { return textLeft() + 6 - scrollX; }
    private int rowIndex(int line) { return line >= 0 && line < rowOf.length ? rowOf[line] : Math.max(0, line); }
    private int rowCount() { return Math.max(1, lineOfRow.length); }
    private int lineAtRow(int row) { return lineOfRow.length == 0 ? 0 : lineOfRow[clamp(row, 0, lineOfRow.length - 1)]; }
    private boolean isHidden(int line) { return line >= 0 && line < rowOf.length && lineOfRow[rowOf[line]] != line; }
    private int lineTop(int line) { return getY() + 5 + rowIndex(line) * LH - scrollY; }
    private int viewW() { return width - gutter() - SB; }
    private int viewH() { return height - SB; }

    private int xOf(int line, int col) {
        String s = lines.get(line);
        int c = Math.min(col, s.length());
        return textOrigin() + font().width(visibleText(line, c)) + SWATCH_W * swatchesBefore(line, c);
    }

    /** s[0, col) without the characters of collapsed literals */
    private String visibleText(int line, int col) {
        String s = lines.get(line);
        if (line >= colorsByLine.size()) return s.substring(0, col);
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        for (ColorLit c : colorsByLine.get(line)) {
            if (c.start() >= col) break;
            if (!isCollapsed(c)) continue;
            if (c.start() > pos) sb.append(s, pos, c.start());
            pos = Math.max(pos, Math.min(c.end(), col));
        }
        if (pos < col) sb.append(s, pos, col);
        return sb.toString();
    }

    /** the literal whose square is drawn directly before column "col", or null */
    private ColorLit literalAt(int line, int col) {
        if (line < 0 || line >= colorsByLine.size()) return null;
        for (ColorLit c : colorsByLine.get(line)) if (c.start() == col) return c;
        return null;
    }

    private boolean isExpanded(ColorLit c) {
        if (pickerOpen && pickerLine == c.line() && pickerStart == c.start()) return true;
        return isFocused() && cursorLine == c.line()
                && cursorColumn >= c.start() && cursorColumn <= c.end();
    }

    /** collapsed = only the small box is visible (never in read-only editors, they can't click the box) */
    private boolean isCollapsed(ColorLit c) {
        return !readOnly && !isExpanded(c);
    }

    /** Number of inline squares drawn to the left of the boundary at "col". */
    private int swatchesBefore(int line, int col) {
        if (line < 0 || line >= colorsByLine.size()) return 0;
        int n = 0;
        for (ColorLit c : colorsByLine.get(line)) if (c.start() < col) n++;
        return n;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private int maxScrollY() {
        int content = 5 + rowCount() * LH;
        int vh = viewH();
        return content <= vh ? 0 : content - vh + LH * 3;
    }

    private int maxScrollX() { return Math.max(0, contentWidth - viewW()); }

    private void clampScroll() {
        scrollY = clamp(scrollY, 0, maxScrollY());
        scrollX = clamp(scrollX, 0, maxScrollX());
    }

    private void ensureCursorVisible() {
        revealLine(cursorLine);
        String line = lines.get(cursorLine);
        cursorColumn = Math.min(cursorColumn, line.length());

        int cx = xOf(cursorLine, cursorColumn) - textOrigin();
        int area = viewW() - 14;
        if (cx < scrollX) scrollX = Math.max(0, cx - 20);
        if (cx > scrollX + area) scrollX = cx - area;

        int top = 5 + rowIndex(cursorLine) * LH;
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

    private void refreshEnabledLibraries() {
        Set<String> set = new TreeSet<>();
        try {
            for (Object o : AeroComputersConfig.ALLOWED_LIBRARIES.get()) {
                String name = String.valueOf(o).trim();
                if (!name.isEmpty()) set.add(name);
            }
        } catch (RuntimeException e) {
            set.add("math"); // config not loaded yet
        }
        enabledLibs = set;
    }

    /** an allowed library OR a local .py file next to the program */
    private boolean isKnownModule(String w) {
        return enabledLibs.contains(w) || LocalModules.has(w);
    }

    private boolean isModuleName(String w) {
        return LanguageSpec.MODULES.contains(w) || isKnownModule(w);
    }

    /** a module that comes from a local file (and is not a catalog library) */
    private static boolean isLocalModule(String mod) {
        return LocalModules.has(mod) && !LanguageSpec.MODULES.contains(mod);
    }

    private List<String> localParams(String name) {
        List<String> p = LocalModules.params(name);          // "lib.add"
        if (p != null) return p;
        String q = importedNames.get(name);                  // "add" after "from lib import add"
        return q == null ? null : LocalModules.params(q);
    }

    /** Call after LocalModules.update(...) so highlighting and completion pick up the new files. */
    public void refreshLocalModules() {
        rescan();
    }

    private void rescan() {
        int n = lines.size();
        Font f = font();
        refreshEnabledLibraries();

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
        scanColors();
        for (int i = 0; i < n; i++) {
            maxW = Math.max(maxW, f.width(lines.get(i)) + SWATCH_W * colorsByLine.get(i).size());
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
        analyzeScopes();
        analyzeImports();

        Set<String> vars = new LinkedHashSet<>(extraVariables);
        vars.addAll(info.variables());
        variables = List.copyOf(vars);
        functions = List.copyOf(info.functions().keySet());
        classes = List.copyOf(info.classes().keySet());
        callableParams = info.callableParams();

        computeFolds();
    }

    // ---------------------------------------------------------------- code folding
    /** A line opens a foldable region when the following code is indented deeper. */
    private void computeFolds() {
        int n = lines.size();
        foldEnd = new int[n];
        Arrays.fill(foldEnd, -1);

        if (!pythonMode) {
            folded.clear();
            layoutRows();
            return;
        }

        Deque<Integer> stack = new ArrayDeque<>();
        int lastCode = -1;

        for (int i = 0; i < n; i++) {
            if (lineStart[i] != PyLexer.State.NONE) { lastCode = i; continue; } // inside a multi-line string
            if (PyLexer.significant(lineTokens.get(i)).isEmpty()) continue;     // blank / comment only

            int d = indentOf(lines.get(i));
            while (!stack.isEmpty() && indentOf(lines.get(stack.peek())) >= d) {
                int start = stack.pop();
                if (lastCode > start) foldEnd[start] = lastCode;
            }
            stack.push(i);
            lastCode = i;
        }
        while (!stack.isEmpty()) {
            int start = stack.pop();
            if (lastCode > start) foldEnd[start] = lastCode;
        }

        folded.removeIf(start -> start < 0 || start >= n || foldEnd[start] <= start);
        layoutRows();
    }

    private void layoutRows() {
        int n = lines.size();
        rowOf = new int[n];
        int[] tmp = new int[n];
        int rows = 0;
        int hiddenUntil = -1;

        for (int i = 0; i < n; i++) {
            if (i <= hiddenUntil) { rowOf[i] = Math.max(0, rows - 1); continue; }
            rowOf[i] = rows;
            tmp[rows++] = i;
            if (folded.contains(i) && i < foldEnd.length && foldEnd[i] > i) hiddenUntil = foldEnd[i];
        }
        lineOfRow = Arrays.copyOf(tmp, rows);
        persistFolds();
    }

    /** Opens every collapsed region that hides this line (the cursor must never be hidden). */
    private void revealLine(int line) {
        boolean changed = false;
        for (Iterator<Integer> it = folded.iterator(); it.hasNext(); ) {
            int start = it.next();
            if (start < foldEnd.length && start < line && line <= foldEnd[start]) { it.remove(); changed = true; }
        }
        if (changed) layoutRows();
    }

    /**
     * Keeps collapsed regions across a bulk change (undo, redo, move, duplicate, delete).
     * A region stays collapsed unless the change touches the code inside it or moves its header.
     */
    private void remapFolds(List<String> oldLines, int[] oldFoldEnd) {
        if (folded.isEmpty()) return;

        int oldN = oldLines.size();
        int newN = lines.size();
        int max = Math.min(oldN, newN);

        int p = 0;                                   // first differing line
        while (p < max && oldLines.get(p).equals(lines.get(p))) p++;
        int q = 0;                                   // common lines at the end
        while (q < max - p && oldLines.get(oldN - 1 - q).equals(lines.get(newN - 1 - q))) q++;

        int changedEnd = oldN - q;                   // old lines [p, changedEnd) were changed
        int delta = newN - oldN;

        Set<Integer> next = new TreeSet<>();
        for (int start : folded) {
            int end = start < oldFoldEnd.length ? oldFoldEnd[start] : start;

            if (start >= changedEnd) next.add(start + delta);                       // after the change
            else if (start < p) { if (p > end) next.add(start); }                   // before it, hidden code untouched
            else if (start == p && changedEnd == start + 1 && delta == 0) next.add(start); // only the header text changed
            // otherwise: hidden code touched or header moved -> the region opens
        }
        folded.clear();
        folded.addAll(next);
    }

    private void shiftFolds(int sl, int el, int delta) {
        if (folded.isEmpty()) return;
        Set<Integer> next = new TreeSet<>();
        for (int start : folded) {
            if (start <= sl) next.add(start);
            else if (start > el) next.add(start + delta);   // starts inside the replaced range are dropped
        }
        folded.clear();
        folded.addAll(next);
    }

    /** Call once after creating the editor: restores the collapsed regions of this computer. */
    public void setFoldKey(String key) {
        this.foldKey = key;
        FoldMemory m = FOLD_MEMORY.get(key);
        if (m != null && m.textHash() == getText().hashCode()) {   // only if the program is still the same
            folded.clear();
            folded.addAll(m.folds());
            computeFolds();                                         // validates and lays out the rows
        }
    }

    private void persistFolds() {
        if (foldKey == null) return;
        if (folded.isEmpty()) FOLD_MEMORY.remove(foldKey);
        else FOLD_MEMORY.put(foldKey, new FoldMemory(getText().hashCode(), new TreeSet<>(folded)));
    }

    private boolean isFoldable(int line) { return line >= 0 && line < foldEnd.length && foldEnd[line] > line; }

    private void toggleFold(int line) {
        if (!isFoldable(line)) return;

        if (folded.contains(line)) {
            folded.remove(line);
        } else {
            folded.add(line);
            // keep the cursor / selection visible: move them to the header line
            if (cursorLine > line && cursorLine <= foldEnd[line]) {
                cursorLine = line;
                cursorColumn = Math.min(cursorColumn, lines.get(line).length());
            }
            if (selLine > line && selLine <= foldEnd[line]) {
                selLine = line;
                selColumn = Math.min(selColumn, lines.get(line).length());
            }
        }
        closeAutocomplete();
        layoutRows();
        clampScroll();
        resetBlink();
    }

    /** Ctrl+Shift+[ folds the innermost region around the cursor, Ctrl+Shift+] unfolds it. */
    private void foldAtCursor(boolean fold) {
        for (int s = cursorLine; s >= 0; s--) {
            if (!isFoldable(s) || foldEnd[s] < cursorLine) continue;
            if (fold && !folded.contains(s)) { toggleFold(s); return; }
            if (!fold && folded.contains(s)) { toggleFold(s); return; }
        }
    }

    private int foldArrowAt(double mx, double my) {
        int x0 = getX();
        int gut = gutter();
        if (mx < x0 + gut - FOLD_ZONE || mx >= x0 + gut) return -1;
        if (my < getY() || my >= getY() + viewH()) return -1;

        int row = Math.floorDiv((int) my - getY() - 5 + scrollY, LH);
        if (row < 0 || row >= lineOfRow.length) return -1;
        int line = lineOfRow[row];
        return isFoldable(line) ? line : -1;
    }

    private int[] foldedMarkerRect(Font f, int line) {
        int x = xOf(line, lines.get(line).length()) + 6;
        return new int[]{x, lineTop(line) - 1, f.width("...") + 8, LH};
    }

    private int foldedMarkerAt(double mx, double my) {
        if (mx < textLeft() || mx >= getX() + width - SB || my < getY() || my >= getY() + viewH()) return -1;
        int row = Math.floorDiv((int) my - getY() - 5 + scrollY, LH);
        if (row < 0 || row >= lineOfRow.length) return -1;
        int line = lineOfRow[row];
        if (!folded.contains(line) || !isFoldable(line)) return -1;
        int[] r = foldedMarkerRect(font(), line);
        return (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) ? line : -1;
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
                    if (isKnownModule(mod)) moduleAliases.put(alias, mod);
                    k = m;
                    while (k < t.size() && !t.get(k).is(",")) k++;
                    k++;
                }
            } else if (first.equals("from") && t.size() >= 4 && t.get(2).is("import")) {
                String mod = t.get(1).text();
                if (!isKnownModule(mod)) continue;
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
                    String name = t.get(4).text();
                    String qualified = mod + "." + name;
                    if (LanguageSpec.PRELOADED_MODULES.contains(mod) && LanguageSpec.NATIVE_CLASSES.containsKey(qualified)) {
                        variableClasses.put(t.get(0).text(), qualified);
                    } else if (LocalModules.isClass(mod, name)) {
                        variableClasses.put(t.get(0).text(), qualified);
                    }
                }
            } else if (t.size() >= 4 && t.get(0).type() == Type.IDENT && t.get(1).is("=")
                    && t.get(2).type() == Type.IDENT && t.get(3).is("(")
                    && importedNames.containsKey(t.get(2).text())) {
                String q = importedNames.get(t.get(2).text());
                int d = q.indexOf('.');
                if (d > 0 && LocalModules.isClass(q.substring(0, d), q.substring(d + 1))) {
                    variableClasses.put(t.get(0).text(), q);
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
    private static List<String> paramNames(List<Token> t, int from) { return paramNames(t, from, false); }

    private static List<String> paramNames(List<Token> t, int from, boolean includeSelf) {
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
                if ((p.is("(") || p.is(",") || p.is("*") || p.is("**")) && (includeSelf || !x.text().equals("self"))) {
                    out.add(x.text());
                }
            }
        }
        return out;
    }

    /** Finds every def with its extent (by indentation) and the names bound inside it. */
    private void analyzeScopes() {
        scopes.clear();
        globalNames.clear();

        int n = lines.size();
        lineScope = new int[n];
        Arrays.fill(lineScope, -1);
        Deque<Integer> open = new ArrayDeque<>();

        for (int i = 0; i < n; i++) {
            if (lineStart[i] != PyLexer.State.NONE) {
                lineScope[i] = open.isEmpty() ? -1 : open.peek();
                continue;
            }

            List<Token> t = PyLexer.significant(lineTokens.get(i));
            if (t.isEmpty()) {
                lineScope[i] = open.isEmpty() ? -1 : open.peek();
                continue;
            }

            int ind = indentOf(lines.get(i));
            while (!open.isEmpty() && ind <= scopes.get(open.peek()).indent) open.pop();

            lineScope[i] = open.isEmpty() ? -1 : open.peek();
            for (int idx : open) scopes.get(idx).end = i;

            if (t.get(0).is("def") && t.size() > 2 && t.get(1).type() == Type.IDENT && t.get(2).is("(")) {
                scopes.add(new Scope(i, ind, lineScope[i], paramNames(t, 3, true)));
                open.push(scopes.size() - 1);
                continue;
            }

            Set<String> assigned = new LinkedHashSet<>();
            ProgramAnalyzer.lineVariables(t, assigned);

            if (lineScope[i] >= 0) scopes.get(lineScope[i]).locals.addAll(assigned);
            else if (lineClass[i] == null) globalNames.addAll(assigned); // class level attributes are not globals
        }
    }

    /** Variables visible at a line: own function (params + locals), enclosing functions, globals. */
    private List<String> visibleVariables(int line) {
        Set<String> out = new LinkedHashSet<>();
        int idx = line >= 0 && line < lineScope.length ? lineScope[line] : -1;
        while (idx >= 0) {
            Scope s = scopes.get(idx);
            out.addAll(s.params);
            out.addAll(s.locals);
            idx = s.parent;
        }
        out.addAll(globalNames);
        out.addAll(extraVariables);
        return new ArrayList<>(out);
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

        if (!isFocused() && (autocompleteOpen || pickerOpen)) closeAutocomplete();   // really lost focus

        hoveredHelpWord = hasControlDown() ? getHelpWordAt(mouseX, mouseY) : null;

        g.fill(x0, y0, x0 + width, y0 + height, C_BG);

        // first / last are VISUAL ROWS (collapsed regions take no rows)
        int first = Math.max(0, Math.floorDiv(scrollY - 5, LH));
        int last = Math.min(lineOfRow.length - 1, Math.floorDiv(scrollY + vh - 5, LH));

        hoverSwatch = swatchAt(mouseX, mouseY);
        boolean gutterHover = mouseX >= x0 && mouseX < x0 + gut && mouseY >= y0 && mouseY < y0 + vh;

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
        for (int r = first; r <= last; r++) {
            int i = lineAtRow(r);
            String num = String.valueOf(i + 1);
            int color = (i == cursorLine && !readOnly) ? C_GUTTER_NUM_ACTIVE : C_GUTTER_NUM;
            g.drawString(f, num, x0 + gut - FOLD_ZONE - 10 - f.width(num), lineTop(i), color, false);

            // fold arrows: shown while the mouse is over the gutter, collapsed ones always
            if (!readOnly && isFoldable(i)) {
                boolean isFolded = folded.contains(i);
                if (gutterHover || isFolded) {
                    drawFoldArrow(g, x0 + gut - FOLD_ZONE + 6, lineTop(i) + 3, isFolded);
                }
            }
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
        for (int r = first; r <= last; r++) {
            int i = lineAtRow(r);
            drawLine(g, f, i, textOrigin(), lineTop(i), clipL, clipR);
            if (folded.contains(i) && isFoldable(i)) drawFoldedMarker(g, f, i);
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
        g.pose().translate(0, 0, pickerOpen && pickerFreq ? 100 : 400);
        if (active) {
            if (pickerOpen) drawPicker(g, f, mouseX, mouseY);
            else if (autocompleteOpen && !suggestions.isEmpty()) drawAutocomplete(g, f, mouseX, mouseY);
            else drawSignatureHelp(g, f);
        }
        g.pose().popPose();
    }

    private void drawSelection(GuiGraphics g, Font f, int first, int last) {
        if (!hasSelection()) return;
        int[] s = normalizedSelection();
        int color = isFocused() ? C_SEL : C_SEL_INACTIVE;

        for (int r = first; r <= last; r++) {
            int i = lineAtRow(r);
            if (i < s[0] || i > s[2]) continue;
            String text = lines.get(i);
            int from = i == s[0] ? s[1] : 0;
            int to = i == s[2] ? s[3] : text.length();
            int x1 = xOf(i, from);
            int x2 = xOf(i, to);
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

        for (int r = first; r <= last; r++) {
            int i = lineAtRow(r);
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
        if (!pythonMode) {
            g.drawString(f, lines.get(lineIdx), x, y, C_TEXT, false);
            return;
        }
        List<Token> toks = lineTokens.get(lineIdx);
        List<ColorLit> colors = lineIdx < colorsByLine.size() ? colorsByLine.get(lineIdx) : List.of();

        for (int ti = 0; ti < toks.size(); ti++) {
            Token t = toks.get(ti);

            ColorLit lit = null;
            for (ColorLit c : colors) if (c.start() == t.start()) { lit = c; break; }

            if (lit != null) {
                if (x + SWATCH_W > clipL && x < clipR) drawSwatch(g, x, y, lit);
                x += SWATCH_W;
                if (isCollapsed(lit)) {
                    // skip every token of the literal, only the box stays
                    while (ti + 1 < toks.size() && toks.get(ti + 1).start() < lit.end()) ti++;
                    continue;
                }
            }

            int w = f.width(t.text());
            if (t.type() != Type.WHITESPACE && x + w > clipL && x < clipR) {
                if (isFString(t)) drawFString(g, f, t.text(), x, y);
                else g.drawString(f, t.text(), x, y, colorFor(toks, ti, lineIdx), false);

                if (hoveredHelpWord != null && hoveredHelpWord.line() == lineIdx
                        && hoveredHelpWord.startColumn() == t.start()
                        && hoveredHelpWord.endColumn() == t.end()) {
                    g.fill(x, y + f.lineHeight - 1, x + w, y + f.lineHeight, 0xFF3399FF);
                }
            }
            x += w;
        }
    }

    // ---------------------------------------------------------------- f-strings
    /**
     * True for a string token whose prefix contains f / F (f"..", rf"..", Fr'..').
     * The lexer keeps the prefix inside the STRING token. Continuation lines of a triple quoted
     * string have no prefix and are therefore drawn as normal strings.
     */
    private static boolean isFString(Token t) {
        if (t.type() != Type.STRING) return false;
        String s = t.text();
        boolean f = false;
        for (int i = 0; i < s.length() && i < 3; i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') return f;
            if ("rRbBuUfF".indexOf(c) < 0) return false;
            if (c == 'f' || c == 'F') f = true;
        }
        return false;
    }

    /** Draws an f-string: text in string colour, replacement fields {expr} in text colour with blue braces. */
    private void drawFString(GuiGraphics g, Font f, String s, int x, int y) {
        int len = s.length();
        int[] col = new int[len];
        int depth = 0;

        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (depth == 0 && (c == '{' || c == '}') && i + 1 < len && s.charAt(i + 1) == c) {
                col[i] = C_STRING;            // {{ and }} are literal braces
                col[i + 1] = C_STRING;
                i++;
                continue;
            }
            if (c == '{') {
                col[i] = C_FSTRING_BRACE;
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                col[i] = C_FSTRING_BRACE;
            } else {
                col[i] = depth > 0 ? C_TEXT : C_STRING;
            }
        }

        int start = 0;
        while (start < len) {
            int end = start + 1;
            while (end < len && col[end] == col[start]) end++;
            String piece = s.substring(start, end);
            g.drawString(f, piece, x, y, col[start], false);
            x += f.width(piece);
            start = end;
        }
    }

    private void drawBracketMatch(GuiGraphics g, Font f) {
        if (!pythonMode) return;
        long k = -1;
        for (int c : new int[]{cursorColumn - 1, cursorColumn}) {
            if (c >= 0 && bracketPair.containsKey(key(cursorLine, c))) { k = key(cursorLine, c); break; }
        }
        if (k < 0) return;

        for (long kk : new long[]{k, bracketPair.get(k)}) {
            int line = (int) (kk >> 20);
            int col = (int) (kk & 0xFFFFF);
            if (line < 0 || line >= lines.size() || col >= lines.get(line).length() || isHidden(line)) continue;
            int x = xOf(line, col);
            int w = f.width(String.valueOf(lines.get(line).charAt(col)));
            int y = lineTop(line);
            g.fill(x, y - 1, x + w, y + LH, C_BRACKET_MATCH_FILL);
            g.renderOutline(x, y - 1, w, LH + 1, C_BRACKET_MATCH_BORDER);
        }
    }

    private void drawSwatch(GuiGraphics g, int x, int y, ColorLit c) {
        boolean active = (pickerOpen && pickerLine == c.line() && pickerStart == c.start()) || c.equals(hoverSwatch);
        g.fill(x, y, x + 9, y + 9, active ? 0xFFFFFFFF : 0xFF6B6B6B);

        if (!c.freq()) {
            g.fill(x + 1, y + 1, x + 8, y + 8, 0xFF000000 | c.rgb());
            return;
        }

        // frequency: dark square with a small antenna
        g.fill(x + 1, y + 1, x + 8, y + 8, 0xFF2D2D30);
        int a = 0xFF4FC1FF;
        g.fill(x + 4, y + 2, x + 5, y + 7, a);   // mast
        g.fill(x + 3, y + 7, x + 6, y + 8, a);   // base
        g.fill(x + 2, y + 2, x + 3, y + 4, a);   // inner waves
        g.fill(x + 6, y + 2, x + 7, y + 4, a);
        g.fill(x + 1, y + 1, x + 2, y + 5, a);   // outer waves
        g.fill(x + 7, y + 1, x + 8, y + 5, a);
    }

    private void drawFoldArrow(GuiGraphics g, int cx, int cy, boolean collapsed) {
        int c = 0xFFC5C5C5;
        if (!collapsed) {          // v
            g.fill(cx - 3, cy - 1, cx + 4, cy, c);
            g.fill(cx - 2, cy, cx + 3, cy + 1, c);
            g.fill(cx - 1, cy + 1, cx + 2, cy + 2, c);
            g.fill(cx, cy + 2, cx + 1, cy + 3, c);
        } else {                   // >
            g.fill(cx - 1, cy - 3, cx, cy + 4, c);
            g.fill(cx, cy - 2, cx + 1, cy + 3, c);
            g.fill(cx + 1, cy - 1, cx + 2, cy + 2, c);
            g.fill(cx + 2, cy, cx + 3, cy + 1, c);
        }
    }

    /** the "..." box behind a collapsed line */
    private void drawFoldedMarker(GuiGraphics g, Font f, int line) {
        int[] r = foldedMarkerRect(f, line);
        g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0x66264F78);
        g.drawString(f, "...", r[0] + 4, r[1] + 1, 0xFF9DA5B4, false);
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

        if ((p.equals("import") || p.equals("from")) && isModuleName(w)) return C_CLASS;

        // member access:  math.sqrt   hud.Label   label.text   lib.add
        if (p.equals(".")) {
            String recv = receiverBefore(toks, i);
            String mod = recv == null ? null : resolveModule(recv);
            if (mod != null) {
                if (isLocalModule(mod)) {
                    if (LocalModules.isClass(mod, w)) return C_CLASS;
                    if (LocalModules.params(mod + "." + w) != null) return C_FUNCTION;
                    return C_VARIABLE;
                }
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
            int d = imported.indexOf('.');
            if (d > 0 && isLocalModule(imported.substring(0, d))) {
                String lm = imported.substring(0, d);
                String name = imported.substring(d + 1);
                if (LocalModules.isClass(lm, name)) return C_CLASS;
                if (LocalModules.params(imported) != null) return C_FUNCTION;
                return C_VARIABLE;
            }
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
        int row = rowIndex(cursorLine) + delta;
        if (row < 0) setCursor(0, 0, extend);
        else if (row >= lineOfRow.length) {
            int l = lineAtRow(lineOfRow.length - 1);
            setCursor(l, lines.get(l).length(), extend);
        } else {
            int t = lineAtRow(row);
            setCursor(t, Math.min(desired, lines.get(t).length()), extend);
        }
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
        List<String> oldLines = new ArrayList<>(lines);
        int[] oldEnd = foldEnd.clone();
        lines.clear();
        lines.addAll(s.lines());
        remapFolds(oldLines, oldEnd);
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
        int lineCountBefore = lines.size();

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

        shiftFolds(sl, el, lines.size() - lineCountBefore);
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
        List<String> oldLines = new ArrayList<>(lines);
        int[] oldEnd = foldEnd.clone();
        for (int i = l2; i >= l1; i--) lines.remove(i);
        if (lines.isEmpty()) lines.add("");
        remapFolds(oldLines, oldEnd);
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
        List<String> oldLines = new ArrayList<>(lines);
        int[] oldEnd = foldEnd.clone();
        List<String> block = new ArrayList<>(lines.subList(r[0], r[1] + 1));
        for (int i = 0; i < block.size(); i++) lines.remove(r[0]);
        lines.addAll(r[0] + delta, block);
        remapFolds(oldLines, oldEnd);
        cursorLine += delta;
        if (selLine != -1) selLine += delta;
        closeAutocomplete();
        afterEdit();
    }

    private void duplicateLines(boolean down) {
        int[] r = selectedLineRange();
        recordUndo(KIND_NONE);
        List<String> oldLines = new ArrayList<>(lines);
        int[] oldEnd = foldEnd.clone();
        List<String> block = new ArrayList<>(lines.subList(r[0], r[1] + 1));
        if (down) {
            lines.addAll(r[1] + 1, block);
            cursorLine += block.size();
            if (selLine != -1) selLine += block.size();
        } else {
            lines.addAll(r[0], block);
        }
        remapFolds(oldLines, oldEnd);
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

        if (!pythonMode) {
            replaceRange(cursorLine, col, cursorLine, col, "\n" + baseIndent);
            closeAutocomplete();
            return;
        }

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
        if (!pythonMode) {
            insertAtCursor(String.valueOf(c), isWordChar(c) ? KIND_TYPE : KIND_NONE);
            return true;
        }
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
            if (shift && keyCode == GLFW.GLFW_KEY_LEFT_BRACKET) { foldAtCursor(true); return true; }
            if (shift && keyCode == GLFW.GLFW_KEY_RIGHT_BRACKET) { foldAtCursor(false); return true; }
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
        int row = clamp(Math.floorDiv((int) mouseY - getY() - 5 + scrollY, LH), 0, rowCount() - 1);
        int line = lineAtRow(row);
        String s = lines.get(line);
        int relX = (int) mouseX - textOrigin();

        int col = 0, acc = 0, i = 0;
        while (i < s.length()) {
            ColorLit c = literalAt(line, i);
            if (c != null && isCollapsed(c)) {
                if (relX < acc + SWATCH_W / 2) break;
                acc += SWATCH_W;
                i = c.end();
                col = i;
                continue;
            }
            int sw = c != null ? SWATCH_W : 0;
            int w = f.width(String.valueOf(s.charAt(i)));
            if (relX < acc + sw + w / 2) break;
            acc += sw + w;
            col = ++i;
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
        // ---- swatches + popups (colour picker / frequency popup) ----
        if (button == 0 && !readOnly) {
            if (pickerOpen) {
                int[] r = pickerRect();
                boolean inside = mouseX >= r[0] && mouseX <= r[0] + r[2] && mouseY >= r[1] && mouseY <= r[1] + r[3];
                if (inside) {
                    if (pickerFreq) return freqClick(mouseX, mouseY, button, r);   // empty frequency popup: just swallow the click
                    int svx = r[0] + 6, svy = r[1] + 6, hx = r[0] + 102;
                    if (mouseX >= svx && mouseX < svx + PK_SV_W && mouseY >= svy && mouseY < svy + PK_SV_H) {
                        pickerDrag = 1;
                        pickerDragTo(mouseX, mouseY);
                    } else if (mouseX >= hx - 2 && mouseX < hx + PK_HUE_W + 2
                            && mouseY >= svy && mouseY < svy + PK_SV_H) {
                        pickerDrag = 2;
                        pickerDragTo(mouseX, mouseY);
                    }
                    return true;
                }
                ColorLit swOpen = swatchAt(mouseX, mouseY);
                boolean same = swOpen != null && swOpen.line() == pickerLine && swOpen.start() == pickerStart;
                closeAutocomplete();
                if (same) return true;
            }

            ColorLit sw = swatchAt(mouseX, mouseY);
            if (sw != null) {
                setFocused(true);
                openPicker(sw);
                return true;
            }
        }

        // ---- fold arrows / "..." marker ----
        if (button == 0 && !readOnly) {
            int arrow = foldArrowAt(mouseX, mouseY);
            if (arrow >= 0) {
                setFocused(true);
                toggleFold(arrow);
                return true;
            }
            int marker = foldedMarkerAt(mouseX, mouseY);
            if (marker >= 0) {
                setFocused(true);
                toggleFold(marker);
                return true;
            }
        }

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

        if (pickerDrag != 0) {
            pickerDragTo(mouseX, mouseY);
            return true;
        }

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
        if (pickerDrag != 0) {
            pickerDrag = 0;
            return true;
        }
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
        if (!pythonMode) return null;
        int relY = (int) mouseY - getY() - 5 + scrollY;
        if (relY < 0) return null;
        int helpRow = relY / LH;
        if (helpRow >= lineOfRow.length) return null;
        int lineIndex = lineOfRow[helpRow];
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
            ColorLit lc = literalAt(lineIndex, i);
            if (lc != null) {
                acc += SWATCH_W;
                if (isCollapsed(lc)) {
                    if (acc > relX) return null;     // mouse is over a collapsed box
                    i = lc.end() - 1;                // skip the hidden text
                    continue;
                }
            }
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
    // COLOUR + FREQUENCY LITERALS, PICKER
    // =====================================================================
    private static boolean isHex(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    /** 0xFF0000 (number) or "#FF0000" / '#FF0000' (string). */
    private ColorLit parseColor(int line, Token t) {
        String s = t.text();

        if (t.type() == Type.NUMBER && s.length() == 8 && s.charAt(0) == '0'
                && (s.charAt(1) == 'x' || s.charAt(1) == 'X') && isHex(s, 2, 8)) {
            return new ColorLit(line, t.start(), t.end(), Integer.parseInt(s.substring(2), 16), false);
        }

        if (t.type() == Type.STRING && !t.unterminated() && s.length() == 9
                && (s.charAt(0) == '"' || s.charAt(0) == '\'') && s.charAt(8) == s.charAt(0)
                && s.charAt(1) == '#' && isHex(s, 2, 8)) {
            return new ColorLit(line, t.start(), t.end(), Integer.parseInt(s.substring(2, 8), 16), false);
        }
        return null;
    }

    /** a plain quoted string (no prefix) that is empty or "something:something" */
    private static boolean isFreqString(Token t) {
        if (t.type() != Type.STRING || t.unterminated()) return false;
        String s = t.text();
        if (s.length() < 2) return false;
        char q = s.charAt(0);
        if ((q != '"' && q != '\'') || s.charAt(s.length() - 1) != q) return false;   // also rejects prefixed strings
        return FREQ_ID.matcher(s.substring(1, s.length() - 1)).matches();
    }

    /** ["a:b", "c:d"] starting at the '[' token with index i of the significant tokens. */
    private ColorLit parseFreq(int line, List<Token> sig, int i) {
        if (i + 4 >= sig.size()) return null;
        if (!sig.get(i).is("[") || !sig.get(i + 2).is(",") || !sig.get(i + 4).is("]")) return null;
        if (!isFreqString(sig.get(i + 1)) || !isFreqString(sig.get(i + 3))) return null;
        return new ColorLit(line, sig.get(i).start(), sig.get(i + 4).end(), 0, true);
    }

    private void scanColors() {
        colorsByLine = new ArrayList<>(lines.size());
        maxColorsPerLine = 0;

        for (int i = 0; i < lines.size(); i++) {
            List<ColorLit> list = new ArrayList<>();

            if (pythonMode) {
                for (Token t : lineTokens.get(i)) {
                    ColorLit c = parseColor(i, t);
                    if (c != null) list.add(c);
                }

                // lines that start inside a multi-line string contain no real code
                if (lineStart[i] == PyLexer.State.NONE) {
                    List<Token> sig = PyLexer.significant(lineTokens.get(i));
                    for (int k = 0; k < sig.size(); k++) {
                        ColorLit fr = parseFreq(i, sig, k);
                        if (fr != null) list.add(fr);
                    }
                }
            }

            list.sort(Comparator.comparingInt(ColorLit::start));
            colorsByLine.add(list);
            maxColorsPerLine = Math.max(maxColorsPerLine, list.size());
        }
    }

    /** The square under the mouse (it sits directly in front of the literal). */
    private ColorLit swatchAt(double mx, double my) {
        if (readOnly || colorsByLine.isEmpty()) return null;
        if (mx < textLeft() || mx >= getX() + width - SB) return null;
        if (my < getY() || my >= getY() + viewH()) return null;

        int row = Math.floorDiv((int) my - getY() - 5 + scrollY, LH);
        if (row < 0 || row >= lineOfRow.length) return null;
        int line = lineOfRow[row];
        if (line >= colorsByLine.size()) return null;

        int ly = lineTop(line);
        if (my < ly - 1 || my > ly + 10) return null;

        for (ColorLit c : colorsByLine.get(line)) {
            int sx = xOf(line, c.start());
            if (mx >= sx - 1 && mx < sx + SWATCH_W) return c;
        }
        return null;
    }

    private static int hsvToRgb(float h, float s, float v) {
        float hh = (h - (float) Math.floor(h)) * 6f;
        int i = (int) hh;
        float f = hh - i;
        float p = v * (1 - s);
        float q = v * (1 - s * f);
        float t = v * (1 - s * (1 - f));
        float r, g, b;
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    private void setPickerFromRgb(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;

        float h = 0;
        if (d > 0) {
            if (max == r) h = ((g - b) / d) % 6f;
            else if (max == g) h = (b - r) / d + 2f;
            else h = (r - g) / d + 4f;
            h /= 6f;
            if (h < 0) h += 1f;
        }
        pickerH = h;
        pickerS = max == 0 ? 0 : d / max;
        pickerV = max;
    }

    private void openPicker(ColorLit c) {
        closeAutocomplete();

        pickerLine = c.line();
        pickerStart = c.start();
        pickerEnd = c.end();
        pickerFreq = c.freq();
        pickerUndoDone = false;
        pickerDrag = 0;

        freqActive = 0;

        clearSelection();
        cursorLine = c.line();
        cursorColumn = c.end();
        desiredColumn = -1;
        ensureCursorVisible();
        resetBlink();

        if (pickerFreq) {
            // frequency popup: same window as the colour picker, content comes later
            pickerOpen = true;
            return;
        }

        String src = lines.get(c.line()).substring(c.start(), c.end());
        pickerString = src.charAt(0) == '"' || src.charAt(0) == '\'';
        pickerQuote = pickerString ? src.charAt(0) : 0;
        pickerPrefix = pickerString ? "#" : src.substring(0, 2);

        String digits = pickerString ? src.substring(2, 8) : src.substring(2);
        pickerLower = digits.chars().anyMatch(ch -> ch >= 'a' && ch <= 'f');

        setPickerFromRgb(c.rgb());
        pickerOpen = true;
    }

    private int[] pickerRect() {
        int w = pickerFreq ? FQ_W : PK_W;
        int h = pickerFreq ? FQ_H : PK_H;
        int anchor = pickerLine < lines.size() ? xOf(pickerLine, pickerStart) - 4 : getX() + 2;
        int x = Math.max(textLeft(), Math.min(anchor, getX() + width - SB - w - 2));
        int y = lineTop(pickerLine) + LH + 3;
        if (y + h > getY() + height) y = lineTop(pickerLine) - h - 3;
        y = Math.max(getY() + 2, Math.min(y, getY() + height - h - 2));
        return new int[]{x, y, w, h};
    }

    private static float clamp01(double v) { return (float) Math.max(0, Math.min(1, v)); }

    private void pickerDragTo(double mx, double my) {
        int[] r = pickerRect();
        if (pickerDrag == 1) {
            pickerS = clamp01((mx - (r[0] + 6)) / (PK_SV_W - 1));
            pickerV = 1f - clamp01((my - (r[1] + 6)) / (PK_SV_H - 1));
        } else if (pickerDrag == 2) {
            pickerH = clamp01((my - (r[1] + 6)) / (PK_SV_H - 1));
        }
        pickerApply();
    }

    /** Writes the chosen colour back into the source text (same length, so the cursor never moves). */
    private void pickerApply() {
        if (pickerFreq) return;
        if (pickerLine >= lines.size()) { closeAutocomplete(); return; }
        String line = lines.get(pickerLine);
        if (pickerStart > pickerEnd || pickerEnd > line.length()) { closeAutocomplete(); return; }

        String hex = String.format("%06X", hsvToRgb(pickerH, pickerS, pickerV) & 0xFFFFFF);
        if (pickerLower) hex = hex.toLowerCase(Locale.ROOT);
        String text = pickerString ? "" + pickerQuote + pickerPrefix + hex + pickerQuote : pickerPrefix + hex;

        if (!pickerUndoDone) {          // the whole drag is ONE undo step
            recordUndo(KIND_NONE);
            pickerUndoDone = true;
        }
        lines.set(pickerLine, line.substring(0, pickerStart) + text + line.substring(pickerEnd));
        rescan();
    }

    private void drawPicker(GuiGraphics g, Font f, int m_x, int m_y) {
        if (pickerLine >= lines.size()) return;

        int[] r = pickerRect();
        int x = r[0], y = r[1];
        g.fill(x, y, x + r[2], y + r[3], C_POPUP_BG);
        g.renderOutline(x, y, r[2], r[3], C_POPUP_BORDER);

        if (pickerFreq) { drawFreqPopup(g, f, r, m_x, m_y); return; }

        int svx = x + 6, svy = y + 6;
        for (int i = 0; i < PK_SV_W; i++) {
            float sat = i / (float) (PK_SV_W - 1);
            g.fillGradient(svx + i, svy, svx + i + 1, svy + PK_SV_H,
                    0xFF000000 | hsvToRgb(pickerH, sat, 1f), 0xFF000000);
        }

        int hx = x + 102;
        int[] cols = {0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFF0000};
        for (int k = 0; k < 6; k++) {
            int y1 = svy + k * PK_SV_H / 6;
            int y2 = svy + (k + 1) * PK_SV_H / 6;
            g.fillGradient(hx, y1, hx + PK_HUE_W, y2, 0xFF000000 | cols[k], 0xFF000000 | cols[k + 1]);
        }

        int mx = svx + Math.round(pickerS * (PK_SV_W - 1));
        int my = svy + Math.round((1f - pickerV) * (PK_SV_H - 1));
        g.renderOutline(mx - 4, my - 4, 9, 9, 0xFF000000);
        g.renderOutline(mx - 3, my - 3, 7, 7, 0xFFFFFFFF);

        int hy = svy + Math.round(pickerH * (PK_SV_H - 1));
        g.fill(hx - 2, hy - 1, hx + PK_HUE_W + 2, hy + 2, 0xFF000000);
        g.fill(hx - 1, hy, hx + PK_HUE_W + 1, hy + 1, 0xFFFFFFFF);

        int rgb = hsvToRgb(pickerH, pickerS, pickerV) & 0xFFFFFF;
        g.fill(x + 6, y + 94, x + 22, y + 106, 0xFF000000 | rgb);
        g.renderOutline(x + 6, y + 94, 16, 12, C_POPUP_BORDER);
        g.drawString(f, String.format("#%06X", rgb), x + 28, y + 96, C_POPUP_TEXT, false);
    }

    // =====================================================================
    // AUTOCOMPLETE
    // =====================================================================
    /** True while the suggestion list OR the colour picker / frequency popup is open (so Esc closes it first). */
    public boolean isAutocompleteOpen() { return autocompleteOpen || pickerOpen; }

    public void closeAutocomplete() {
        pickerOpen = false;
        pickerFreq = false;
        pickerDrag = 0;
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
        if (last.type() == Type.COMMENT) return true;
        if (last.type() == Type.STRING && last.unterminated()) return !inFStringExpression(last.text());
        return false;
    }

    /** true if the (unterminated) string text ends inside a {expression} of an f-string */
    private static boolean inFStringExpression(String s) {
        int q = 0;
        boolean f = false;
        while (q < s.length() && "rRbBuUfF".indexOf(s.charAt(q)) >= 0) {
            if (s.charAt(q) == 'f' || s.charAt(q) == 'F') f = true;
            q++;
        }
        if (!f || q >= s.length()) return false;
        char quote = s.charAt(q);
        if (quote != '"' && quote != '\'') return false;

        int i = s.startsWith("" + quote + quote + quote, q) ? q + 3 : q + 1;
        int depth = 0;
        char inner = 0;                       // a nested 'string' inside the expression

        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (depth == 0) {
                if ((c == '{' || c == '}') && i + 1 < s.length() && s.charAt(i + 1) == c) { i++; continue; } // {{ }}
                if (c == '{') depth++;
                continue;
            }
            if (inner != 0) { if (c == inner) inner = 0; continue; }
            if (c == '\'' || c == '"') inner = c;
            else if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return depth > 0 && inner == 0;
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
        if (!pythonMode) { closeAutocomplete(); return; }
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

            if (afterImport && prefix.size() > 1 && isKnownModule(prefix.get(1).text())) {
                String mod = prefix.get(1).text();
                for (String m : LanguageSpec.moduleMembers(mod)) cands.add(memberCandidate(mod, m));
            } else {
                for (String m : enabledLibs) cands.add(new Candidate(m, Kind.MODULE, "module"));
                for (String m : LocalModules.names()) {
                    if (!enabledLibs.contains(m)) cands.add(new Candidate(m, Kind.MODULE, "local file"));
                }
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
                } else if (cls != null && cls.contains(".")
                        && LocalModules.isClass(cls.substring(0, cls.indexOf('.')), cls.substring(cls.indexOf('.') + 1))) {
                    String lm = cls.substring(0, cls.indexOf('.'));
                    String lc = cls.substring(cls.indexOf('.') + 1);
                    for (String m : LocalModules.classMethods(lm, lc)) cands.add(new Candidate(m, Kind.FUNCTION, lc));
                    for (String fld : LocalModules.classFields(lm, lc)) cands.add(new Candidate(fld, Kind.PROPERTY, lc));
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
        if (isLocalModule(module)) {
            if (LocalModules.isClass(module, name)) return new Candidate(name, Kind.CLASS, module);
            if (LocalModules.params(module + "." + name) != null) return new Candidate(name, Kind.FUNCTION, module);
            return new Candidate(name, Kind.VARIABLE, module);
        }
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
            boolean user = info.functions().containsKey(call.name()) || ctorParams.containsKey(call.name())
                    || localParams(call.name()) != null;
            boolean allowed = user || (SUGGEST_NAMED_ARGS_FOR_API && !LanguageSpec.isPositionalOnly(call.name()));
            List<String> params = allowed ? paramsFor(call.name()) : null;
            if (params != null) {
                for (String p : params) out.putIfAbsent(p, new Candidate(p, Kind.PARAM, "parameter"));
            }
        }

        for (String v : visibleVariables(cursorLine)) out.putIfAbsent(v, new Candidate(v, Kind.VARIABLE, "variable"));
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
            int d = q.indexOf('.');
            Kind k;
            if (d > 0 && isLocalModule(q.substring(0, d))) {
                k = LocalModules.isClass(q.substring(0, d), q.substring(d + 1)) ? Kind.CLASS
                        : LocalModules.params(q) != null ? Kind.FUNCTION : Kind.VARIABLE;
            } else {
                k = LanguageSpec.NATIVE_CLASSES.containsKey(q) ? Kind.CLASS
                        : LanguageSpec.BUILTIN_FUNCTIONS.containsKey(q) ? Kind.FUNCTION : Kind.CONSTANT;
            }
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
        int x = xOf(cursorLine, sc) - 18;
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
        p = LocalModules.params(name);                  // "lib.add(" and "lib.Vec("
        if (p != null) return p;
        String q = importedNames.get(name);
        if (q != null && !q.equals(name)) return paramsFor(q);
        return methodParams.get(name);                   // obj.method(  /  self.method(
    }

    private void drawSignatureHelp(GuiGraphics g, Font f) {
        if (!pythonMode) return;
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

    // ---------------------------------------------------------------- frequency popup
    private static ItemStack stringToItem(String id) {
        if (id == null || id.isBlank()) return ItemStack.EMPTY;
        ResourceLocation loc = ResourceLocation.tryParse(id);
        if (loc == null || !BuiltInRegistries.ITEM.containsKey(loc)) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.get(loc);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static String itemToString(ItemStack s) {
        return s.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    /** the two items currently written in the open literal */
    private ItemStack[] freqItems() {
        ItemStack[] out = {ItemStack.EMPTY, ItemStack.EMPTY};
        if (pickerLine >= lines.size()) return out;
        String line = lines.get(pickerLine);
        if (pickerStart > pickerEnd || pickerEnd > line.length()) return out;

        List<Token> sig = PyLexer.significant(PyLexer.lex(line.substring(pickerStart, pickerEnd)));
        if (sig.size() == 5) {
            for (int k = 0; k < 2; k++) {
                String s = sig.get(1 + k * 2).text();
                if (s.length() >= 2) out[k] = stringToItem(s.substring(1, s.length() - 1));
            }
        }
        return out;
    }

    /** writes the new pair into the source text */
    private void setFreqItem(int index, ItemStack stack) {
        ItemStack[] cur = freqItems();
        cur[index] = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        String text = "[\"" + itemToString(cur[0]) + "\", \"" + itemToString(cur[1]) + "\"]";

        recordUndo(KIND_NONE);
        replaceRange(pickerLine, pickerStart, pickerLine, pickerEnd, text);
        pickerEnd = pickerStart + text.length();      // the literal changed length
    }

    private void freqPress(int i) {
        freqActive = i;
        ItemStack carried = carriedSupplier.get();
        if (!carried.isEmpty()) setFreqItem(i, carried);     // item on the cursor -> this slot
    }

    /** returns false for the inventory part so the screen's real slots get the click */
    private boolean freqClick(double mx, double my, int button, int[] r) {
        if (my >= r[1] + 46) return false;

        for (int i = 0; i < 2; i++) {
            ItemIconButton b = freqButtons[i];
            if (b == null || !b.isMouseOver(mx, my)) continue;
            if (button == 1) {                                // right click clears
                freqActive = i;
                setFreqItem(i, ItemStack.EMPTY);
            } else {
                b.mouseClicked(mx, my, button);               // plays the click sound, calls freqPress
            }
            return true;
        }
        return true;                                          // swallow clicks on the popup background
    }

    private void drawFreqPopup(GuiGraphics g, Font f, int[] r, int mx, int my) {
        g.drawString(f, "Frequency", r[0] + 6, r[1] + 6, C_POPUP_TEXT, false);

        ItemStack[] cur = freqItems();
        for (int i = 0; i < 2; i++) {
            int bx = r[0] + FQ_W / 2 - 21 + i * 24;
            int by = r[1] + 20;
            final int slot = i;

            ItemIconButton b = new ItemIconButton(bx, by, cur[i], btn -> freqPress(slot));
            freqButtons[i] = b;

            if (i == freqActive) g.renderOutline(bx - 1, by - 1, 20, 20, 0xFF4FC1FF);
            b.render(g, mx, my, 0);
        }

        g.fill(r[0] + 6, r[1] + 42, r[0] + r[2] - 6, r[1] + 43, C_POPUP_BORDER);

        // cell backgrounds only, the items and tooltips come from the real slots
        int ox = r[0] + 6;
        int oy = r[1] + 48;
        for (int i = 0; i < 36; i++) {
            int col = i < 27 ? i % 9 : i - 27;
            int y = i < 27 ? oy + (i / 9) * 18 : oy + 58;
            g.fill(ox + col * 18, y, ox + col * 18 + 18, y + 18, 0xFF555555);
            g.fill(ox + col * 18 + 1, y + 1, ox + col * 18 + 17, y + 17, 0xFF2B2B2B);
        }
    }
}