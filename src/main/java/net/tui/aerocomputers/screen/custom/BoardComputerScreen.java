package net.tui.aerocomputers.screen.custom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.client.ClientFileState;
import net.tui.aerocomputers.network.BoardComputerControlPayload;
import net.tui.aerocomputers.network.FileActionPayload;
import net.tui.aerocomputers.screen.CodeEditorWidget;
import net.tui.aerocomputers.screen.FileExplorerWidget;
import net.tui.aerocomputers.screen.HelpWidget;
import net.tui.aerocomputers.screen.PopupSlot;
import net.tui.aerocomputers.script.ComputerFiles;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

public class BoardComputerScreen extends AbstractContainerScreen<BoardComputerMenu> {
    private CodeEditorWidget editor;

    private static final int MENU_BAR_HEIGHT = 24;
    private static final int FILE_EXPLORER_X = 8;
    private static final int FILE_EXPLORER_Y = 34;
    private static final int FILE_EXPLORER_WIDTH = 128;
    private int fileExplorerHeight;
    private int editorWidth;
    private int editorHeight;

    private EditBox boardNameBox;
    private int lastEditorCompletionUpdateId = -1;
    private HelpWidget helpWidget;

    private FileExplorerWidget fileExplorer;
    /** The file shown in the editor. null means no file is open. */
    private @Nullable String currentFile = null;
    private @Nullable String pendingOpen = null;
    private @Nullable String resumeFile = null;
    private int lastFileContentVersion = 0;
    private boolean fileMenuOpen = false;
    private boolean editMenuOpen = false;
    private Button fileImportButton;
    private Button fileExportButton;
    private Button fileSaveButton;
    private Button runStateButton;
    private Button restartButton;


    public BoardComputerScreen(BoardComputerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 640;
        this.imageHeight = 360;
        this.inventoryLabelY = -1000;
        this.titleLabelY = -1000;
    }

    @Override
    protected void init() {
        // AbstractContainerScreen uses GUI-scaled dimensions, so compute the layout
        // from the current window size every time the screen is opened or resized.
        // Use the full available GUI-scaled window; Minecraft recalculates width/height
        // when the GUI scale, resolution, or fullscreen state changes.
        this.imageWidth = this.width;
        this.imageHeight = this.height;
        this.fileExplorerHeight = Math.max(40, imageHeight - FILE_EXPLORER_Y - 8);
        this.editorHeight = fileExplorerHeight;
        this.editorWidth = Math.max(40, imageWidth - 150);
        super.init();

        currentFile = null;
        pendingOpen = null;
        lastFileContentVersion = ClientFileState.contentVersion;

        savedOnClose = false;

        // VS Code-style menu bar.
        addRenderableWidget(Button.builder(Component.literal("File"), btn -> {
            fileMenuOpen = !fileMenuOpen;
            editMenuOpen = false;
            updateMenuVisibility();
        }).bounds(leftPos + 6, topPos + 4, 42, 18).build());

        addRenderableWidget(Button.builder(Component.literal("Edit"), btn -> {
            editMenuOpen = !editMenuOpen;
            fileMenuOpen = false;
            updateMenuVisibility();
        }).bounds(leftPos + 50, topPos + 4, 42, 18).build());

        addRenderableWidget(Button.builder(Component.literal("Help"), btn -> {
                    fileMenuOpen = false;
                    editMenuOpen = false;
                    updateMenuVisibility();
                    openHelp("help.aero.basics.text");
                })
                .bounds(leftPos + 94, topPos + 4, 46, 18).build());

        addRenderableWidget(Button.builder(Component.literal("Exit"), btn -> onClose())
                .bounds(leftPos + imageWidth - 52, topPos + 4, 46, 18).build());

        // Edit menu contents.
        boardNameBox = new EditBox(font, leftPos + 52, topPos + 27, 128, 18, Component.empty());
        boardNameBox.setValue(menu.blockEntity.getComputerName());
        boardNameBox.setMaxLength(32);
        boardNameBox.setTextColor(0xFFFFFFFF);
        boardNameBox.setHint(Component.literal("Computer name"));
        boardNameBox.setResponder(name -> PacketDistributor.sendToServer(new BoardComputerControlPayload(
                menu.blockEntity.getBlockPos(), BoardComputerControlPayload.Action.NAME, name)));

        runStateButton = Button.builder(Component.literal(menu.blockEntity.isRunning() ? "Pause" : "Run"), btn -> {
            boolean newState = !menu.blockEntity.isRunning();
            PacketDistributor.sendToServer(new BoardComputerControlPayload(menu.blockEntity.getBlockPos(),
                    newState ? BoardComputerControlPayload.Action.PLAY : BoardComputerControlPayload.Action.PAUSE, ""));
            btn.setMessage(Component.literal(newState ? "Pause" : "Run"));
        }).bounds(leftPos + 52, topPos + 48, 62, 18).build();

        restartButton = Button.builder(Component.literal("Restart"), btn -> {
            saveCurrentFile();
            PacketDistributor.sendToServer(new BoardComputerControlPayload(
                    menu.blockEntity.getBlockPos(), BoardComputerControlPayload.Action.RESTART, ""));
        }).bounds(leftPos + 118, topPos + 48, 62, 18).build();

        fileImportButton = Button.builder(Component.literal("Import..."), btn -> {
            fileMenuOpen = false;
            updateMenuVisibility();
            importCurrentFile();
        }).bounds(leftPos + 6, topPos + MENU_BAR_HEIGHT + 2, 90, 18).build();
        fileExportButton = Button.builder(Component.literal("Export..."), btn -> {
            fileMenuOpen = false;
            updateMenuVisibility();
            exportCurrentFile();
        }).bounds(leftPos + 6, topPos + MENU_BAR_HEIGHT + 22, 90, 18).build();
        fileSaveButton = Button.builder(Component.literal("Save  Ctrl+S"), btn -> {
            saveCurrentFile();
            fileMenuOpen = false;
            updateMenuVisibility();
            if (currentFile != null && minecraft != null && minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal("Saved " + currentFile), true);
            }
        }).bounds(leftPos + 6, topPos + MENU_BAR_HEIGHT + 42, 90, 18).build();

        editor = new CodeEditorWidget(leftPos + 142, topPos + FILE_EXPLORER_Y, editorWidth, editorHeight, "",
                menu.blockEntity.getEditorCompletions(), this::openHelp);
        editor.setReadOnly(true);
        editor.setPythonMode(false);
        editor.setCarriedSupplier(menu::getCarried);
        addRenderableWidget(editor);
        setInitialFocus(editor);

        fileExplorer = new FileExplorerWidget(leftPos + FILE_EXPLORER_X, topPos + FILE_EXPLORER_Y,
                FILE_EXPLORER_WIDTH, fileExplorerHeight, menu.blockEntity.getBlockPos(),
                this::onExplorerOpen, this::onExplorerRenamed);
        addRenderableWidget(fileExplorer);

        // Add dropdown controls last so they render above the editor and explorer.
        addRenderableWidget(fileImportButton);
        addRenderableWidget(fileExportButton);
        addRenderableWidget(fileSaveButton);
        addRenderableWidget(boardNameBox);
        addRenderableWidget(runStateButton);
        addRenderableWidget(restartButton);
        updateMenuVisibility();

        // Reopen the computer\'s program file (or the file that was open before a resize).
        String start = resumeFile != null ? resumeFile : menu.blockEntity.getProgramPath();
        resumeFile = null;

        fileExplorer.visible = true;
        fileExplorer.active = true;
        fileExplorer.refresh();

        if (!start.isEmpty()) {
            requestOpen(start);
        }

    }

    private void updateMenuVisibility() {
        if (fileImportButton != null) { fileImportButton.visible = fileMenuOpen; fileImportButton.active = fileMenuOpen; }
        if (fileExportButton != null) { fileExportButton.visible = fileMenuOpen; fileExportButton.active = fileMenuOpen; }
        if (fileSaveButton != null) { fileSaveButton.visible = fileMenuOpen; fileSaveButton.active = fileMenuOpen; }
        if (boardNameBox != null) { boardNameBox.visible = editMenuOpen; boardNameBox.active = editMenuOpen; }
        if (runStateButton != null) { runStateButton.visible = editMenuOpen; runStateButton.active = editMenuOpen; }
        if (restartButton != null) { restartButton.visible = editMenuOpen; restartButton.active = editMenuOpen; }
    }

    private void importCurrentFile() {
        if (currentFile == null) {
            if (minecraft != null && minecraft.player != null) minecraft.player.displayClientMessage(
                    Component.literal("Open a file before importing."), true);
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(MemoryUtil.memAddress(stack.UTF8("*.txt;*.py"))).flip();
            String path = TinyFileDialogs.tinyfd_openFileDialog("Import into current file", "", filters,
                    "Text/Python files", false);
            if (path != null) loadData(Files.readString(Paths.get(path)));
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void exportCurrentFile() {
        if (currentFile == null) {
            if (minecraft != null && minecraft.player != null) minecraft.player.displayClientMessage(
                    Component.literal("Open a file before exporting."), true);
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(MemoryUtil.memAddress(stack.UTF8("*.txt;*.py"))).flip();
            String suggested = currentFile.substring(currentFile.lastIndexOf('/') + 1);
            String path = TinyFileDialogs.tinyfd_saveFileDialog("Export current file", suggested, filters,
                    "Text/Python files");
            if (path != null) Files.writeString(Paths.get(path), saveData());
        } catch (IOException e) { e.printStackTrace(); }
    }

    // =====================================================================
    // Files: whatever is open in the editor is the computer's program
    // =====================================================================

    private static boolean isPython(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".py");
    }

    private void requestOpen(String path) {
        pendingOpen = path;
        PacketDistributor.sendToServer(new FileActionPayload(
                menu.blockEntity.getBlockPos(), FileActionPayload.Action.READ, path, ""));
    }

    private void onExplorerOpen(@Nullable String path) {
        if (path == null || path.equals(currentFile)) return;
        saveCurrentFile();          // autosave the file we are leaving
        requestOpen(path);          // the server also makes it the program
    }

    private void onExplorerRenamed(String oldPath, String newPath) {
        if (currentFile != null
                && (currentFile.equals(oldPath) || currentFile.startsWith(oldPath + "/"))) {
            currentFile = newPath + currentFile.substring(oldPath.length());
            fileExplorer.setOpenFile(currentFile);
        }
    }

    private void applyFile(String path, String content) {
        currentFile = path;
        editor.setText(content);
        editor.setPythonMode(isPython(path));      // highlighting + autocomplete only for .py
        editor.setReadOnly(false);
        var lvl = menu.blockEntity.getLevel();
        editor.setFoldKey((lvl != null ? lvl.dimension().location().toString() : "?")
                + "@" + menu.blockEntity.getBlockPos().asLong() + ":" + path);
        fileExplorer.setOpenFile(path);
    }

    private void clearFile() {
        currentFile = null;
        editor.setText("");
        editor.setPythonMode(false);
        editor.setReadOnly(true);
        fileExplorer.setOpenFile(null);
    }

    private void saveCurrentFile() {
        if (currentFile == null || editor == null) return;
        String text = editor.getText();
        if (text.getBytes(StandardCharsets.UTF_8).length > ComputerFiles.MAX_TEXT_BYTES) {
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.literal("File too large to save (max 32 KB)"), true);
            }
            return;
        }
        PacketDistributor.sendToServer(new FileActionPayload(
                menu.blockEntity.getBlockPos(), FileActionPayload.Action.WRITE, currentFile, text));
    }

    /** Autosave before the screen is rebuilt; init() reopens the same file. */
    @Override
    public void resize(Minecraft mc, int w, int h) {
        saveCurrentFile();
        resumeFile = currentFile;
        super.resize(mc, w, h);
    }

    // =====================================================================

    private void openHelp(String translationKey) {
        if (helpWidget != null) {
            removeWidget(helpWidget);
        }

        helpWidget = new HelpWidget(
                (width - 300) / 2,
                (height - 180) / 2,
                300,
                180,
                menu.blockEntity.getEditorCompletions(),
                translationKey
        );

        addRenderableWidget(helpWidget);
    }

    /** Imports plain text into the currently open file. */
    public void loadData(String data) {
        if (currentFile == null) {
            AeroComputers.LOGGER.warn("Open a file before importing text.");
            return;
        }
        editor.setText(data);
        saveCurrentFile();
    }

    /** Exports the currently visible editor text. */
    public String saveData() {
        return currentFile != null && editor != null ? editor.getText() : "";
    }

    @Override
    public void containerTick() {
        super.containerTick();

        int completionId = menu.blockEntity.getEditorCompletionUpdateId();
        if (completionId != lastEditorCompletionUpdateId) {
            lastEditorCompletionUpdateId = completionId;
            if (editor != null) {
                editor.updateCompletions(menu.blockEntity.getEditorCompletions());
            }
        }

        // The file tree stays visible beside the editor.
        if (fileExplorer != null) {
            if (fileExplorer.tick() && currentFile != null && !fileExplorer.exists(currentFile)) {
                clearFile();
            }
        }

        if (ClientFileState.contentVersion != lastFileContentVersion) {
            lastFileContentVersion = ClientFileState.contentVersion;
            if (pendingOpen != null && pendingOpen.equals(ClientFileState.openPath)) {
                String path = pendingOpen;
                pendingOpen = null;
                applyFile(path, ClientFileState.openContent);
            }
        }
    }

    @Override
    public void removed() {
        if (!savedOnClose) saveCurrentFile();
        saveCurrentFile();   // autosave: the open file is written back when the GUI closes

        PacketDistributor.sendToServer(new BoardComputerControlPayload(
                menu.blockEntity.getBlockPos(),
                BoardComputerControlPayload.Action.RESTART,
                ""));

        super.removed();
    }

    private boolean savedOnClose = false;

    @Override
    public void onClose() {
        saveCurrentFile();        // muss VOR super.onClose() passieren, das schickt das Close-Paket
        savedOnClose = true;
        super.onClose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (helpWidget != null && helpWidget.visible
                && helpWidget.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        // Dispatch dropdown clicks first. Drawing controls on top does not change
        // Minecraft's normal widget hit-testing order, so consume these clicks here.
        if (fileMenuOpen) {
            int x = leftPos + 4;
            int y = topPos + MENU_BAR_HEIGHT;
            int w = 104;
            int h = 62;
            if (mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h) {
                if (fileImportButton.mouseClicked(mouseX, mouseY, button)) return true;
                if (fileExportButton.mouseClicked(mouseX, mouseY, button)) return true;
                if (fileSaveButton.mouseClicked(mouseX, mouseY, button)) return true;
                return true; // consume clicks on the empty menu background
            }
        }

        if (editMenuOpen) {
            int x = leftPos + 48;
            int y = topPos + MENU_BAR_HEIGHT;
            int w = 138;
            int h = 48;
            if (mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h) {
                if (boardNameBox.mouseClicked(mouseX, mouseY, button)) {
                    setFocused(boardNameBox);
                    return true;
                }
                if (runStateButton.mouseClicked(mouseX, mouseY, button)) return true;
                if (restartButton.mouseClicked(mouseX, mouseY, button)) return true;
                return true; // do not let clicks reach the editor/explorer below
            }
        }

        // Clicking outside the dropdown closes it, then normal widget handling runs.
        boolean onMenuBar = mouseY >= topPos && mouseY <= topPos + MENU_BAR_HEIGHT;
        if ((fileMenuOpen || editMenuOpen) && !onMenuBar) {
            fileMenuOpen = false;
            editMenuOpen = false;
            updateMenuVisibility();
        }

        boolean result = super.mouseClicked(mouseX, mouseY, button);
        if (editor != null && !(mouseX >= editor.getX() && mouseX <= editor.getX() + editor.getWidth()
                && mouseY >= editor.getY() && mouseY <= editor.getY() + editor.getHeight())) {
            editor.setFocused(false);
        }
        return result;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {

        if (keyCode == 256 && fileExplorer != null && fileExplorer.isPrompting()) {
            fileExplorer.cancelPrompt();
            return true;
        }

        if (keyCode == 83 && hasControlDown() && currentFile != null) {
            saveCurrentFile();
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal("Saved " + currentFile), true);
            }
            return true;
        }

        if (keyCode == 256 && editor != null && editor.isAutocompleteOpen()) {
            editor.closeAutocomplete();
            return true;
        }

        if (keyCode == 256 && helpWidget != null) {
            removeWidget(helpWidget);
            helpWidget = null;
            return true;
        }


        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (fileExplorer != null && fileExplorer.visible
                && fileExplorer.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        if (editor != null && editor.isFocused()) {
            if (editor.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = fileExplorer != null && fileExplorer.mouseReleased(mouseX, mouseY, button);
        return super.mouseReleased(mouseX, mouseY, button) || handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (helpWidget != null && helpWidget.visible
                && helpWidget.isMouseOver(mouseX, mouseY)) {
            return helpWidget.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        if (fileExplorer != null && fileExplorer.visible
                && fileExplorer.isMouseOver(mouseX, mouseY)) {
            return fileExplorer.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }


        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF1E1E1E);
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + MENU_BAR_HEIGHT, 0xFF252526);
        guiGraphics.fill(leftPos + FILE_EXPLORER_X - 2, topPos + FILE_EXPLORER_Y - 2,
                leftPos + FILE_EXPLORER_X + FILE_EXPLORER_WIDTH + 2,
                topPos + FILE_EXPLORER_Y + fileExplorerHeight + 2, 0xFF252526);
        guiGraphics.fill(leftPos + 138, topPos + FILE_EXPLORER_Y - 2,
                leftPos + 139, topPos + FILE_EXPLORER_Y + fileExplorerHeight + 2, 0xFF3C3C3C);
    }

    private void syncPopupSlots() {
        int[] o = editor == null ? null : editor.freqInventoryOrigin();

        for (int i = 0; i < menu.popupSlots.size(); i++) {
            PopupSlot s = menu.popupSlots.get(i);
            if (o == null) {
                s.setEnabled(false);
                continue;
            }
            int col = i < 27 ? i % 9 : i - 27;
            int y = i < 27 ? o[1] + (i / 9) * 18 : o[1] + 58;     // hotbar sits 4px below the main rows
            s.moveTo(o[0] + col * 18 + 1 - leftPos, y + 1 - topPos);
            s.setEnabled(true);
        }
    }

    @Override
    public void render(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        syncPopupSlots();
        renderBackground(graphics, mouseX, mouseY, partialTick);

        super.render(graphics, mouseX, mouseY, partialTick);

        // Draw dropdowns above all underlying widgets and captions.
        renderDropdownOverlays(graphics, mouseX, mouseY, partialTick);

        // Draw captions only after the dropdown overlay, and only when
        // they are outside the open dropdown area.
        boolean dropdownOpen = fileMenuOpen || editMenuOpen;

        String label;
        int labelColor = 0xFFAAAAAA;

        if (currentFile == null) {
            label = "No file open - select a file";
        } else if (isPython(currentFile)) {
            label = currentFile + "  (running this file)";
        } else {
            label = currentFile + "  (not a .py file - will not run)";
            labelColor = 0xFFDDAA55;
        }

        int captionY = topPos + 25;

        // The captions are below the menu bar. Avoid drawing them over
        // an open dropdown if their horizontal area intersects it.
        if (!dropdownOpen) {
            graphics.drawString(
                    font, label,
                    leftPos + 142, captionY,
                    labelColor, false
            );

            graphics.drawString(
                    font, "EXPLORER",
                    leftPos + 12, captionY,
                    0xFFBBBBBB, false
            );
        }

        if (helpWidget != null && helpWidget.visible) {
            helpWidget.render(graphics, mouseX, mouseY, partialTick);
        }

        renderTooltip(graphics, mouseX, mouseY);
    }

    private void renderDropdownOverlays(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        graphics.flush();

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 2000.0F);

        if (fileMenuOpen) {
            int x = leftPos + 4;
            int y = topPos + MENU_BAR_HEIGHT;
            int menuWidth = 104;
            int menuHeight = 62;

            graphics.fill(
                    x, y, x + menuWidth, y + menuHeight,
                    0xFF252526
            );
            graphics.renderOutline(
                    x, y, menuWidth, menuHeight,
                    0xFF454545
            );

            if (fileImportButton != null) {
                fileImportButton.render(graphics, mouseX, mouseY, partialTick);
            }
            if (fileExportButton != null) {
                fileExportButton.render(graphics, mouseX, mouseY, partialTick);
            }
            if (fileSaveButton != null) {
                fileSaveButton.render(graphics, mouseX, mouseY, partialTick);
            }
        }

        if (editMenuOpen) {
            int x = leftPos + 48;
            int y = topPos + MENU_BAR_HEIGHT;
            int menuWidth = 138;
            int menuHeight = 48;

            graphics.fill(
                    x, y, x + menuWidth, y + menuHeight,
                    0xFF252526
            );
            graphics.renderOutline(
                    x, y, menuWidth, menuHeight,
                    0xFF454545
            );

            if (boardNameBox != null) {
                boardNameBox.render(graphics, mouseX, mouseY, partialTick);
            }
            if (runStateButton != null) {
                runStateButton.render(graphics, mouseX, mouseY, partialTick);
            }
            if (restartButton != null) {
                restartButton.render(graphics, mouseX, mouseY, partialTick);
            }
        }

        graphics.flush();
        graphics.pose().popPose();
    }
}
