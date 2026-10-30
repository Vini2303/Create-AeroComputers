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
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.registries.DigitalTransmissionRegistry;
import net.tui.aerocomputers.registries.HudRegistry;
import net.tui.aerocomputers.registries.SensorRegistry;
import net.tui.aerocomputers.screen.custom.BoardComputerMenu;
import net.tui.aerocomputers.script.AeroScriptApi;
import net.tui.aerocomputers.script.HudScriptApi;
import net.tui.aerocomputers.script.PythonScriptRuntime;
import net.tui.aerocomputers.network.CameraChunkManager;
import net.tui.aerocomputers.script.SandboxFileSystem;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.*;


public class BoardComputerBlockEntity extends KineticBlockEntity implements MenuProvider, BlockEntitySubLevelActor {

    private String programPath = "";   // relativ zum Welt-Ordner, z. B. "main.py"

    private record FrequencyPair(String first, String second) {}

    private final Map<FrequencyPair, Integer> powerInputs = new LinkedHashMap<>();
    private final Map<FrequencyPair, Integer> powerOutputs = new LinkedHashMap<>();

    private final Map<Integer, FrequencyPair> inputSlotPairs = new HashMap<>();
    private final Map<Integer, FrequencyPair> outputSlotPairs = new HashMap<>();

    private final Map<FrequencyPair, Integer> receivedPowers = new HashMap<>();
    private final Map<FrequencyPair, Integer> transmittedPowers = new HashMap<>();

    private static ItemStack frequencyStack(String frequency) {
        ResourceLocation id = ResourceLocation.parse(frequency);
        var item = BuiltInRegistries.ITEM.getOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown item frequency: " + frequency));
        return new ItemStack(item);
    }

    public int scriptGetPower(String[] freq) {
        FrequencyPair pair = requireFrequencyPair(freq);
        if (level == null || level.isClientSide) {
            return receivedPowers.getOrDefault(pair, 0);
        }

        Integer slot = powerInputs.get(pair);
        if (slot == null) {
            slot = findFreeSlot(powerInputs);
            if (slot < 0) {
                throw new IllegalStateException("No free redstone receiver slots");
            }
            powerInputs.put(pair, slot);
            inputSlotPairs.put(slot, pair);
            LinkBehaviour link = inputLinks[slot];
            link.setFrequency(true, frequencyStack(freq[0]));
            link.setFrequency(false, frequencyStack(freq[1]));
            link.notifySignalChange();
            setChanged();
        }
        return receivedPowers.getOrDefault(pair, 0);
    }

    public Object scriptSetSpeed(String[] freq, Object speed) {
        Set<DigitalTransmissionBlockEntity> transmissions =
                DigitalTransmissionRegistry.get(freq[0], freq[1]);
        for (DigitalTransmissionBlockEntity transmission : transmissions) {
            transmission.setSpeed((int) asDouble(speed));
        }
        return speed;
    }

    private static FrequencyPair requireFrequencyPair(String[] freq) {
        if (freq == null || freq.length != 2 || freq[0] == null || freq[1] == null
                || freq[0].isBlank() || freq[1].isBlank()) {
            throw new IllegalArgumentException("Expected two item IDs, e.g. [\"minecraft:stone\", \"minecraft:dirt\"]");
        }
        // Validate IDs immediately so typos do not silently create unusable links.
        ResourceLocation.parse(freq[0]);
        ResourceLocation.parse(freq[1]);
        return new FrequencyPair(freq[0], freq[1]);
    }

    public int scriptSetPower(String[] freq, Object power) {
        FrequencyPair pair = requireFrequencyPair(freq);

        if (level == null || level.isClientSide) {
            return 0;
        }

        int strength = Math.max(0, Math.min(15, (int) asDouble(power)));

        Integer slot = powerOutputs.get(pair);

        if (slot == null) {
            slot = findFreeSlot(powerOutputs);

            if (slot < 0) {
                throw new IllegalStateException(
                        "No free redstone transmitter slots"
                );
            }

            powerOutputs.put(pair, slot);
            outputSlotPairs.put(slot, pair);

            LinkBehaviour link = outputLinks[slot];
            link.setFrequency(true, frequencyStack(freq[0]));
            link.setFrequency(false, frequencyStack(freq[1]));
        }

        transmittedPowers.put(pair, strength);
        outputLinks[slot].notifySignalChange();

        setChanged();
        return strength;
    }

    private static int findFreeSlot(Map<FrequencyPair, Integer> allocated) {
        boolean[] used = new boolean[MAX_CHANNELS];

        for (int slot : allocated.values()) {
            if (slot >= 0 && slot < used.length) {
                used[slot] = true;
            }
        }

        for (int i = 0; i < used.length; i++) {
            if (!used[i]) {
                return i;
            }
        }

        return -1;
    }

    public String getProgramPath() { return programPath; }

    public boolean isPythonProgram() {
        return programPath.toLowerCase(java.util.Locale.ROOT).endsWith(".py");
    }

    private String readProgramFile() {
        if (level == null || level.isClientSide || level.getServer() == null
                || programPath.isEmpty() || !isPythonProgram()) return "";
        try {
            java.nio.file.Path root = SandboxFileSystem.rootFor(level.getServer())
                    .toAbsolutePath().normalize();
            java.nio.file.Path f = root.resolve(programPath).normalize();
            if (f.startsWith(root) && java.nio.file.Files.isRegularFile(f))
                return java.nio.file.Files.readString(f);
        } catch (java.io.IOException e) {
            AeroComputers.LOGGER.warn("Cannot read program {}: {}", programPath, e.toString());
        }
        return "";
    }

    private void loadProgram(boolean force) {
        String text = readProgramFile();
        if (!force && text.equals(program)) return;      // unverändert: nicht neu starten
        program = text;
        variables.clear();
        closePythonRuntime();
        pythonError = null;
        editorCompletionUpdateId++;
        setChanged();
        sync();
    }

    public void setProgramPath(String path) {
        String p = path == null ? "" : path;
        if (p.equals(programPath)) return;
        programPath = p;
        loadProgram(true);
    }

    public void onFileWritten(String path) {
        if (path.equals(programPath)) loadProgram(false);
    }

    public void onPathMoved(String oldP, String newP) {
        if (programPath.equals(oldP) || programPath.startsWith(oldP + "/")) {
            programPath = newP + programPath.substring(oldP.length());
            setChanged();
            sync();
        }
    }

    public void onPathDeleted(String path) {
        if (programPath.equals(path) || programPath.startsWith(path + "/")) {
            programPath = "";
            loadProgram(true);
        }
    }


    private static final int PYTHON_STEPS_PER_TICK = 64;
    private static final double PYTHON_BUDGET_SECONDS = 0.001; // 1 ms per tick
    /** HUD changes are pushed to clients at most every N ticks. */
    private static final int HUD_SYNC_INTERVAL_TICKS = 1;

    // GraalPy runtime state. Created lazily in runPythonSteps(), only while not paused.
    private @Nullable PythonScriptRuntime pythonRuntime;
    private @Nullable HudScriptApi hudScriptApi;
    private @Nullable String pythonError;

    private int transmittedSignal;
    private int receivedSignal;

    private String program = "";

    private final Map<String, Object> variables = new HashMap<>();

    private @Nullable SubLevel currentSubLevel;
    private boolean subLevelSeenThisCycle = false;

    private boolean running = true;

    private static final int MAX_CHANNELS = 50;

    private LinkBehaviour[] inputLinks;
    private LinkBehaviour[] outputLinks;


    private static class DrawObject {
        String id;
        String type; // "label", "line", "poly", "rect", "cam"

        String freqOne;
        String freqTwo;

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

        tag.putString("ProgramPath", programPath);
        tag.put("PowerInputs", writePowerPairs(powerInputs, null));
        tag.put("PowerOutputs", writePowerPairs(powerOutputs, transmittedPowers));
        // Pause state is transient in saved world data, but is sent to clients for GUI display.
        if (clientPacket) tag.putBoolean("Running", running);
        tag.putInt("EditorCompletionUpdateId", editorCompletionUpdateId);


        tag.putString("ComputerName", computerName);
        tag.put("DrawObjects", writeDrawObjects());


        tag.putInt("Transmit", transmittedSignal);
        tag.putInt("Receive", receivedSignal);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider provider, boolean clientPacket) {
        super.read(tag, provider, clientPacket);

        programPath = tag.getString("ProgramPath");
        powerInputs.clear();
        powerOutputs.clear();
        inputSlotPairs.clear();
        outputSlotPairs.clear();
        receivedPowers.clear();
        transmittedPowers.clear();
        if (tag.contains("PowerInputs")) readPowerPairs(tag.getList("PowerInputs", Tag.TAG_COMPOUND), powerInputs, inputSlotPairs, null);
        if (tag.contains("PowerOutputs")) readPowerPairs(tag.getList("PowerOutputs", Tag.TAG_COMPOUND), powerOutputs, outputSlotPairs, transmittedPowers);
        variables.clear();
        running = clientPacket && tag.contains("Running") ? tag.getBoolean("Running") : true; // Pause is not saved across world reloads.
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
        restoreLinkFrequencies(powerInputs, inputLinks);
        restoreLinkFrequencies(powerOutputs, outputLinks);
    }

    private void restoreLinkFrequencies(Map<FrequencyPair, Integer> pairs, LinkBehaviour[] links) {
        if (links == null) return;
        for (Map.Entry<FrequencyPair, Integer> entry : pairs.entrySet()) {
            int slot = entry.getValue();
            if (slot < 0 || slot >= links.length || links[slot] == null) continue;
            FrequencyPair pair = entry.getKey();
            links[slot].setFrequency(true, frequencyStack(pair.first()));
            links[slot].setFrequency(false, frequencyStack(pair.second()));
        }
    }

    private ListTag writePowerPairs(Map<FrequencyPair, Integer> pairs, Map<FrequencyPair, Integer> powers) {
        ListTag list = new ListTag();
        for (Map.Entry<FrequencyPair, Integer> entry : pairs.entrySet()) {
            CompoundTag pairTag = new CompoundTag();
            pairTag.putString("first", entry.getKey().first());
            pairTag.putString("second", entry.getKey().second());
            pairTag.putInt("slot", entry.getValue());
            if (powers != null) pairTag.putInt("power", powers.getOrDefault(entry.getKey(), 0));
            list.add(pairTag);
        }
        return list;
    }

    private void readPowerPairs(ListTag list, Map<FrequencyPair, Integer> pairs,
                                Map<Integer, FrequencyPair> slots, Map<FrequencyPair, Integer> powers) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String first = entry.getString("first");
            String second = entry.getString("second");
            int slot = entry.getInt("slot");
            if (first.isBlank() || second.isBlank() || slot < 0 || slot >= MAX_CHANNELS) continue;
            FrequencyPair pair = new FrequencyPair(first, second);
            pairs.put(pair, slot);
            slots.put(slot, pair);
            if (powers != null) powers.put(pair, Math.max(0, Math.min(15, entry.getInt("power"))));
        }
    }


    @Override
    public Component getDisplayName() {
        return Component.literal("Board Computer");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int i, Inventory playerInventory, Player player) {
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

        var server = level.getServer();
        if (server == null) return;

        java.nio.file.Path fileRoot = SandboxFileSystem.rootFor(server);

        hudScriptApi = new HudScriptApi(this);
        pythonRuntime = new PythonScriptRuntime(
                new AeroScriptApi(this),
                hudScriptApi.asProxy(),
                this::onPythonPrint,
                fileRoot
        );
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
    }

    private void startPythonProgram() {
        if (pythonRuntime == null) return;
        try {
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                if (entry.getValue() instanceof Number || entry.getValue() instanceof String
                        || entry.getValue() instanceof Boolean || entry.getValue() instanceof List<?>) {
                    pythonRuntime.setVariable(entry.getKey(), entry.getValue());
                }
            }
            pythonRuntime.start(program);
            pythonError = null;
        } catch (RuntimeException exception) {
            pythonError = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            AeroComputers.LOGGER.error("Python compile/start error at {}: {}", worldPosition, pythonError, exception);
        }
    }

    private void runPythonSteps(int maxSteps) {
        if (!running || program == null || program.isBlank() || !isPythonProgram()) return;

        // Lazy (re)start: only happens while the computer is running.
        if (pythonRuntime == null) {
            ensurePythonRuntime();
            startPythonProgram();
        }
        if (pythonRuntime == null || pythonRuntime.isFinished()) return;

        try {
            // One host->guest call; the stepping loop runs inside Python.
            pythonRuntime.step(maxSteps, PYTHON_BUDGET_SECONDS);
        } catch (RuntimeException exception) {
            pythonError = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            AeroComputers.LOGGER.error("Python script error at {}: {}", worldPosition, pythonError, exception);
            setChanged();
        }
    }

    // Methods exposed through the deliberately small AeroScriptApi facade.
    public Object scriptGetPos(@Nullable String[] freq) {
        if (freq != null) {
            SensorBlockEntity s = SensorRegistry.get(freq[0], freq[1]);
            return s == null ? List.of(0.0, 0.0, 0.0) : asXyz(s.get_Pos());
        }
        throw new IllegalStateException("No sensor " + freq[0] + "/" + freq[1]);
    }

    public Object scriptGetRot(@Nullable String[] freq) {
        if (freq != null) {
            SensorBlockEntity s = SensorRegistry.get(freq[0], freq[1]);
            return s == null ? List.of(0.0, 0.0, 0.0) : asXyz(s.get_Rot());
        }
        throw new IllegalStateException("No sensor " + freq[0] + "/" + freq[1]);
    }

    public Object scriptGetVel(@Nullable String[] freq) {
        if (freq != null) {
            SensorBlockEntity s = SensorRegistry.get(freq[0], freq[1]);
            return s == null ? List.of(0.0, 0.0, 0.0) : asXyz(s.get_Vel());
        }
        throw new IllegalStateException("No sensor " + freq[0] + "/" + freq[1]);
    }

    public Object scriptRadar(@Nullable String[] freq) {
        if (freq != null) {
            SensorBlockEntity s = SensorRegistry.get(freq[0], freq[1]);
            if (s == null) return List.of();
            Object r = s.get_Radar();
            if (r instanceof List<?> list) {
                List<Object> out = new ArrayList<>();
                for (Object o : list) out.add(asXyz(o));
                return out;
            }
            return r;
        }
        throw new IllegalStateException("No sensor " + freq[0] + "/" + freq[1]);
    }

    private static Object asXyz(Object v) {
        if (v instanceof List<?>) return v;
        if (v instanceof Vector3d d) return List.of(round2(d.x), round2(d.y), round2(d.z));
        if (v instanceof net.minecraft.world.phys.Vec3 d) return List.of(round2(d.x), round2(d.y), round2(d.z));
        if (v instanceof double[] a && a.length >= 3) return List.of(round2(a[0]), round2(a[1]), round2(a[2]));
        return v;
    }

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
        if (args.isEmpty() || !(args.get(0) instanceof List<?> freq)
                || freq.size() != 2
                || !(freq.get(0) instanceof String)
                || !(freq.get(1) instanceof String)) {
            throw new IllegalArgumentException(
                    type + " expects [freq_one, freq_two] as its first argument"
            );
        }

        String freqOne = (String) freq.get(0);
        String freqTwo = (String) freq.get(1);

        if (freqOne.isBlank() || freqTwo.isBlank()) {
            throw new IllegalArgumentException("HUD frequencies cannot be blank");
        }

        args = args.subList(1, args.size());

        DrawObject obj = new DrawObject();
        obj.id = "d" + (drawObjectIdCounter++);
        obj.freqOne = freqOne;
        obj.freqTwo = freqTwo;
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
        FrequencyPair pair = inputSlotPairs.get(slot);
        if (pair != null) {
            receivedPowers.put(pair, power);
        }
    }

    private int getOutputTransmitStrength(int slot) {
        FrequencyPair pair = outputSlotPairs.get(slot);
        return pair == null ? 0 : transmittedPowers.getOrDefault(pair, 0);
    }

    static double round2(double value) {
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

        if (level instanceof ServerLevel sl && !drawObjects.isEmpty()
                && drawObjects.stream().anyMatch(o -> "cam".equals(o.type)))
            CameraChunkManager.tick(sl);

        int rpm = (int) Math.abs(getSpeed());

        if (rpm < 128)
            return;

        // Paused: no runtime is created and nothing is executed.
        if (!running)
            return;

        // Interpolate execution speed from 1 step/tick at 128 RPM
        // to the maximum step count at 256 RPM. At and above 256 RPM,
        // always request the maximum amount of work each tick.
        int steps = rpm >= 256
                ? PYTHON_STEPS_PER_TICK
                : 1 + (int) Math.floor((rpm - 128) * (PYTHON_STEPS_PER_TICK - 1) / 128.0);
        steps = Math.max(1, Math.min(PYTHON_STEPS_PER_TICK, steps));
        runPythonSteps(steps);


        // Throttled: sending the whole block entity NBT every tick is expensive.
        if (hudDirty && level.getGameTime() % HUD_SYNC_INTERVAL_TICKS == 0) {
            hudDirty = false;
            notifyUpdate();
        }
    }


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
        List<Integer> out = new ArrayList<>();

        if (value instanceof List<?> list) {
            for (Object v : list) out.add((int) asDouble(v));

        } else if (value instanceof Number n) {                 // 0xFF8800
            int rgb = (int) n.longValue();
            out.add((rgb >> 16) & 0xFF);
            out.add((rgb >> 8) & 0xFF);
            out.add(rgb & 0xFF);

        } else if (value instanceof String s) {                 // "#FF8800" or "0xFF8800"
            String h = s.trim();
            if (h.startsWith("#")) h = h.substring(1);
            else if (h.regionMatches(true, 0, "0x", 0, 2)) h = h.substring(2);
            try {
                int rgb = Integer.parseInt(h, 16);
                out.add((rgb >> 16) & 0xFF);
                out.add((rgb >> 8) & 0xFF);
                out.add(rgb & 0xFF);
            } catch (NumberFormatException ignored) { }
        }

        while (out.size() < 3) out.add(255);
        return out;
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
            program = readProgramFile();
            ComputerRegistry.register(computerName, this);
            closePythonRuntime();
            running = true; // Reloading the computer automatically resumes its program.
            // The runtime is started lazily by tick().
        }
    }

    @Override
    public void remove() {
        closePythonRuntime();
        HudRegistry.clearComputer(worldPosition);
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

    /**
     * Restarts the program from the beginning. Does NOT change the pause state:
     * a paused computer stays paused and starts fresh once it is resumed.
     */
    public void restart() {
        variables.clear();
        closePythonRuntime();   // tick() starts a new runtime lazily, only if running
        drawObjects.clear();
        commands.clear();
        drawObjectIdCounter = 0;
        pythonError = null;
        hudDirty = true;
        setChanged();
        sync();
    }


    public void sync() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
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
        if (level == null || !level.isClientSide) {
            return;
        }

        Map<HudRegistry.FreqPair, List<HudRegistry.DrawEntry>> grouped =
                new HashMap<>();

        for (DrawObject o : drawObjects) {

            if (o.freqOne == null || o.freqOne.isBlank()
                    || o.freqTwo == null || o.freqTwo.isBlank()) {
                continue;
            }

            HudRegistry.DrawEntry entry = new HudRegistry.DrawEntry(
                    o.type,
                    o.x,
                    o.y,
                    o.x2,
                    o.y2,
                    o.w,
                    o.h,
                    o.text,
                    o.fontSize,
                    o.width,
                    o.borderWidth,
                    o.renderCenter,
                    o.renderBorder,
                    List.copyOf(o.color),
                    List.copyOf(o.centerColor),
                    List.copyOf(o.borderColor),
                    List.copyOf(o.xs),
                    List.copyOf(o.ys)
            );

            HudRegistry.FreqPair key =
                    new HudRegistry.FreqPair(o.freqOne, o.freqTwo);

            grouped.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(entry);
        }

        // Remove this computer's previous entries from every frequency.
        HudRegistry.clearComputer(worldPosition);

        // Register this computer's current entries.
        grouped.forEach((key, entries) ->
                HudRegistry.update(
                        key.freqOne(),
                        key.freqTwo(),
                        worldPosition,
                        entries
                )
        );
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
            ct.putString("freqOne", o.freqOne == null ? "" : o.freqOne);
            ct.putString("freqTwo", o.freqTwo == null ? "" : o.freqTwo);

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

            o.freqOne = ct.getString("freqOne");
            o.freqTwo = ct.getString("freqTwo");

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


    /** The editor derives functions, classes, and variables from the program text. */
    public EditorCompletions getEditorCompletions() {
        return new EditorCompletions(List.of());
    }

    public record EditorCompletions(List<String> extraVariables) {}

}
