package net.tui.aerocomputers.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import net.tui.aerocomputers.client.ClientFileState;
import net.tui.aerocomputers.network.FileActionPayload;
import net.tui.aerocomputers.network.FileActionPayload.Action;
import net.tui.aerocomputers.network.FileTreePayload;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class FileExplorerWidget extends AbstractWidget {
    private static final int ROW_H = 11;
    private static final int HEADER_H = 14;
    private static final int ICON_W = 16;
    private static final int INDENT = 8;
    private static final int CHEV_W = 8;
    private static final int MENU_ITEM_H = 12;

    private enum Mode { NONE, NEW_FILE, NEW_FOLDER, RENAME }

    private enum MenuItem {
        NEW_FILE("New File"), NEW_FOLDER("New Folder"), RENAME("Rename"), DELETE("Delete");
        final String label;
        MenuItem(String label) { this.label = label; }
    }

    /** path == null && !edit is the special "<< Program" row. edit == true is the inline name box. */
    private record Row(@Nullable String path, String name, boolean dir, int depth, boolean edit) {}

    private final BlockPos pos;
    private final Consumer<String> onOpen;                 // null argument = the computer's program
    private final BiConsumer<String, String> onRenamed;    // (oldPath, newPath)

    private final List<Row> rows = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();
    private final EditBox prompt;

    private String selected = "";
    private @Nullable String openFile = null;
    private int scroll = 0;
    private int seenVersion = -1;

    // inline editing
    private Mode mode = Mode.NONE;
    private String promptDir = "";
    private String renamePath = "";

    // context menu
    private boolean menuOpen = false;
    private int menuX, menuY;
    private List<MenuItem> menuItems = List.of();
    private String menuTarget = "";
    private boolean deleteArmed = false;

    // drag & drop
    private @Nullable String dragPath = null;
    private boolean dragging = false;
    private double pressX, pressY;
    private @Nullable String hoverDir = null;
    private long hoverSince = 0;

    public FileExplorerWidget(int x, int y, int w, int h, BlockPos pos,
                              Consumer<String> onOpen, BiConsumer<String, String> onRenamed) {
        super(x, y, w, h, Component.literal("Files"));
        this.pos = pos;
        this.onOpen = onOpen;
        this.onRenamed = onRenamed;

        prompt = new EditBox(Minecraft.getInstance().font, x, y, w, 9, Component.empty());
        prompt.setBordered(false);
        prompt.setMaxLength(48);
        rebuild();
    }

    // ------------------------------------------------------------------ public API

    public void refresh() { send(Action.LIST, "", ""); }

    /** Call every tick. Returns true when a new file tree arrived. */
    public boolean tick() {
        if (seenVersion == ClientFileState.treeVersion) return false;
        seenVersion = ClientFileState.treeVersion;
        if (!selected.isEmpty() && !exists(selected)) selected = "";
        rebuild();
        return true;
    }

    public boolean exists(String path) {
        for (FileTreePayload.Entry e : ClientFileState.entries) if (e.path().equals(path)) return true;
        return false;
    }

    public void setOpenFile(@Nullable String path) { this.openFile = path; }
    public boolean isPrompting() { return mode != Mode.NONE; }

    public void cancelPrompt() {
        if (mode == Mode.NONE) return;
        mode = Mode.NONE;
        prompt.setFocused(false);
        rebuild();
    }

    /** Closes the inline name box / context menu. Returns true if something was open. */
    public boolean closeOverlays() {
        boolean any = mode != Mode.NONE || menuOpen;
        cancelPrompt();
        closeMenu();
        return any;
    }

    // ------------------------------------------------------------------ tree model

    private static String parentOf(String p) {
        int i = p.lastIndexOf('/');
        return i < 0 ? "" : p.substring(0, i);
    }

    private static String nameOf(String p) { return p.substring(p.lastIndexOf('/') + 1); }

    private boolean isDir(String path) {
        for (FileTreePayload.Entry e : ClientFileState.entries) if (e.path().equals(path)) return e.dir();
        return false;
    }

    private void rebuild() {
        rows.clear();

        Map<String, List<FileTreePayload.Entry>> byParent = new HashMap<>();
        for (FileTreePayload.Entry e : ClientFileState.entries) {
            byParent.computeIfAbsent(parentOf(e.path()), k -> new ArrayList<>()).add(e);
        }
        addChildren("", 0, byParent);

        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    private void addChildren(String parent, int depth, Map<String, List<FileTreePayload.Entry>> byParent) {
        List<FileTreePayload.Entry> list = new ArrayList<>(byParent.getOrDefault(parent, List.of()));
        list.sort(Comparator.<FileTreePayload.Entry, Boolean>comparing(e -> !e.dir())
                .thenComparing(e -> nameOf(e.path()).toLowerCase()));

        boolean newHere = (mode == Mode.NEW_FILE || mode == Mode.NEW_FOLDER) && parent.equals(promptDir);
        boolean fileRowPlaced = false;

        // new folders go at the top of the folder, new files after the sub-folders
        if (newHere && mode == Mode.NEW_FOLDER) rows.add(new Row(null, "", true, depth, true));

        for (FileTreePayload.Entry e : list) {
            if (newHere && mode == Mode.NEW_FILE && !fileRowPlaced && !e.dir()) {
                rows.add(new Row(null, "", false, depth, true));
                fileRowPlaced = true;
            }

            if (mode == Mode.RENAME && e.path().equals(renamePath)) {
                rows.add(new Row(null, nameOf(e.path()), e.dir(), depth, true));
            } else {
                rows.add(new Row(e.path(), nameOf(e.path()), e.dir(), depth, false));
            }

            if (e.dir() && expanded.contains(e.path())) addChildren(e.path(), depth + 1, byParent);
        }

        if (newHere && mode == Mode.NEW_FILE && !fileRowPlaced) rows.add(new Row(null, "", false, depth, true));
    }

    // ------------------------------------------------------------------ layout

    private int listTop() { return getY() + HEADER_H + 1; }
    private int listBottom() { return getY() + height; }
    private int maxScroll() { return Math.max(0, rows.size() * ROW_H - (listBottom() - listTop())); }

    private int rowAt(double mx, double my) {
        if (mx < getX() || mx >= getX() + width || my < listTop() || my >= listBottom()) return -1;
        int i = (int) ((my - listTop() + scroll) / ROW_H);
        return i >= 0 && i < rows.size() ? i : -1;
    }

    private int headerX(int button) { return getX() + width - (2 - button) * ICON_W - 2; }

    private int headerButtonAt(double mx, double my) {
        if (my < getY() || my >= getY() + HEADER_H) return -1;
        for (int i = 0; i < 2; i++) {
            if (mx >= headerX(i) && mx < headerX(i) + ICON_W) return i;
        }
        return -1;
    }

    private int menuWidth() {
        Font font = Minecraft.getInstance().font;
        int w = font.width("Confirm delete?");
        for (MenuItem it : MenuItem.values()) w = Math.max(w, font.width(it.label));
        return w + 12;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        // Lost focus (clicked elsewhere): dismiss inline editing and the menu.
        if (!isFocused()) {
            cancelPrompt();
            closeMenu();
        }

        // Safety net: mouse released outside the widget.
        if (dragPath != null && !mc.mouseHandler.isLeftPressed()) { dragPath = null; dragging = false; }

        g.fill(getX(), getY(), getX() + width, getY() + height, 0xFF101010);

        // ---- header: title + "new file" / "new folder" icons
        g.fill(getX(), getY(), getX() + width, getY() + HEADER_H, 0xFF181818);
        g.drawString(font, "FILES", getX() + 4, getY() + 3, 0xFF999999);

        int hovBtn = (dragging || menuOpen || mode != Mode.NONE) ? -1 : headerButtonAt(mx, my);
        for (int i = 0; i < 2; i++) {
            int bx = headerX(i);
            if (i == hovBtn) g.fill(bx, getY() + 1, bx + ICON_W, getY() + HEADER_H, 0xFF3A3A3A);
            if (i == 0) drawNewFileIcon(g, bx + 2, getY() + 1, 0xFFDDDDDD, i == hovBtn ? 0xFF3A3A3A : 0xFF181818);
            else drawNewFolderIcon(g, bx + 2, getY() + 1, 0xFFDDDDDD, i == hovBtn ? 0xFF3A3A3A : 0xFF181818);
        }

        // ---- drag helpers (hover-to-expand + edge auto-scroll)
        if (dragging && isMouseOver(mx, my)) {
            int hi = rowAt(mx, my);
            Row hr = hi >= 0 ? rows.get(hi) : null;
            String hp = (hr != null && hr.dir() && hr.path() != null) ? hr.path() : null;
            long now = System.currentTimeMillis();
            if (!Objects.equals(hp, hoverDir)) {
                hoverDir = hp;
                hoverSince = now;
            } else if (hp != null && !expanded.contains(hp) && !hp.equals(dragPath) && now - hoverSince > 600) {
                expanded.add(hp);
                rebuild();
            }
            if (my < listTop() + 8) scroll = Mth.clamp(scroll - 2, 0, maxScroll());
            else if (my > listBottom() - 8) scroll = Mth.clamp(scroll + 2, 0, maxScroll());
        }

        // ---- rows
        int hover = (dragging || menuOpen) ? -1 : rowAt(mx, my);
        String dropDest = dragging ? dropTarget(mx, my) : null;

        g.enableScissor(getX(), listTop(), getX() + width, listBottom());

        // indent guides
        for (int i = 0; i < rows.size(); i++) {
            int ry = listTop() + i * ROW_H - scroll;
            if (ry + ROW_H < listTop() || ry > listBottom()) continue;
            Row r = rows.get(i);
            for (int d = 0; d < r.depth(); d++) {
                int gx = getX() + 6 + d * INDENT;
                g.fill(gx, ry, gx + 1, ry + ROW_H, 0xFF2E2E2E);
            }
        }

        for (int i = 0; i < rows.size(); i++) {
            int ry = listTop() + i * ROW_H - scroll;
            if (ry + ROW_H < listTop() || ry > listBottom()) continue;
            Row r = rows.get(i);
            int x0 = getX() + 3 + r.depth() * INDENT;

            if (r.edit()) {
                g.fill(getX(), ry, getX() + width - 4, ry + ROW_H, 0xFF1E1E1E);
                boolean bad = prompt.getValue().contains("/") || prompt.getValue().contains("\\");
                g.renderOutline(getX(), ry, width - 4, ROW_H, bad ? 0xFFFF5555 : 0xFF3399FF);
                if (r.dir()) g.drawString(font, ">", x0, ry + 2, 0xFFAAAAAA);

                int tx = x0 + CHEV_W;
                prompt.setX(tx);
                prompt.setY(ry + 2);
                prompt.setWidth(Math.max(20, getX() + width - 6 - tx));
                prompt.setHeight(9);
                prompt.render(g, mx, my, pt);
                continue;
            }

            boolean sel = r.path() != null && r.path().equals(selected);
            if (sel) g.fill(getX(), ry, getX() + width - 4, ry + ROW_H, isFocused() ? 0xFF094771 : 0xFF37373D);
            else if (i == hover) g.fill(getX(), ry, getX() + width - 4, ry + ROW_H, 0xFF2A2D2E);

            if (r.path() != null && r.dir()) {
                g.drawString(font, expanded.contains(r.path()) ? "v" : ">", x0, ry + 2, 0xFFAAAAAA);
            }

            int color = r.path() == null ? 0xFF4EBB7D
                    : r.path().equals(openFile) ? 0xFF9CDCFE
                    : r.dir() ? 0xFFDCDC8B : 0xFFFFFFFF;
            g.drawString(font, r.name(), x0 + CHEV_W, ry + 2, color);
        }

        // drop highlight (whole folder block, like VS Code)
        if (dropDest != null) {
            if (dropDest.isEmpty()) {
                g.renderOutline(getX(), listTop(), width, listBottom() - listTop(), 0xFF3399FF);
            } else {
                int d = -1;
                for (int i = 0; i < rows.size(); i++) {
                    if (dropDest.equals(rows.get(i).path())) { d = i; break; }
                }
                if (d >= 0) {
                    int end = d + 1;
                    while (end < rows.size() && rows.get(end).depth() > rows.get(d).depth()) end++;
                    int y0 = listTop() + d * ROW_H - scroll;
                    int y1 = listTop() + end * ROW_H - scroll;
                    g.fill(getX(), y0, getX() + width - 4, y1, 0x403399FF);
                    g.renderOutline(getX(), y0, width - 4, y1 - y0, 0xFF3399FF);
                }
            }
        }

        g.disableScissor();

        // ---- scrollbar
        int view = listBottom() - listTop();
        int content = rows.size() * ROW_H;
        if (content > view) {
            int th = Math.max(10, view * view / content);
            int ty = listTop() + (int) ((long) scroll * (view - th) / Math.max(1, content - view));
            g.fill(getX() + width - 3, ty, getX() + width - 1, ty + th, 0xFF666666);
        }

        // ---- context menu
        if (menuOpen) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);

            int w = menuWidth();
            int h = menuItems.size() * MENU_ITEM_H + 2;
            g.fill(menuX, menuY, menuX + w, menuY + h, 0xFF252526);
            g.renderOutline(menuX, menuY, w, h, 0xFF454545);

            int hi = menuItemAt(mx, my);
            for (int i = 0; i < menuItems.size(); i++) {
                MenuItem it = menuItems.get(i);
                int iy = menuY + 1 + i * MENU_ITEM_H;
                if (i == hi) g.fill(menuX + 1, iy, menuX + w - 1, iy + MENU_ITEM_H, 0xFF094771);

                boolean armed = it == MenuItem.DELETE && deleteArmed;
                String label = armed ? "Confirm delete?" : it.label;
                int color = it == MenuItem.DELETE ? 0xFFFF6B6B : 0xFFFFFFFF;
                g.drawString(font, label, menuX + 6, iy + 2, color);
            }
            g.pose().popPose();
        }

        // ---- drag ghost
        if (dragging && dragPath != null) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            String n = nameOf(dragPath);
            g.fill(mx + 4, my + 4, mx + 8 + font.width(n), my + 15, 0xCC094771);
            g.drawString(font, n, mx + 6, my + 6, 0xFFFFFFFF);
            g.pose().popPose();
        }

        // ---- header tooltips
        if (hovBtn >= 0) {
            g.renderTooltip(font, Component.literal(hovBtn == 0 ? "New File..." : "New Folder..."), mx, my);
        }
    }

    private void drawPlus(GuiGraphics g, int x, int y, int color, int bg) {
        g.fill(x - 1, y - 1, x + 8, y + 8, bg);             // knock out what's behind the plus
        g.fill(x, y + 3, x + 7, y + 4, color);               // horizontal
        g.fill(x + 3, y, x + 4, y + 7, color);               // vertical
    }

    private void drawNewFileIcon(GuiGraphics g, int x, int y, int color, int bg) {
        g.renderOutline(x, y + 1, 8, 10, color);             // page
        drawPlus(g, x + 6, y + 5, color, bg);
    }

    private void drawNewFolderIcon(GuiGraphics g, int x, int y, int color, int bg) {
        g.fill(x, y + 2, x + 4, y + 4, color);               // tab
        g.renderOutline(x, y + 3, 10, 8, color);             // body
        drawPlus(g, x + 7, y + 5, color, bg);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {}

    // ------------------------------------------------------------------ context menu

    private void openMenu(String target, double x, double y) {
        menuTarget = target;
        deleteArmed = false;
        menuItems = target.isEmpty()
                ? List.of(MenuItem.NEW_FILE, MenuItem.NEW_FOLDER)
                : isDir(target)
                ? List.of(MenuItem.NEW_FILE, MenuItem.NEW_FOLDER, MenuItem.RENAME, MenuItem.DELETE)
                : List.of(MenuItem.RENAME, MenuItem.DELETE);

        int w = menuWidth();
        int h = menuItems.size() * MENU_ITEM_H + 2;
        menuX = Mth.clamp((int) x, getX(), getX() + width - w);
        menuY = Mth.clamp((int) y, getY(), getY() + height - h);
        menuOpen = true;
    }

    private void closeMenu() {
        menuOpen = false;
        deleteArmed = false;
    }

    private int menuItemAt(double mx, double my) {
        if (!menuOpen) return -1;
        int w = menuWidth();
        if (mx < menuX || mx >= menuX + w
                || my < menuY + 1 || my >= menuY + 1 + menuItems.size() * MENU_ITEM_H) return -1;
        return (int) ((my - menuY - 1) / MENU_ITEM_H);
    }

    private void activateMenuItem(MenuItem item) {
        selected = menuTarget;
        switch (item) {
            case NEW_FILE -> { closeMenu(); startPrompt(Mode.NEW_FILE, ""); }
            case NEW_FOLDER -> { closeMenu(); startPrompt(Mode.NEW_FOLDER, ""); }
            case RENAME -> { closeMenu(); startPrompt(Mode.RENAME, nameOf(menuTarget)); }
            case DELETE -> {
                if (!deleteArmed) {
                    deleteArmed = true;          // second click confirms
                } else {
                    send(Action.DELETE, menuTarget, "");
                    selected = "";
                    closeMenu();
                }
            }
        }
    }

    // ------------------------------------------------------------------ mouse

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!isMouseOver(mx, my)) return false;
        dragPath = null;
        dragging = false;

        // Context menu gets first pick of the click.
        if (menuOpen) {
            int item = menuItemAt(mx, my);
            if (item >= 0) {
                if (button == 0) activateMenuItem(menuItems.get(item));
                return true;
            }
            closeMenu();   // clicked elsewhere: close, then handle the click normally
        }

        // Inline name box is open.
        if (mode != Mode.NONE) {
            int ri = rowAt(mx, my);
            if (ri >= 0 && rows.get(ri).edit()) {
                return prompt.isMouseOver(mx, my) ? prompt.mouseClicked(mx, my, button) : true;
            }
            confirmPrompt();   // clicking away commits, like VS Code
            return true;
        }

        // Header icons.
        if (my < getY() + HEADER_H) {
            int b = headerButtonAt(mx, my);
            if (button == 0 && b >= 0) startPrompt(b == 0 ? Mode.NEW_FILE : Mode.NEW_FOLDER, "");
            return true;
        }

        int i = rowAt(mx, my);

        // Right click -> context menu.
        if (button == 1) {
            if (i < 0) {
                selected = "";
                openMenu("", mx, my);
            } else {
                Row r = rows.get(i);
                if (r.path() == null) return true;
                selected = r.path();
                openMenu(r.path(), mx, my);
            }
            return true;
        }

        if (button != 0) return true;

        if (i < 0) { selected = ""; return true; }

        Row r = rows.get(i);
        if (r.path() == null) { onOpen.accept(null); return true; }

        selected = r.path();
        dragPath = r.path();
        pressX = mx;
        pressY = my;
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragPath == null || button != 0) return false;
        if (!dragging && Math.hypot(mx - pressX, my - pressY) > 4) dragging = true;
        return true;
    }

    /** Where would a drop at this position move the dragged entry to? null = not a valid drop. */
    private @Nullable String dropTarget(double mx, double my) {
        if (dragPath == null || !isMouseOver(mx, my) || my < listTop() || my >= listBottom()) return null;

        int i = rowAt(mx, my);
        String dest;
        if (i < 0) {
            dest = "";                                   // empty space = root
        } else {
            Row t = rows.get(i);
            if (t.path() == null) return null;
            dest = t.dir() ? t.path() : parentOf(t.path());   // dropping on a file = its folder
        }

        if (dest.equals(parentOf(dragPath)) || dest.equals(dragPath) || dest.startsWith(dragPath + "/")) {
            return null;
        }
        return dest;
    }

    /** Idempotent: safe if both the screen and the event system call it. */
    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragPath == null || button != 0) return false;
        String src = dragPath;
        boolean wasDrag = dragging;
        String dest = wasDrag ? dropTarget(mx, my) : null;
        dragPath = null;
        dragging = false;
        hoverDir = null;

        if (!wasDrag) {
            if (isDir(src)) {
                if (!expanded.remove(src)) expanded.add(src);
                rebuild();
            } else {
                onOpen.accept(src);
            }
            return true;
        }

        if (dest != null) {
            send(Action.MOVE, src, dest);
            String newPath = dest.isEmpty() ? nameOf(src) : dest + "/" + nameOf(src);
            onRenamed.accept(src, newPath);
            if (!dest.isEmpty()) expanded.add(dest);
            selected = newPath;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (!isMouseOver(mx, my)) return false;
        scroll = Mth.clamp(scroll - (int) (sy * ROW_H * 2), 0, maxScroll());
        return true;
    }

    // ------------------------------------------------------------------ keyboard

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (mode != Mode.NONE) {
            if (key == 257 || key == 335) { confirmPrompt(); return true; }   // Enter
            if (key == 256) { cancelPrompt(); return true; }                  // Esc
            prompt.keyPressed(key, scan, mods);
            return true;     // swallow everything so 'E' etc. don't close the screen
        }

        if (menuOpen && key == 256) { closeMenu(); return true; }

        if (!selected.isEmpty()) {
            if (key == 291) { startPrompt(Mode.RENAME, nameOf(selected)); return true; }   // F2
            if (key == 261) {                                                              // Delete
                for (int i = 0; i < rows.size(); i++) {
                    if (selected.equals(rows.get(i).path())) {
                        openMenu(selected, getX() + 10 + rows.get(i).depth() * INDENT,
                                listTop() + (i + 1) * ROW_H - scroll);
                        deleteArmed = true;      // shows "Confirm delete?"
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (mode == Mode.NONE) return false;
        prompt.charTyped(c, mods);
        return true;
    }

    // ------------------------------------------------------------------ actions

    private void startPrompt(Mode m, String initial) {
        closeMenu();

        if (m == Mode.RENAME) {
            if (selected.isEmpty()) return;
            renamePath = selected;
        } else {
            promptDir = selected.isEmpty() ? "" : isDir(selected) ? selected : parentOf(selected);
            if (!promptDir.isEmpty()) expanded.add(promptDir);
        }

        mode = m;
        prompt.setValue(initial);
        if (m == Mode.RENAME && !isDir(renamePath)) {
            // select just the name, not the extension (like VS Code)
            int dot = initial.lastIndexOf('.');
            prompt.setCursorPosition(dot > 0 ? dot : initial.length());
            prompt.setHighlightPos(0);
        }
        prompt.setFocused(true);
        rebuild();

        // make sure the inline row is on screen
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).edit()) continue;
            int top = i * ROW_H;
            int view = listBottom() - listTop();
            if (top < scroll) scroll = top;
            else if (top + ROW_H > scroll + view) scroll = top + ROW_H - view;
            scroll = Mth.clamp(scroll, 0, maxScroll());
            break;
        }
    }

    private void confirmPrompt() {
        String value = prompt.getValue().trim();
        if (value.contains("/") || value.contains("\\")) return;   // keep the box open

        Mode m = mode;
        String dir = promptDir;
        String ren = renamePath;
        cancelPrompt();
        if (value.isEmpty()) return;

        switch (m) {
            case NEW_FILE -> {
                String fileName = withDefaultExtension(value);
                send(Action.NEW_FILE, dir, fileName);
                selected = dir.isEmpty() ? fileName : dir + "/" + fileName;
            }
            case NEW_FOLDER -> {
                send(Action.MKDIR, dir, value);
                selected = dir.isEmpty() ? value : dir + "/" + value;
            }
            case RENAME -> {
                if (ren.isEmpty() || value.equals(nameOf(ren))) return;
                String parent = parentOf(ren);
                String newPath = parent.isEmpty() ? value : parent + "/" + value;
                send(Action.RENAME, ren, value);
                onRenamed.accept(ren, newPath);
                if (expanded.remove(ren)) expanded.add(newPath);
                selected = newPath;
            }
            default -> { }
        }
    }

    private void send(Action action, String path, String extra) {
        PacketDistributor.sendToServer(new FileActionPayload(pos, action, path, extra));
    }

    private static String withDefaultExtension(String name) {
        int dot = name.lastIndexOf('.');
        // no dot, or only a leading dot (".gitignore") or trailing dot ("file.") -> add .txt
        if (dot <= 0 || dot == name.length() - 1) {
            return name.endsWith(".") ? name + "txt" : name + ".txt";
        }
        return name;
    }
}