package net.tui.aerocomputers.block.entity;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.content.redstone.link.RedstoneLinkFrequencySlot;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.screen.custom.BoardComputerMenu;
import net.tui.aerocomputers.script.AeroScriptApi;
import net.tui.aerocomputers.script.HudScriptApi;
import net.tui.aerocomputers.script.PythonScriptRuntime;
import net.tui.aerocomputers.network.CameraChunkManager;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.*;


public class BoardComputerBlockEntity extends KineticBlockEntity implements MenuProvider, BlockEntitySubLevelActor {

    public List<List<Double>> getAllSubLevelPositions() {
        List<List<Double>> allPositions = new ArrayList<>();

        if (this.level == null) {
            return allPositions;
        }

        SubLevelContainer container =
                ServerSubLevelContainer.getContainer(this.level);

        if (container == null) {
            return allPositions;
        }

        Vector3d currentPos = currentSubLevel.logicalPose().position();

        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel != currentSubLevel) {
                Vector3d pos = subLevel.logicalPose().position();

                allPositions.add(List.of(
                        round2(pos.x()),
                        round2(pos.y()),
                        round2(pos.z())
                ));
            }
        }

        allPositions.sort(Comparator.comparingDouble(pos -> {
            double dx = pos.get(0) - currentPos.x();
            double dy = pos.get(1) - currentPos.y();
            double dz = pos.get(2) - currentPos.z();

            return dx * dx + dy * dy + dz * dz;
        }));

        return allPositions;
    }


    private static final int PYTHON_STEPS_PER_TICK = 64;
    private static final long PYTHON_BUDGET_NANOS = 1_000_000L;

    // GraalPy runtime state. The old tokenizer/parser/evaluator are not used.
    private @Nullable PythonScriptRuntime pythonRuntime;
    private @Nullable HudScriptApi hudScriptApi;
    private @Nullable String runtimeProgram;
    private @Nullable String pythonError;

    private int transmittedSignal;
    private int receivedSignal;

    private String program = "";

    private final Map<String, Object> variables = new HashMap<>();

    private @Nullable SubLevel currentSubLevel;
    private boolean subLevelSeenThisCycle = false;

    private boolean running = true;

    private final Map<String, Object> persistentVariables = new LinkedHashMap<>();

    public enum SignalMode { S, B }

    public static class IOChannel {
        public String id;
        public int slot; // index into inputLinks/outputLinks
        public boolean active = false;
        public String variableName;
        public SignalMode mode = SignalMode.S;
        public int value = 0;
        public ItemStack freqA = ItemStack.EMPTY;
        public ItemStack freqB = ItemStack.EMPTY;
    }

    private final List<IOChannel> inputChannels = new ArrayList<>();
    private final List<IOChannel> outputChannels = new ArrayList<>();

    private static final int MAX_CHANNELS = 50;

    private LinkBehaviour[] inputLinks;
    private LinkBehaviour[] outputLinks;




    private static class DrawObject {
        String id;
        String type; // "label", "line", "poly", "rect"
        double x, y, x2, y2, w, h;
        String text = "";
        double fontSize = 1.0;
        double width = 1.0;
        double borderWidth = 1.0;
        boolean renderCenter = true;
        boolean renderBorder = true;
        List<Integer> color = new ArrayList<>(List.of(255, 255, 255));
        List<Integer> centerColor = new ArrayList<>(List.of(255, 255, 255));
        List<Integer> borderColor = new ArrayList<>(List.of(0, 0, 0));
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
    }

    private final List<DrawObject> drawObjects = new ArrayList<>();
    private int drawObjectIdCounter = 0;

    private String computerName = "";

    private int editorCompletionUpdateId = 0;

    public int getEditorCompletionUpdateId() {
        return editorCompletionUpdateId;
    }

    public record Command(String name, List<Double> args) {
        public double arg(int i) { return i < args.size() ? args.get(i) : 0.0; }
    }

    private final Map<String, Command> commands = new LinkedHashMap<>();

    private final List<String> messages = new ArrayList<>();

    public void sendMessage(String message) {
        messages.add(message);
    }

    public @Nullable Command getCommand(String name) {
        return commands.get(name);
    }

    private void issueCommand(String name, double... values) {
        List<Double> list = new ArrayList<>();
        for (double v : values) list.add(v);
        commands.put(name, new Command(name, List.copyOf(list)));
    }


    public BoardComputerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntitys.BOARDCOMPUTER_BE.get(), pos, blockState);
        setLazyTickRate(10);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider provider, boolean clientPacket) {
        super.write(tag, provider, clientPacket);

        tag.putString("Program", program);
        // Pause state is transient in saved world data, but is sent to clients for GUI display.
        if (clientPacket) tag.putBoolean("Running", running);
        tag.put("PersistentVars", writePersistentVariables());
        tag.put("InputChannels", writeChannels(inputChannels, provider));
        tag.put("OutputChannels", writeChannels(outputChannels, provider));
        tag.putInt("ChannelUpdateId", channelUpdateId);
        tag.putInt("VariableUpdateId", variableUpdateId);
        tag.putInt("EditorCompletionUpdateId", editorCompletionUpdateId);


        tag.putString("ComputerName", computerName);
        tag.put("DrawObjects", writeDrawObjects());


        tag.putInt("Transmit", transmittedSignal);
        tag.putInt("Receive", receivedSignal);
    }

    public void setProgram(String text) {
        program = text == null ? "" : text;
        variables.clear();
        resetPythonRuntime();

        editorCompletionUpdateId++;

        setChanged();

        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(
                    worldPosition,
                    getBlockState(),
                    getBlockState(),
                    3
            );
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider provider, boolean clientPacket) {
        super.read(tag, provider, clientPacket);

        program = tag.getString("Program");
        variables.clear();
        running = clientPacket && tag.contains("Running") ? tag.getBoolean("Running") : true; // Pause is not saved across world reloads.
        if (tag.contains("PersistentVars")) readPersistentVariables(tag.getCompound("PersistentVars"));
        if (tag.contains("InputChannels")) {
            inputChannels.clear();
            inputChannels.addAll(
                    readChannels(tag.getCompound("InputChannels"), provider)
            );
        }
        if (tag.contains("OutputChannels")) {
            outputChannels.clear();
            outputChannels.addAll(
                    readChannels(tag.getCompound("OutputChannels"), provider)
            );
        }
        channelUpdateId = tag.getInt("ChannelUpdateId");
        variableUpdateId = tag.getInt("VariableUpdateId");
        if (tag.contains("DrawObjects")) {
            drawObjects.clear();
            drawObjects.addAll(readDrawObjects(tag.getCompound("DrawObjects")));
        }
        if (tag.contains("ComputerName") && !tag.getString("ComputerName").isEmpty()) {
            computerName = tag.getString("ComputerName");
        }
        editorCompletionUpdateId =
                tag.getInt("EditorCompletionUpdateId");

        transmittedSignal = tag.getInt("Transmit");
        receivedSignal = tag.getInt("Receive");
    }

    @Override
    public void initialize() {
        super.initialize();

        for (IOChannel c : inputChannels) {
            if (!c.freqA.isEmpty())
                inputLinks[c.slot].setFrequency(true, c.freqA);

            if (!c.freqB.isEmpty())
                inputLinks[c.slot].setFrequency(false, c.freqB);
        }

        for (IOChannel c : outputChannels) {
            if (!c.freqA.isEmpty())
                outputLinks[c.slot].setFrequency(true, c.freqA);

            if (!c.freqB.isEmpty())
                outputLinks[c.slot].setFrequency(false, c.freqB);
        }
    }


    @Override
    public Component getDisplayName() {
        return Component.literal("Board Computer");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int i, Inventory playerInventory, Player player) {
        if (level != null && !level.isClientSide && running
                && pythonRuntime != null && pythonRuntime.isFinished()
                && program != null && !program.isBlank()) {
            restart();
        }
        return new BoardComputerMenu(i, playerInventory, this);
    }

    @Override
    public float calculateStressApplied() {
        return 16f;
    }

    @Override
    public void requestModelDataUpdate() {
        super.requestModelDataUpdate();
    }

    public String getProgram() {
        return program;
    }

    public @Nullable String getPythonError() {
        return pythonError;
    }

    private void onPythonPrint(String line) {
        if (level == null || level.isClientSide || !(level instanceof ServerLevel serverLevel)) return;
        Component message = Component.literal((computerName == null || computerName.isBlank()
                ? "[Board Computer] " : "[" + computerName + "] ") + line);
        for (Player player : serverLevel.players()) {
            player.sendSystemMessage(message);
        }
    }

    private void ensurePythonRuntime() {
        if (pythonRuntime != null || level == null || level.isClientSide) return;
        hudScriptApi = new HudScriptApi(this);
        pythonRuntime = new PythonScriptRuntime(new AeroScriptApi(this), hudScriptApi.asProxy(), this::onPythonPrint);
        runtimeProgram = null;
    }

    private void closePythonRuntime() {
        if (pythonRuntime != null) {
            try {
                pythonRuntime.close();
            } catch (Exception ignored) {
                // Runtime may already have been closed after a load/unload transition.
            }
        }
        pythonRuntime = null;
        hudScriptApi = null;
        runtimeProgram = null;
    }

    private void resetPythonRuntime() {
        closePythonRuntime();
        if (level != null && !level.isClientSide) {
            ensurePythonRuntime();
            startPythonProgram();
        }
    }

    private void startPythonProgram() {
        if (pythonRuntime == null) return;
        try {
            for (Map.Entry<String, Object> entry : persistentVariables.entrySet()) {
                pythonRuntime.setVariable(entry.getKey(), entry.getValue());
                variables.put(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                if (entry.getValue() instanceof Number || entry.getValue() instanceof String
                        || entry.getValue() instanceof Boolean || entry.getValue() instanceof List<?>) {
                    pythonRuntime.setVariable(entry.getKey(), entry.getValue());
                }
            }
            pythonRuntime.start(program);
            runtimeProgram = program;
            pythonError = null;
        } catch (RuntimeException exception) {
            pythonError = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            AeroComputers.LOGGER.error("Python compile/start error at {}: {}", worldPosition, pythonError, exception);
        }
    }

    private void runPythonSteps(int maxSteps) {
        if (!running || program == null || program.isBlank()) return;
        ensurePythonRuntime();
        if (pythonRuntime == null) return;
        // Refresh hardware inputs before each cooperative Python step.
        for (IOChannel channel : inputChannels) {
            if (channel.variableName == null || channel.variableName.isBlank()) continue;
            double value = channel.mode == SignalMode.B
                    ? (channel.value > 0 ? 1.0 : 0.0) : channel.value;
            pythonRuntime.setVariable(channel.variableName, value);
        }
        if (!Objects.equals(runtimeProgram, program)) {
            closePythonRuntime();
            ensurePythonRuntime();
            startPythonProgram();
        }
        if (pythonRuntime == null || pythonRuntime.isFinished()) {
            return;
        }

        long startedAt = System.nanoTime();
        int steps = 0;
        try {
            while (steps < maxSteps && System.nanoTime() - startedAt < PYTHON_BUDGET_NANOS) {
                if (!pythonRuntime.step()) {
                    // Script completed. Keep the computer enabled; the runtime
                    // remains finished until the player restarts it or the code changes.
                    break;
                }
                steps++;
            }
        } catch (RuntimeException exception) {
            pythonError = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            AeroComputers.LOGGER.error("Python script error at {}: {}", worldPosition, pythonError, exception);
            setChanged();
        }
    }

    private void syncPythonVariables() {
        if (pythonRuntime == null) return;
        for (String name : pythonRuntime.getVariableNames()) {
            if (name == null || name.isBlank()) continue;
            try {
                Object value = pythonRuntime.getVariable(name);
                if (value == null) continue;
                // Avoid copying opaque proxy objects (for example HUD labels) into
                // the legacy numeric-variable map.
                if (value instanceof Number || value instanceof String || value instanceof Boolean
                        || value instanceof List<?>) {
                    variables.put(name, value);
                    if (persistentVariables.containsKey(name)) {
                        persistentVariables.put(name, value);
                    }
                }
            } catch (RuntimeException ignored) {
                // Internal/runtime globals may not be representable as Java values.
            }
        }
    }

    // Methods exposed through the deliberately small AeroScriptApi facade.
    public Object scriptGetPos() {
        Vector3d pos = new Vector3d(getBlockPos().getX() + 0.5, getBlockPos().getY() + 0.5, getBlockPos().getZ() + 0.5);
        if (currentSubLevel != null) currentSubLevel.logicalPose().transformPosition(pos);
        return List.of(round2(pos.x), round2(pos.y), round2(pos.z));
    }

    public Object scriptGetRot() {
        if (currentSubLevel == null) return List.of(0.0, 0.0, 0.0);
        Quaterniond start = new Quaterniond().rotateY(Math.toRadians(-getBlockState().getValue(DirectionalKineticBlock.FACING).toYRot()));
        Quaterniond relative = start.invert().mul(new Quaterniond(currentSubLevel.logicalPose().orientation()));
        Vector3d euler = new Vector3d();
        relative.getEulerAnglesYXZ(euler);
        return List.of(round2(Math.toDegrees(euler.x)), round2(Math.toDegrees(euler.y)), round2(Math.toDegrees(euler.z)));
    }

    public Object scriptGetVel() {
        if (currentSubLevel == null) return List.of(0.0, 0.0, 0.0);
        Vector3d current = new Vector3d(getBlockPos().getX() + 0.5, getBlockPos().getY() + 0.5, getBlockPos().getZ() + 0.5);
        Vector3d previous = new Vector3d(current);
        currentSubLevel.logicalPose().transformPosition(current);
        currentSubLevel.lastPose().transformPosition(previous);
        Vector3d velocity = current.sub(previous).mul(20.0);
        return List.of(round2(velocity.x), round2(velocity.y), round2(velocity.z));
    }

    public Object scriptRadar() { return getAllSubLevelPositions(); }

    public Object scriptCommand(String name, List<Object> args) {
        switch (name) {
            case "bigc.aimangel" -> issueCommand(name,
                    args.size() > 0 ? asDouble(args.get(0)) : 0.0,
                    args.size() > 1 ? asDouble(args.get(1)) : 0.0);
            case "bigc.fire", "slrw.firechin" -> issueCommand(name,
                    args.size() > 0 ? asDouble(args.get(0)) : 0.0);
            case "bigc.aimpos", "slrw.aimchinpos" -> {
                List<Double> pos = args.size() > 0 ? toPosList(args.get(0)) : List.of(0.0, 0.0, 0.0);
                issueCommand(name, pos.get(0), pos.get(1), pos.get(2));
            }
            case "slrw.aimchinangel" -> issueCommand(name,
                    args.size() > 0 ? asDouble(args.get(0)) : 0.0,
                    args.size() > 1 ? asDouble(args.get(1)) : 0.0);
            default -> throw new IllegalArgumentException("Unknown Aero command: " + name);
        }
        return 0.0;
    }

    public Object scriptSend(String name, String message) {
        BoardComputerBlockEntity target = ComputerRegistry.get(name);
        if (target != null) target.sendMessage(message);
        return target != null;
    }

    public Object scriptRead() {
        return messages.isEmpty() ? "" : messages.remove(0);
    }

    public Object scriptClear() {
        messages.clear();
        return 0.0;
    }

    public String scriptCreateHudObject(String type, List<Object> args) {
        DrawObject obj = new DrawObject();
        obj.id = "d" + (drawObjectIdCounter++);
        switch (type) {
            case "hud.Label" -> { obj.type = "label"; obj.x = argNumber(args, 0, 0); obj.y = argNumber(args, 1, 0); obj.text = arg(args, 2) == null ? "" : arg(args, 2).toString(); obj.color = toColorList(arg(args, 3)); obj.fontSize = argNumber(args, 4, 1); }
            case "hud.Line" -> { obj.type = "line"; obj.x = argNumber(args, 0, 0); obj.y = argNumber(args, 1, 0); obj.x2 = argNumber(args, 2, 0); obj.y2 = argNumber(args, 3, 0); obj.width = argNumber(args, 4, 1); obj.color = toColorList(arg(args, 5)); }
            case "hud.Poly" -> { obj.type = "poly"; obj.xs = toDoubleList(arg(args, 0)); obj.ys = toDoubleList(arg(args, 1)); obj.borderWidth = argNumber(args, 2, 1); obj.borderColor = toColorList(arg(args, 3)); }
            case "hud.Rect" -> { obj.type = "rect"; obj.x = argNumber(args, 0, 0); obj.y = argNumber(args, 1, 0); obj.w = argNumber(args, 2, 0); obj.h = argNumber(args, 3, 0); obj.borderWidth = argNumber(args, 4, 1); obj.centerColor = toColorList(arg(args, 5)); obj.borderColor = toColorList(arg(args, 6)); obj.renderCenter = arg(args, 7) == null || asBoolean(arg(args, 7)); obj.renderBorder = arg(args, 8) == null || asBoolean(arg(args, 8)); }
            case "hud.Cam" -> { obj.type = "cam"; obj.x = argNumber(args, 0, 0); obj.y = argNumber(args, 1, 0); obj.w = argNumber(args, 2, 0); obj.h = argNumber(args, 3, 0); }
            default -> throw new IllegalArgumentException("Unknown HUD object: " + type);
        }
        drawObjects.add(obj); markDrawDirty(); return obj.id;
    }
    private static Object arg(List<Object> args, int index) { return index >= 0 && index < args.size() ? args.get(index) : null; }
    private static double argNumber(List<Object> args, int index, double fallback) { Object value = arg(args, index); return value == null ? fallback : asDouble(value); }

    public Object scriptGetHudField(String id, String field) {
        DrawObject object = findDrawObject(id);
        if (object == null) return null;
        return switch (field) {
            case "x", "x1" -> object.x; case "y", "y1" -> object.y; case "x2" -> object.x2; case "y2" -> object.y2;
            case "text" -> object.text; case "color" -> new ArrayList<>(object.color); case "center_color" -> new ArrayList<>(object.centerColor); case "border_color" -> new ArrayList<>(object.borderColor);
            case "font_size" -> object.fontSize; case "width" -> object.width; case "border_width" -> object.borderWidth; case "w" -> object.w; case "h" -> object.h;
            case "xs" -> new ArrayList<>(object.xs); case "ys" -> new ArrayList<>(object.ys); case "render_center" -> object.renderCenter; case "render_border" -> object.renderBorder; default -> null;
        };
    }

    public void scriptSetHudField(String id, String field, Object value) {
        DrawObject object = findDrawObject(id);
        if (object == null) return;
        switch (field) {
            case "x", "x1" -> object.x = asDouble(value); case "y", "y1" -> object.y = asDouble(value); case "x2" -> object.x2 = asDouble(value); case "y2" -> object.y2 = asDouble(value);
            case "text" -> object.text = asText(value); case "color" -> object.color = toColorList(value); case "center_color" -> object.centerColor = toColorList(value); case "border_color" -> object.borderColor = toColorList(value);
            case "font_size" -> object.fontSize = asDouble(value); case "width" -> object.width = asDouble(value); case "border_width" -> object.borderWidth = asDouble(value); case "w" -> object.w = asDouble(value); case "h" -> object.h = asDouble(value);
            case "xs" -> object.xs = toDoubleList(value); case "ys" -> object.ys = toDoubleList(value); case "render_center" -> object.renderCenter = asBoolean(value); case "render_border" -> object.renderBorder = asBoolean(value);
        }
        markDrawDirty();
    }

    public void scriptRemoveHudObject(String id) {
        DrawObject object = findDrawObject(id);
        if (object != null) {
            drawObjects.remove(object);
            markDrawDirty();
        }
    }

    private @Nullable DrawObject findDrawObject(String id) {
        for (DrawObject object : drawObjects) {
            if (Objects.equals(object.id, id)) return object;
        }
        return null;
    }


    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {

        if (inputLinks == null)
            inputLinks = new LinkBehaviour[MAX_CHANNELS];

        if (outputLinks == null)
            outputLinks = new LinkBehaviour[MAX_CHANNELS];

        for (int i = 0; i < MAX_CHANNELS; i++) {
            final int idx = i;

            Pair<ValueBoxTransform, ValueBoxTransform> inSlots =
                    ValueBoxTransform.Dual.makeSlots(RedstoneLinkFrequencySlot::new);

            LinkBehaviour inLink = LinkBehaviour.receiver(
                    this,
                    inSlots,
                    power -> onInputPowerChanged(idx, power)
            );

            inputLinks[i] = inLink;
            behaviours.add(inLink);


            Pair<ValueBoxTransform, ValueBoxTransform> outSlots =
                    ValueBoxTransform.Dual.makeSlots(RedstoneLinkFrequencySlot::new);

            LinkBehaviour outLink = LinkBehaviour.transmitter(
                    this,
                    outSlots,
                    () -> getOutputTransmitStrength(idx)
            );

            outputLinks[i] = outLink;
            behaviours.add(outLink);
        }
    }

    private void onInputPowerChanged(int slot, int power) {
        for (IOChannel ch : inputChannels) {
            if (ch.slot == slot) {
                ch.value = power;
                if (ch.variableName != null) {
                    double val = ch.mode == SignalMode.B ? (power > 0 ? 1.0 : 0.0) : power;
                    variables.put(ch.variableName, val);
                }
                return;
            }
        }
    }

    private int getOutputTransmitStrength(int slot) {
        for (IOChannel ch : outputChannels) {
            if (ch.slot == slot) {
                return ch.value;
            }
        }
        return 0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }



    @Override
    public void sable$tick(ServerSubLevel subLevel) {
        this.currentSubLevel = subLevel;
        this.subLevelSeenThisCycle = true;
    }


    @Override
    public void tick() {
        super.tick();

        if (!subLevelSeenThisCycle) {
            currentSubLevel = null;
        }
        subLevelSeenThisCycle = false;

        if (level == null || level.isClientSide)
            return;

        if (level instanceof ServerLevel sl && drawObjects.stream().anyMatch(o -> "cam".equals(o.type)))
            CameraChunkManager.tick(sl);

        int rpm = (int) Math.abs(getSpeed());

        if (rpm < 128)
            return;

        if (!running)
            return;

        for (IOChannel ch : inputChannels) {
            double val = ch.mode == SignalMode.B ? (ch.value > 0 ? 1.0 : 0.0) : ch.value;
            variables.put(ch.variableName, val);
        }

        if (rpm < 256) {
            double chance = rpm / 256.0;
            if (level.random.nextDouble() < chance) {
                runPythonSteps(1);
            }
        } else {
            runPythonSteps(PYTHON_STEPS_PER_TICK);
        }

        syncPythonVariables();

        for (IOChannel ch : outputChannels) {
            Object raw = variables.getOrDefault(ch.variableName, 0.0);
            double num = asDouble(raw);
            ch.value = ch.mode == SignalMode.B
                    ? (num > 0 ? 15 : 0)
                    : (int) Math.max(0, Math.min(15, num));
        }

        for (IOChannel ch : outputChannels) {
            if (outputLinks[ch.slot] != null) {
                outputLinks[ch.slot].notifySignalChange();
            }
        }


        if (hudDirty) {
            hudDirty = false;
            notifyUpdate();
        }
    }







    /**
     * Uses the shared analyzer (the same one the editor uses) to find blocks,
     * functions and classes.
     */

    /** Removes a trailing "# comment" (using the shared lexer, so '#' inside strings is kept). */
















    /**
     * Expression tokenizer. Uses the shared lossless lexer and just drops what the
     * expression parser doesn't care about.
     */














    // These are Java/Python boundary conversions, not a Python parser or evaluator.
    private static double asDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof Boolean b) return b ? 1.0 : 0.0;
        if (value == null) return 0.0;
        try { return Double.parseDouble(value.toString()); } catch (NumberFormatException ignored) { return 0.0; }
    }
    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0.0;
        return value != null && !value.toString().isEmpty() && !"false".equalsIgnoreCase(value.toString());
    }
    private static String asText(Object value) { return value == null ? "None" : value.toString(); }
    private List<Integer> toColorList(Object value) {
        List<Integer> out = new ArrayList<>(); if (value instanceof List<?> list) for (Object v : list) out.add((int) asDouble(v)); while (out.size() < 3) out.add(255); return out;
    }
    private List<Double> toPosList(Object value) {
        List<Double> out = new ArrayList<>(); if (value instanceof List<?> list) for (Object v : list) out.add(asDouble(v)); while (out.size() < 3) out.add(0.0); return out;
    }
    private List<Double> toDoubleList(Object value) {
        List<Double> out = new ArrayList<>(); if (value instanceof List<?> list) for (Object v : list) out.add(asDouble(v)); return out;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide) {
            ComputerRegistry.register(computerName, this);
            closePythonRuntime();
            ensurePythonRuntime();
            running = true; // Reloading the computer automatically resumes its program.
            if (program != null && !program.isBlank()) startPythonProgram();
        }
    }

    @Override
    public void remove() {
        closePythonRuntime();
        super.remove();
        if (level != null && !level.isClientSide) {
            ComputerRegistry.unregister(computerName, this);
        }
    }

    public void setComputerName(String name) {
        if (Objects.equals(this.computerName, name))
            return;

        boolean server = level != null && !level.isClientSide;
        if (server) ComputerRegistry.unregister(this.computerName, this);

        this.computerName = name;

        if (server) ComputerRegistry.register(name, this);

        setChanged();
        sync();
    }

    public void restart() {
        running = true;
        variables.clear();
        closePythonRuntime();
        drawObjects.clear();
        commands.clear();
        drawObjectIdCounter = 0;
        pythonError = null;
        if (level != null && !level.isClientSide) {
            ensurePythonRuntime();
            startPythonProgram();
        }
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }



    public void addPersistentVariable(String name) {
        persistentVariables.putIfAbsent(name, 0.0);
        variablesChanged();
    }

    public void removePersistentVariable(String name) {
        persistentVariables.remove(name);
        variablesChanged();
    }

    public void renamePersistentVariable(String oldName, String newName) {
        if (!persistentVariables.containsKey(oldName) || persistentVariables.containsKey(newName)) return;
        Object value = persistentVariables.remove(oldName);
        persistentVariables.put(newName, value);
        variablesChanged();
    }

    public void setPersistentVariableValue(String name, double value) {
        if (persistentVariables.containsKey(name)) {
            persistentVariables.put(name, value);
            variablesChanged();
        }
    }

    public Map<String, Object> getPersistentVariablesSnapshot() {
        return new LinkedHashMap<>(persistentVariables);
    }

    public void sync() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }


    private CompoundTag writePersistentVariables() {
        CompoundTag tag = new CompoundTag();
        for (var entry : persistentVariables.entrySet()) {
            CompoundTag varTag = new CompoundTag();
            Object value = entry.getValue();
            if (value instanceof Double d) {
                varTag.putString("type", "double");
                varTag.putDouble("value", d);
            } else if (value instanceof Boolean b) {
                varTag.putString("type", "boolean");
                varTag.putBoolean("value", b);
            } else {
                varTag.putString("type", "string");
                varTag.putString("value", String.valueOf(value));
            }
            tag.put(entry.getKey(), varTag);
        }
        return tag;
    }

    private void readPersistentVariables(CompoundTag tag) {
        persistentVariables.clear();
        for (String key : tag.getAllKeys()) {
            CompoundTag varTag = tag.getCompound(key);
            Object value = switch (varTag.getString("type")) {
                case "boolean" -> varTag.getBoolean("value");
                case "string" -> varTag.getString("value");
                default -> varTag.getDouble("value");
            };
            persistentVariables.put(key, value);
        }
    }

    public IOChannel addInputChannel() {
        for (int i = 0; i < MAX_CHANNELS; i++) {
            final int slotIndex = i;
            boolean used = inputChannels.stream().anyMatch(c -> c.slot == slotIndex);
            if (!used) {
                IOChannel ch = new IOChannel();
                ch.id = UUID.randomUUID().toString();
                ch.slot = slotIndex;
                ch.variableName = "input" + slotIndex;
                inputChannels.add(ch);
                editorCompletionUpdateId++;
                setChanged();
                sync();
                return ch;
            }
        }
        return null; // all MAX_CHANNELS slots in use
    }

    public IOChannel addOutputChannel() {
        for (int i = 0; i < MAX_CHANNELS; i++) {
            final int slotIndex = i;
            boolean used = outputChannels.stream().anyMatch(c -> c.slot == slotIndex);
            if (!used) {
                IOChannel ch = new IOChannel();
                ch.id = UUID.randomUUID().toString();
                ch.slot = slotIndex;
                ch.variableName = "output" + slotIndex;
                outputChannels.add(ch);
                editorCompletionUpdateId++;
                setChanged();
                sync();
                return ch;
            }
        }
        return null; // all MAX_CHANNELS slots in use
    }

    public void removeInputChannel(String id) {
        editChannel(inputChannels, id, c -> {
            inputLinks[c.slot].setFrequency(true, ItemStack.EMPTY);
            inputLinks[c.slot].setFrequency(false, ItemStack.EMPTY);
        });
        inputChannels.removeIf(c -> c.id.equals(id));
        editorCompletionUpdateId++;
        setChanged();
        sync();
    }

    public void removeOutputChannel(String id) {
        editChannel(outputChannels, id, c -> {
            outputLinks[c.slot].setFrequency(true, ItemStack.EMPTY);
            outputLinks[c.slot].setFrequency(false, ItemStack.EMPTY);
        });
        outputChannels.removeIf(c -> c.id.equals(id));
        editorCompletionUpdateId++;
        setChanged();
        sync();
    }

    private static void editChannel(List<IOChannel> list, String id, java.util.function.Consumer<IOChannel> editor) {
        for (IOChannel c : list) {
            if (c.id.equals(id)) {
                editor.accept(c);
                return;
            }
        }
    }

    public void setInputChannelName(String id, String name) {
        editChannel(inputChannels, id, c -> c.variableName = name);
        editorCompletionUpdateId++;
        setChanged();
        sync();
    }

    public void setOutputChannelName(String id, String name) {
        editChannel(outputChannels, id, c -> c.variableName = name);
        editorCompletionUpdateId++;
        setChanged();
        sync();
    }

    public void setInputChannelMode(String id, SignalMode mode) {
        editChannel(inputChannels, id, c -> c.mode = mode);
        channelsChanged();
    }

    public void setOutputChannelMode(String id, SignalMode mode) {
        editChannel(outputChannels, id, c -> c.mode = mode);
        channelsChanged();
    }

    public void setInputChannelFrequency(String id, boolean first, ItemStack stack) {

        ItemStack copy = stack.copy();
        copy.setCount(1);

        editChannel(inputChannels, id, c -> {

            if (first)
                c.freqA = copy;
            else
                c.freqB = copy;

            inputLinks[c.slot].setFrequency(first, copy);
        });

        channelsChanged();
    }

    public void setOutputChannelFrequency(String id, boolean first, ItemStack stack) {

        ItemStack copy = stack.copy();
        copy.setCount(1);

        editChannel(outputChannels, id, c -> {

            if (first)
                c.freqA = copy;
            else
                c.freqB = copy;

            LinkBehaviour link = outputLinks[c.slot];

            if (link != null) {
                link.setFrequency(first, copy);
            }
        });

        channelsChanged();
    }

    public List<IOChannel> getInputChannels() { return inputChannels; }
    public List<IOChannel> getOutputChannels() { return outputChannels; }


    private CompoundTag writeChannels(List<IOChannel> list, HolderLookup.Provider provider) {
        ListTag listTag = new ListTag();

        for (IOChannel c : list) {
            CompoundTag ct = new CompoundTag();

            ct.putString("id", c.id);
            ct.putInt("slot", c.slot);
            ct.putString("name", c.variableName);
            ct.putString("mode", c.mode.name());

            if (!c.freqA.isEmpty()) {
                ct.put("freqA", c.freqA.saveOptional(provider));
            }

            if (!c.freqB.isEmpty()) {
                ct.put("freqB", c.freqB.saveOptional(provider));
            }

            listTag.add(ct);
        }

        CompoundTag tag = new CompoundTag();
        tag.put("channels", listTag);

        return tag;
    }

    private List<IOChannel> readChannels(CompoundTag tag, HolderLookup.Provider provider) {
        List<IOChannel> result = new ArrayList<>();
        ListTag listTag = tag.getList("channels", Tag.TAG_COMPOUND);
        for (int i = 0; i < listTag.size(); i++) {
            CompoundTag ct = listTag.getCompound(i);
            IOChannel c = new IOChannel();
            c.id = ct.getString("id");
            c.slot = ct.getInt("slot");
            c.variableName = ct.getString("name");
            c.mode = SignalMode.valueOf(ct.getString("mode"));
            c.active = true;

            if (ct.contains("freqA")) {
                c.freqA = ItemStack.parseOptional(
                        provider,
                        ct.getCompound("freqA")
                );
            }

            if (ct.contains("freqB")) {
                c.freqB = ItemStack.parseOptional(
                        provider,
                        ct.getCompound("freqB")
                );
            }
            result.add(c);
        }
        return result;
    }

    private int channelUpdateId = 0;

    public int getChannelUpdateId() {
        return channelUpdateId;
    }

    public void channelsChanged() {
        channelUpdateId++;
        editorCompletionUpdateId++;
        setChanged();
        sync();
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        write(tag, provider, true);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {

        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(
            Connection connection,
            ClientboundBlockEntityDataPacket packet,
            HolderLookup.Provider provider
    ) {
        CompoundTag tag = packet.getTag();

        read(tag, provider, true);

        updateClientHud();
    }

    private void updateClientHud() {
        if (level == null || !level.isClientSide)
            return;

        var entries = drawObjects.stream()
                .map(o -> new net.tui.aerocomputers.client.ClientHudRegistry.DrawEntry(
                        o.type,
                        o.x, o.y,
                        o.x2, o.y2,
                        o.w, o.h,
                        o.text,
                        o.fontSize,
                        o.width,
                        o.borderWidth,
                        o.renderCenter,
                        o.renderBorder,
                        o.color,
                        o.centerColor,
                        o.borderColor,
                        o.xs,
                        o.ys
                ))
                .toList();

        net.tui.aerocomputers.client.ClientHudRegistry.update(
                getComputerName(),
                entries
        );
    }

    private int variableUpdateId = 0;

    public int getVariableUpdateId() {
        return variableUpdateId;
    }

    private void variablesChanged() {
        variableUpdateId++;
        editorCompletionUpdateId++;
        setChanged();
        sync();
    }



    private CompoundTag writeDrawObjects() {
        ListTag listTag = new ListTag();
        for (DrawObject o : drawObjects) {
            CompoundTag ct = new CompoundTag();
            ct.putString("id", o.id);
            ct.putString("type", o.type);
            ct.putDouble("x", o.x);
            ct.putDouble("y", o.y);
            ct.putDouble("x2", o.x2);
            ct.putDouble("y2", o.y2);
            ct.putString("text", o.text);
            ct.putDouble("fontSize", o.fontSize);
            ct.putDouble("width", o.width);
            ct.putDouble("borderWidth", o.borderWidth);
            ct.putDouble("w", o.w);
            ct.putDouble("h", o.h);
            ct.putBoolean("renderCenter", o.renderCenter);
            ct.putBoolean("renderBorder", o.renderBorder);
            ct.putIntArray("color", o.color.stream().mapToInt(Integer::intValue).toArray());
            ct.putIntArray("centerColor", o.centerColor.stream().mapToInt(Integer::intValue).toArray());
            ct.putIntArray("borderColor", o.borderColor.stream().mapToInt(Integer::intValue).toArray());

            ListTag xsTag = new ListTag();
            for (double v : o.xs) xsTag.add(net.minecraft.nbt.DoubleTag.valueOf(v));
            ct.put("xs", xsTag);

            ListTag ysTag = new ListTag();
            for (double v : o.ys) ysTag.add(net.minecraft.nbt.DoubleTag.valueOf(v));
            ct.put("ys", ysTag);

            listTag.add(ct);
        }
        CompoundTag tag = new CompoundTag();
        tag.put("objects", listTag);
        return tag;
    }

    private List<DrawObject> readDrawObjects(CompoundTag tag) {
        List<DrawObject> result = new ArrayList<>();
        ListTag listTag = tag.getList("objects", Tag.TAG_COMPOUND);
        for (int i = 0; i < listTag.size(); i++) {
            CompoundTag ct = listTag.getCompound(i);
            DrawObject o = new DrawObject();
            o.id = ct.getString("id");
            o.type = ct.getString("type");
            o.x = ct.getDouble("x");
            o.y = ct.getDouble("y");
            o.x2 = ct.getDouble("x2");
            o.y2 = ct.getDouble("y2");
            o.text = ct.getString("text");
            o.fontSize = ct.getDouble("fontSize");
            o.width = ct.getDouble("width");
            o.borderWidth = ct.getDouble("borderWidth");
            o.w = ct.getDouble("w");
            o.h = ct.getDouble("h");
            o.renderCenter = ct.getBoolean("renderCenter");
            o.renderBorder = ct.getBoolean("renderBorder");

            o.color = Arrays.stream(ct.getIntArray("color")).boxed().toList();
            o.centerColor = Arrays.stream(ct.getIntArray("centerColor")).boxed().toList();
            o.borderColor = Arrays.stream(ct.getIntArray("borderColor")).boxed().toList();

            ListTag xsTag = ct.getList("xs", Tag.TAG_DOUBLE);
            for (int j = 0; j < xsTag.size(); j++) o.xs.add(xsTag.getDouble(j));
            ListTag ysTag = ct.getList("ys", Tag.TAG_DOUBLE);
            for (int j = 0; j < ysTag.size(); j++) o.ys.add(ysTag.getDouble(j));

            result.add(o);
        }
        return result;
    }

    private boolean hudDirty = false;

    private void markDrawDirty() {
        setChanged();
        hudDirty = true;
    }

    public String getComputerName() {
        return computerName;
    }








    /**
     * The editor derives functions/classes/variables from the program text itself
     * (via the shared ProgramAnalyzer). The only thing it cannot know is what lives
     * outside the text: persistent variables and channel variable names.
     */
    public EditorCompletions getEditorCompletions() {
        Set<String> extra = new LinkedHashSet<>(persistentVariables.keySet());

        addChannelVariables(extra, inputChannels);
        addChannelVariables(extra, outputChannels);

        return new EditorCompletions(List.copyOf(extra));
    }

    private static void addChannelVariables(
            Set<String> variables,
            Collection<IOChannel> channels
    ) {
        for (IOChannel channel : channels) {
            if (channel.variableName != null && !channel.variableName.isBlank()) {
                variables.add(channel.variableName);
            }
        }
    }

    public record EditorCompletions(List<String> extraVariables) {}



}