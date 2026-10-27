package net.tui.aerocomputers.screen.custom;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.block.entity.BoardComputerBlockEntity;
import net.tui.aerocomputers.network.BoardComputerChannelPayload;
import net.tui.aerocomputers.network.BoardComputerControlPayload;
import net.tui.aerocomputers.network.BoardComputerEditPayload;
import net.tui.aerocomputers.network.BoardComputerVariablePayload;
import net.tui.aerocomputers.screen.CodeEditorWidget;
import net.tui.aerocomputers.screen.HelpWidget;
import net.tui.aerocomputers.screen.ItemIconButton;
import org.checkerframework.checker.units.qual.A;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class BoardComputerScreen extends AbstractContainerScreen<BoardComputerMenu> {
    private CodeEditorWidget editor;

    private enum Tab { MEMORY, RL_INPUT, RL_OUTPUT }
    private Tab currentTab = Tab.MEMORY;

    private Button tabMemoryButton;
    private Button tabInputButton;
    private Button tabOutputButton;

    // widgets belonging to the currently-shown side panel; cleared/rebuilt on every tab switch or data refresh
    private final List<net.minecraft.client.gui.components.AbstractWidget> panelWidgets = new ArrayList<>();

    private EditBox newVarNameBox;

    private final ResourceLocation GUI_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(AeroComputers.MOD_ID, "textures/gui/board_computer/board_computer_gui.png");

    private static final int PANEL_X = 10;
    private static final int PANEL_Y = 50;
    private static final int ROW_HEIGHT = 20;

    private static final int PANEL_WIDTH = 145;
    private static final int PANEL_HEIGHT = 140;

    private int scrollOffset = 0;

    private EditBox boardNameBox;

    private int lastEditorCompletionUpdateId = -1;

    private HelpWidget helpWidget;

    private final List<PendingChannelLoad> pendingChannelLoads = new ArrayList<>();

    private record PendingChannelLoad(
            BoardComputerChannelPayload.Direction direction,
            String name,
            String mode,
            String freqA,
            String freqB
    ) {}

    private int lastInputChannelCount = -1;
    private int lastOutputChannelCount = -1;

    public BoardComputerScreen(BoardComputerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 468;
        this.imageHeight = 329;
        this.inventoryLabelY = 237;
        this.titleLabelY = 8;
    }

    @Override
    protected void init() {
        super.init();

        boardNameBox = new EditBox(
                font,
                leftPos + 90,
                topPos + 5,
                80,
                14,
                Component.empty()
        );

        boardNameBox.setValue(menu.blockEntity.getComputerName());

        boardNameBox.setMaxLength(32);
        boardNameBox.setTextColor(0xFFFFFFFF);
        boardNameBox.setHint(Component.literal("Name..."));

        boardNameBox.setResponder(name -> {
            PacketDistributor.sendToServer(
                    new BoardComputerControlPayload(
                            menu.blockEntity.getBlockPos(),
                            BoardComputerControlPayload.Action.NAME,
                            boardNameBox.getValue()
                    )
            );
        });

        addRenderableWidget(boardNameBox);

        editor = new CodeEditorWidget(
                leftPos + 181,
                topPos + 13,
                275,
                303,
                menu.blockEntity.getProgram(),
                menu.blockEntity.getEditorCompletions(),
                this::openHelp
        );
        addRenderableWidget(editor);
        setInitialFocus(editor);

        int buttonY = topPos + 210;

        addRenderableWidget(Button.builder(
                Component.literal(menu.blockEntity.isRunning() ? "Running!" : "Paused!"),
                btn -> {
                    boolean newState = !menu.blockEntity.isRunning();
                    PacketDistributor.sendToServer(new BoardComputerControlPayload(
                            menu.blockEntity.getBlockPos(),
                            newState ? BoardComputerControlPayload.Action.PLAY : BoardComputerControlPayload.Action.PAUSE,
                            ""
                    ));
                    btn.setMessage(Component.literal(newState ? "Running!" : "Paused!"));
                }
        ).bounds(leftPos + 8, buttonY, 48, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Restart"),
                btn -> PacketDistributor.sendToServer(new BoardComputerControlPayload(
                        menu.blockEntity.getBlockPos(),
                        BoardComputerControlPayload.Action.RESTART,
                        ""
                ))
        ).bounds(leftPos + 58, buttonY, 50, 20).build());

        addRenderableWidget(
                Button.builder(
                        Component.literal("?"),
                        btn -> {
                            openHelp("help.aero.basics.text");
                        }
                ).bounds(leftPos + 110, buttonY, 20, 20).build()
        );

        addRenderableWidget(Button.builder(Component.literal("\uD83D\uDCBE"),btn -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(MemoryUtil.memAddress(stack.UTF8("*.txt")));
                filters.flip();
                String data = saveData();
                String filePath = TinyFileDialogs.tinyfd_saveFileDialog(
                        "Save My Data",
                        "code.txt",
                        filters,
                        "Text files" );
                if (filePath == null) { return; }
                try { Files.writeString( Paths.get(filePath),data);
                    System.out.println("Saved to: " + filePath); }
                catch (IOException e) { e.printStackTrace();}}}
        ).bounds(leftPos + 132, buttonY, 20, 20).build());

        addRenderableWidget(Button.builder(Component.literal("\uD83D\uDCC1"),btn -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(MemoryUtil.memAddress(stack.UTF8("*.txt")));
                filters.flip();
                String filePath = TinyFileDialogs.tinyfd_openFileDialog(
                        "Load My Data",
                        "",
                        filters,
                        "Text files",
                        false );
                if (filePath == null) { return; }
                try { String data = Files.readString( Paths.get(filePath) );
                    System.out.println("Loaded: " + data);
                    loadData(data); }
                catch (IOException e) { e.printStackTrace(); } } }
        ).bounds(leftPos + 154, buttonY, 20, 20).build() );


        int tabX = leftPos + 8;
        int tabY = topPos + 20;

        tabMemoryButton = addRenderableWidget(Button.builder(
                Component.literal("Memory"), btn -> switchTab(Tab.MEMORY)
        ).bounds(tabX, tabY, 55, 20).build());

        tabInputButton = addRenderableWidget(Button.builder(
                Component.literal("RL In"), btn -> switchTab(Tab.RL_INPUT)
        ).bounds(tabX + 57, tabY, 55, 20).build());

        tabOutputButton = addRenderableWidget(Button.builder(
                Component.literal("RL Out"), btn -> switchTab(Tab.RL_OUTPUT)
        ).bounds(tabX + 114, tabY, 55, 20).build());

        switchTab(Tab.MEMORY);

        lastInputChannelCount = menu.blockEntity.getInputChannels().size();
        lastOutputChannelCount = menu.blockEntity.getOutputChannels().size();
    }

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

    public void loadData(String data) {
        AeroComputers.LOGGER.info("Load Data");

        // Split into exactly 5 parts:
        // variables
        // inputs
        // outputs
        // computer name
        // program
        String[] dataParts = data.split(";-;-;-;-;-;-;-;-;-;", 5);

        if (dataParts.length < 5) {
            AeroComputers.LOGGER.error("Invalid save file!");
            return;
        }

        AeroComputers.LOGGER.info("Variables: " + dataParts[0]);
        AeroComputers.LOGGER.info("Inputs: " + dataParts[1]);
        AeroComputers.LOGGER.info("Outputs: " + dataParts[2]);
        AeroComputers.LOGGER.info("Name: " + dataParts[3]);

        // Variables
        if (!dataParts[0].isBlank()) {
            for (String variable : dataParts[0].split(",")) {
                variable = variable.trim();

                if (variable.isBlank()) {
                    continue;
                }

                String[] parts = variable.split("=", 2);

                if (parts.length != 2) {
                    continue;
                }

                String name = parts[0].trim();
                double value = Double.parseDouble(parts[1].trim());

                PacketDistributor.sendToServer(new BoardComputerVariablePayload(
                        menu.blockEntity.getBlockPos(),
                        BoardComputerVariablePayload.Action.ADD,
                        name,
                        ""
                ));

                PacketDistributor.sendToServer(new BoardComputerVariablePayload(
                        menu.blockEntity.getBlockPos(),
                        BoardComputerVariablePayload.Action.SET_VALUE,
                        name,
                        String.valueOf(value)
                ));
            }
        }

        loadChannels(
                dataParts[1],
                BoardComputerChannelPayload.Direction.INPUT
        );

        loadChannels(
                dataParts[2],
                BoardComputerChannelPayload.Direction.OUTPUT
        );

        // Name
        PacketDistributor.sendToServer(new BoardComputerControlPayload(
                menu.blockEntity.getBlockPos(),
                BoardComputerControlPayload.Action.NAME,
                dataParts[3]
        ));

        // Program
        editor.setText(dataParts[4]);

        PacketDistributor.sendToServer(new BoardComputerEditPayload(
                menu.blockEntity.getBlockPos(),
                editor.getText()
        ));

        rebuildPanel();
    }

    public String saveData() {
        AeroComputers.LOGGER.info("Save Data");

        PacketDistributor.sendToServer(
                new BoardComputerEditPayload(
                        menu.blockEntity.getBlockPos(),
                        editor.getText()
                )
        );

        String variables = menu.blockEntity.getPersistentVariablesSnapshot()
                .entrySet()
                .stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining(","));

        String inputs = saveChannels(menu.blockEntity.getInputChannels());
        String outputs = saveChannels(menu.blockEntity.getOutputChannels());

        return variables + ";-;-;-;-;-;-;-;-;-;" +
                inputs + ";-;-;-;-;-;-;-;-;-;" +
                outputs + ";-;-;-;-;-;-;-;-;-;" +
                menu.blockEntity.getComputerName() + ";-;-;-;-;-;-;-;-;-;" +
                menu.blockEntity.getProgram();
    }

    private String saveChannels(List<BoardComputerBlockEntity.IOChannel> channels) {
        return channels.stream()
                .map(ch -> String.join("|",
                        ch.variableName,
                        ch.mode.name(),
                        String.valueOf(ch.freqA),
                        String.valueOf(ch.freqB)
                ))
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private void loadChannels(String data, BoardComputerChannelPayload.Direction dir) {
        if (data == null || data.isBlank() || data.equals("[]")) {
            return;
        }

        // IMPORTANT:
        // Only remove channels belonging to this direction.
        if (dir == BoardComputerChannelPayload.Direction.INPUT) {
            for (var channel : new ArrayList<>(menu.blockEntity.getInputChannels())) {
                PacketDistributor.sendToServer(new BoardComputerChannelPayload(
                        menu.blockEntity.getBlockPos(),
                        BoardComputerChannelPayload.Direction.INPUT,
                        BoardComputerChannelPayload.Action.REMOVE,
                        channel.id,
                        ""
                ));
            }
        } else {
            for (var channel : new ArrayList<>(menu.blockEntity.getOutputChannels())) {
                PacketDistributor.sendToServer(new BoardComputerChannelPayload(
                        menu.blockEntity.getBlockPos(),
                        BoardComputerChannelPayload.Direction.OUTPUT,
                        BoardComputerChannelPayload.Action.REMOVE,
                        channel.id,
                        ""
                ));
            }
        }

        // Parse the saved channels.
        for (String c : data.split(";")) {
            if (c.isBlank()) {
                continue;
            }

            // Saved format:
            // name|mode|freqA|freqB
            String[] channel = c.split("\\|", 4);

            if (channel.length != 4) {
                AeroComputers.LOGGER.error(
                        "Invalid channel data: '{}'",
                        c
                );
                continue;
            }

            pendingChannelLoads.add(new PendingChannelLoad(
                    dir,
                    channel[0],
                    channel[1],
                    channel[2],
                    channel[3]
            ));
        }

        // Start creating the first channel.
        processNextPendingChannel();
    }

    private boolean channelLoadInFlight = false;

    private void processNextPendingChannel() {
        if (pendingChannelLoads.isEmpty() || channelLoadInFlight) {
            return;
        }

        channelLoadInFlight = true;
        PendingChannelLoad pending = pendingChannelLoads.getFirst();

        PacketDistributor.sendToServer(new BoardComputerChannelPayload(
                menu.blockEntity.getBlockPos(),
                pending.direction(),
                BoardComputerChannelPayload.Action.ADD,
                "",
                ""
        ));
    }

    private void switchTab(Tab tab) {
        currentTab = tab;
        tabMemoryButton.active = tab != Tab.MEMORY;
        tabInputButton.active = tab != Tab.RL_INPUT;
        tabOutputButton.active = tab != Tab.RL_OUTPUT;
        rebuildPanel();
    }

    /** Clears and rebuilds every widget in the side panel to match current block-entity state. */
    public void rebuildPanel() {
        for (var w : panelWidgets) removeWidget(w);
        panelWidgets.clear();

        switch (currentTab) {
            case MEMORY -> buildMemoryPanel();
            case RL_INPUT -> buildChannelPanel(true);
            case RL_OUTPUT -> buildChannelPanel(false);
        }

        updatePanelWidgetVisibility();
    }

    private <T extends net.minecraft.client.gui.components.AbstractWidget> T track(T widget) {
        panelWidgets.add(widget);
        return widget;
    }

    private void buildMemoryPanel() {
        int y = PANEL_Y - scrollOffset;

        for (var entry : menu.blockEntity.getPersistentVariablesSnapshot().entrySet()) {
            String name = entry.getKey();

            EditBox nameBox = track(new EditBox(font, leftPos + PANEL_X, topPos + y, 70, 14, Component.empty()));
            nameBox.setValue(name);
            nameBox.setResponder(newName -> {
                if (!newName.equals(name) && !newName.isBlank()) {
                    PacketDistributor.sendToServer(new BoardComputerVariablePayload(
                            menu.blockEntity.getBlockPos(), BoardComputerVariablePayload.Action.RENAME, name, newName));
                }
            });
            track(Button.builder(Component.literal("X"), btn -> {
                PacketDistributor.sendToServer(new BoardComputerVariablePayload(
                        menu.blockEntity.getBlockPos(), BoardComputerVariablePayload.Action.REMOVE, name, ""));
                rebuildPanel();
            }).bounds(leftPos + PANEL_X + 130, topPos + y, 16, 14).build());

            y += ROW_HEIGHT;
        }

        newVarNameBox = track(new EditBox(font, leftPos + PANEL_X, topPos + y, 100, 14, Component.empty()));
        newVarNameBox.setHint(Component.literal("new variable"));

        track(Button.builder(Component.literal("+"), btn -> {
            String name = newVarNameBox.getValue().trim();
            if (!name.isBlank()) {
                PacketDistributor.sendToServer(new BoardComputerVariablePayload(
                        menu.blockEntity.getBlockPos(), BoardComputerVariablePayload.Action.ADD, name, ""));
                rebuildPanel();
            }
        }).bounds(leftPos + PANEL_X + 105, topPos + y, 20, 14).build());
    }

    private void buildChannelPanel(boolean input) {
        var channels = input ? menu.blockEntity.getInputChannels() : menu.blockEntity.getOutputChannels();
        var dir = input ? BoardComputerChannelPayload.Direction.INPUT : BoardComputerChannelPayload.Direction.OUTPUT;

        int y = PANEL_Y - scrollOffset;



        for (var ch : channels) {
            String id = ch.id;

            EditBox nameBox = track(new EditBox(font, leftPos + PANEL_X, topPos + y, 60, 14, Component.empty()));
            nameBox.setValue(ch.variableName);
            nameBox.setResponder(newName -> {
                if (!newName.isBlank()) {
                    PacketDistributor.sendToServer(new BoardComputerChannelPayload(
                            menu.blockEntity.getBlockPos(), dir, BoardComputerChannelPayload.Action.RENAME, id, newName));
                }
            });

            track(Button.builder(Component.literal(ch.mode.name()), btn -> {
                var newMode = ch.mode == BoardComputerBlockEntity.SignalMode.S
                        ? BoardComputerBlockEntity.SignalMode.B
                        : BoardComputerBlockEntity.SignalMode.S;
                PacketDistributor.sendToServer(new BoardComputerChannelPayload(
                        menu.blockEntity.getBlockPos(), dir, BoardComputerChannelPayload.Action.SET_MODE, id, newMode.name()));
                rebuildPanel();
            }).bounds(leftPos + PANEL_X + 62, topPos + y, 10, 14).build());

            track(new ItemIconButton(
                    leftPos + PANEL_X + 74,
                    topPos + y,
                    ch.freqA,
                    btn -> PacketDistributor.sendToServer(
                            new BoardComputerChannelPayload(
                                    menu.blockEntity.getBlockPos(),
                                    dir,
                                    BoardComputerChannelPayload.Action.SET_FREQ_A,
                                    id,
                                    ""
                            )
                    )
            ));

            track(new ItemIconButton(
                    leftPos + PANEL_X + 94,
                    topPos + y,
                    ch.freqB,
                    btn -> PacketDistributor.sendToServer(
                            new BoardComputerChannelPayload(
                                    menu.blockEntity.getBlockPos(),
                                    dir,
                                    BoardComputerChannelPayload.Action.SET_FREQ_B,
                                    id,
                                    ""
                            )
                    )
            ));

            track(Button.builder(Component.literal("X"), btn -> {
                PacketDistributor.sendToServer(new BoardComputerChannelPayload(
                        menu.blockEntity.getBlockPos(), dir, BoardComputerChannelPayload.Action.REMOVE, id, ""));
                rebuildPanel();
            }).bounds(leftPos + PANEL_X + 115, topPos + y, 16, 14).build());

            y += ROW_HEIGHT;
        }

        track(Button.builder(Component.literal("+ Channel"), btn -> {
            PacketDistributor.sendToServer(new BoardComputerChannelPayload(
                    menu.blockEntity.getBlockPos(),
                    dir,
                    BoardComputerChannelPayload.Action.ADD,
                    "",
                    ""
            ));
        }).bounds(leftPos + PANEL_X, topPos + y, 80, 14).build());
    }

    private int lastChannelUpdateId = -1;
    private int lastInputSize = -1;
    private int lastOutputSize = -1;
    private int lastVariableUpdateId = -1;

    @Override
    public void containerTick() {
        super.containerTick();

        int channelId = menu.blockEntity.getChannelUpdateId();
        int variableId = menu.blockEntity.getVariableUpdateId();
        int completionId = menu.blockEntity.getEditorCompletionUpdateId();

        int in = menu.blockEntity.getInputChannels().size();
        int out = menu.blockEntity.getOutputChannels().size();

        // Handle channel count changes and pending channel loads.
        if (in != lastInputChannelCount || out != lastOutputChannelCount) {
            boolean inputAdded = in > lastInputChannelCount;
            boolean outputAdded = out > lastOutputChannelCount;

            lastInputChannelCount = in;
            lastOutputChannelCount = out;

            if (!pendingChannelLoads.isEmpty()) {
                PendingChannelLoad pending = pendingChannelLoads.getFirst();

                boolean correctDirection =
                        (pending.direction() == BoardComputerChannelPayload.Direction.INPUT && inputAdded)
                                || (pending.direction() == BoardComputerChannelPayload.Direction.OUTPUT && outputAdded);

                if (correctDirection) {
                    var channels =
                            pending.direction() == BoardComputerChannelPayload.Direction.INPUT
                                    ? menu.blockEntity.getInputChannels()
                                    : menu.blockEntity.getOutputChannels();

                    if (!channels.isEmpty()) {
                        var newChannel = channels.getLast();
                        String id = newChannel.id;

                        // Now the client actually knows the ID.
                        PacketDistributor.sendToServer(new BoardComputerChannelPayload(
                                menu.blockEntity.getBlockPos(),
                                pending.direction(),
                                BoardComputerChannelPayload.Action.RENAME,
                                id,
                                pending.name()
                        ));

                        PacketDistributor.sendToServer(new BoardComputerChannelPayload(
                                menu.blockEntity.getBlockPos(),
                                pending.direction(),
                                BoardComputerChannelPayload.Action.SET_FREQ_A,
                                id,
                                pending.freqA()
                        ));

                        PacketDistributor.sendToServer(new BoardComputerChannelPayload(
                                menu.blockEntity.getBlockPos(),
                                pending.direction(),
                                BoardComputerChannelPayload.Action.SET_FREQ_B,
                                id,
                                pending.freqB()
                        ));

                        PacketDistributor.sendToServer(new BoardComputerChannelPayload(
                                menu.blockEntity.getBlockPos(),
                                pending.direction(),
                                BoardComputerChannelPayload.Action.SET_MODE,
                                id,
                                pending.mode()
                        ));

                        // Finished this channel.
                        pendingChannelLoads.removeFirst();
                        channelLoadInFlight = false;

                        // Create the next one.
                        if (!pendingChannelLoads.isEmpty()) {
                            processNextPendingChannel();
                        }
                    }
                }
            }

            // Keep your existing pendingChannelLoads handling here.
            // Do not put the panel refresh check inside this block.
        }

        // Refresh for channel changes, frequency/mode changes,
        // and variable changes, even when channel counts stay the same.
        if (channelId != lastChannelUpdateId
                || variableId != lastVariableUpdateId
                || in != lastInputSize
                || out != lastOutputSize) {

            lastChannelUpdateId = channelId;
            lastVariableUpdateId = variableId;
            lastInputSize = in;
            lastOutputSize = out;

            rebuildPanel();
        }

        if (completionId != lastEditorCompletionUpdateId) {
            lastEditorCompletionUpdateId = completionId;

            if (editor != null) {
                editor.updateCompletions(
                        menu.blockEntity.getEditorCompletions()
                );
            }
        }
    }

    @Override
    public void removed() {
        PacketDistributor.sendToServer(new BoardComputerEditPayload(
                menu.blockEntity.getBlockPos(), editor.getText()));

        PacketDistributor.sendToServer(new BoardComputerControlPayload(
                menu.blockEntity.getBlockPos(),
                BoardComputerControlPayload.Action.RESTART,
                ""));

        super.removed();
    }

    private boolean isInPanel(double mouseX, double mouseY) {
        return mouseX >= leftPos + PANEL_X &&
                mouseX < leftPos + PANEL_X + PANEL_WIDTH &&
                mouseY >= topPos + PANEL_Y &&
                mouseY < topPos + PANEL_Y + PANEL_HEIGHT;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (helpWidget != null && helpWidget.visible) {
            if (helpWidget.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }

        boolean result = super.mouseClicked(mouseX, mouseY, button);

        if (isInPanel(mouseX, mouseY)) {
            for (var widget : panelWidgets) {
                if (widget.mouseClicked(mouseX, mouseY, button)) {
                    setFocused(widget);
                    return true;
                }
            }
        }

        if (editor != null &&
                !(mouseX >= editor.getX() && mouseX <= editor.getX() + editor.getWidth()
                        && mouseY >= editor.getY() && mouseY <= editor.getY() + editor.getHeight())) {
            editor.setFocused(false);
        }

        return result;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {

        if (keyCode == 256 && editor != null && editor.isAutocompleteOpen()) {
            editor.closeAutocomplete();
            return true; // consumed — don't let Screen's default Escape-closes-GUI logic run
        }

        if (keyCode == 256 && helpWidget != null) {
            removeWidget(helpWidget);
            helpWidget = null;
            return true;
        }

        for (var widget : panelWidgets) {
            if (widget.isFocused() && widget.keyPressed(keyCode, scanCode, modifiers))
                return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }



    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (editor != null && editor.isFocused()) {
            if (editor.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    private int getPanelRowCount() {
        return switch (currentTab) {
            case MEMORY ->
                    menu.blockEntity.getPersistentVariablesSnapshot().size() + 1;

            case RL_INPUT ->
                    menu.blockEntity.getInputChannels().size() + 1;

            case RL_OUTPUT ->
                    menu.blockEntity.getOutputChannels().size() + 1;
        };
    }

    @Override
    public boolean mouseScrolled(
            double mouseX,
            double mouseY,
            double scrollX,
            double scrollY
    ) {
        // Help popup gets scroll priority.
        if (helpWidget != null && helpWidget.visible
                && helpWidget.isMouseOver(mouseX, mouseY)) {

            return helpWidget.mouseScrolled(
                    mouseX,
                    mouseY,
                    scrollX,
                    scrollY
            );
        }

        // Normal panel scrolling
        if (mouseX >= leftPos + PANEL_X &&
                mouseX <= leftPos + PANEL_X + PANEL_WIDTH &&
                mouseY >= topPos + PANEL_Y &&
                mouseY <= topPos + PANEL_Y + PANEL_HEIGHT) {

            int maxScroll = Math.max(
                    0,
                    getPanelRowCount() * ROW_HEIGHT - PANEL_HEIGHT
            );

            scrollOffset -= (int)(scrollY * ROW_HEIGHT);
            scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);

            rebuildPanel();
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.setShaderTexture(0, GUI_TEXTURE);
        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        guiGraphics.blit(GUI_TEXTURE, x, y, 0, 0, imageWidth, imageHeight, 468, 329);
    }

    private void updatePanelWidgetVisibility() {
        int top = topPos + PANEL_Y;
        int bottom = top + PANEL_HEIGHT;

        for (var widget : panelWidgets) {
            boolean visible =
                    widget.getY() + widget.getHeight() > top &&
                            widget.getY() < bottom;

            widget.visible = visible;
            widget.active = visible;
        }
    }

    @Override
    public void render(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        renderBackground(graphics, mouseX, mouseY, partialTick);

        // Draw GUI and all normal widgets
        super.render(graphics, mouseX, mouseY, partialTick);

        // Clip panel
        graphics.enableScissor(
                leftPos + PANEL_X,
                topPos + PANEL_Y,
                leftPos + PANEL_X + PANEL_WIDTH,
                topPos + PANEL_Y + PANEL_HEIGHT
        );

        // Panel widgets are rendered again here
        for (var widget : panelWidgets) {
            widget.render(graphics, mouseX, mouseY, partialTick);
        }

        graphics.disableScissor();

        // IMPORTANT:
        // Render the help popup LAST so it is visually on top
        if (helpWidget != null && helpWidget.visible) {
            helpWidget.render(graphics, mouseX, mouseY, partialTick);
        }

        renderTooltip(graphics, mouseX, mouseY);
    }
}