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
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.tui.aerocomputers.AeroComputers;
import net.tui.aerocomputers.lang.LanguageSpec;
import net.tui.aerocomputers.lang.Lexer;
import net.tui.aerocomputers.lang.ProgramAnalyzer;
import net.tui.aerocomputers.lang.ProgramInfo;
import net.tui.aerocomputers.lang.Token;
import net.tui.aerocomputers.lang.TokenType;
import net.tui.aerocomputers.screen.custom.BoardComputerMenu;
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


    private int transmittedSignal;
    private int receivedSignal;

    private String program = "";
    private int lineIndex = 0;

    private final Map<String, Object> variables = new HashMap<>();
    private final Map<Integer, Double> forCounters = new HashMap<>();
    private final Map<Integer, Integer> blockEnd = new HashMap<>();   // if/while/for start -> matching end
    private final Map<Integer, Integer> loopStart = new HashMap<>();  // endwhile/endfor -> matching start
    private String[] programLines = new String[0];
    private String[] firstWords = new String[0];                      // first identifier of each line (from the shared lexer)

    private final Map<String, ProgramInfo.FunctionInfo> functions = new HashMap<>();
    private final Deque<Map<String, Object>> scopeStack = new ArrayDeque<>();
    private int callDepth = 0;

    private static final int MAX_CALL_DEPTH = 20;
    private static final int MAX_FUNCTION_ITERATIONS = 5000;

    private final Map<Integer, List<Object>> forLists = new HashMap<>();

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

    private static class Instance {
        String className;
        Map<String, Object> fields = new LinkedHashMap<>();
    }

    private final Map<String, ProgramInfo.ClassInfo> classes = new HashMap<>();

    private interface NativeInstance {
        Object getField(String name);
        void setField(String name, Object value);
        Object callMethod(String name, List<Object> args);
    }

    private interface NativeClassFactory {
        NativeInstance create(List<Object> args);
    }

    private final Map<String, NativeClassFactory> nativeClasses = new HashMap<>();

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
        scopeStack.push(variables);
        registerBuiltins();
        registerNativeClasses();
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider provider, boolean clientPacket) {
        super.write(tag, provider, clientPacket);

        tag.putString("Program", program);
        tag.putBoolean("Running", running);
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
        program = text;
        compileBlocks();

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
        compileBlocks(); // rebuild programLines/blockEnd/loopStart after loading from NBT
        running = tag.contains("Running") ? tag.getBoolean("Running") : true;
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


    private final Map<String, java.util.function.Function<List<Object>, Object>> builtins = new HashMap<>();

    private void registerBuiltins() {
        builtins.put("aero.getPos", args -> {
            if (currentSubLevel == null) {
                return List.of(
                        getBlockPos().getX() + 0.5,
                        getBlockPos().getY() + 0.5,
                        getBlockPos().getZ() + 0.5
                );
            }

            Vector3d pos = new Vector3d(
                    getBlockPos().getX() + 0.5,
                    getBlockPos().getY() + 0.5,
                    getBlockPos().getZ() + 0.5
            );

            currentSubLevel.logicalPose().transformPosition(pos);

            return List.of(
                    round2(pos.x),
                    round2(pos.y),
                    round2(pos.z)
            );
        });
        builtins.put("aero.getVel", args -> {
            if (currentSubLevel == null)
                return List.of(0.0,0.0,0.0);


            Vector3d current = new Vector3d(
                    getBlockPos().getX()+0.5,
                    getBlockPos().getY()+0.5,
                    getBlockPos().getZ()+0.5
            );

            Vector3d previous = new Vector3d(current);


            currentSubLevel.logicalPose()
                    .transformPosition(current);

            currentSubLevel.lastPose()
                    .transformPosition(previous);


            Vector3d velocity = current.sub(previous)
                    .mul(20.0);


            return List.of(
                    round2(velocity.x),
                    round2(velocity.y),
                    round2(velocity.z)
            );
        });
        builtins.put("aero.getRot", args -> {

            if (currentSubLevel == null)
                return List.of(0.0,0.0,0.0);


            Quaterniond start = new Quaterniond()
                    .rotateY(Math.toRadians(
                            -getBlockState()
                                    .getValue(DirectionalKineticBlock.FACING)
                                    .toYRot()
                    ));


            Quaterniond current =
                    new Quaterniond(
                            currentSubLevel.logicalPose().orientation()
                    );


            Quaterniond relative =
                    start.invert()
                            .mul(current);


            Vector3d euler = new Vector3d();

            relative.getEulerAnglesYXZ(euler);


            return List.of(
                    round2(Math.toDegrees(euler.x)),
                    round2(Math.toDegrees(euler.y)),
                    round2(Math.toDegrees(euler.z))
            );
        });
        builtins.put("aero.radar", args -> {
            return getAllSubLevelPositions();
        });
        builtins.put("math.round", args -> {
            if (args.isEmpty()) return 0.0;
            return (double) Math.round(toNumber(args.get(0)));
        });

        builtins.put("math.floor", args -> {
            if (args.isEmpty()) return 0.0;
            return Math.floor(toNumber(args.get(0)));
        });

        builtins.put("math.ceil", args -> {
            if (args.isEmpty()) return 0.0;
            return Math.ceil(toNumber(args.get(0)));
        });
        builtins.put("math.abs", args -> args.isEmpty() ? 0.0 : Math.abs(toNumber(args.get(0))));

        builtins.put("math.min", args -> {
            if (args.isEmpty()) return 0.0;
            double m = toNumber(args.get(0));
            for (Object a : args) m = Math.min(m, toNumber(a));
            return m;
        });

        builtins.put("math.max", args -> {
            if (args.isEmpty()) return 0.0;
            double m = toNumber(args.get(0));
            for (Object a : args) m = Math.max(m, toNumber(a));
            return m;
        });

        builtins.put("math.sqrt", args -> args.isEmpty() ? 0.0 : Math.sqrt(toNumber(args.get(0))));
        builtins.put("math.sin", args ->
                Math.sin(Math.toRadians(toNumber(args.get(0))))
        );

        builtins.put("math.cos", args ->
                Math.cos(Math.toRadians(toNumber(args.get(0))))
        );

        builtins.put("math.asin", args ->
                Math.toDegrees(Math.asin(toNumber(args.get(0))))
        );

        builtins.put("math.acos", args ->
                Math.toDegrees(Math.acos(toNumber(args.get(0))))
        );
        builtins.put("bigc.aimangel", args -> {
            double pitch = args.size() > 0 ? toNumber(args.get(0)) : 0.0;
            double yaw   = args.size() > 1 ? toNumber(args.get(1)) : 0.0;
            issueCommand("bigc.aimangel", pitch, yaw);
            return 0.0;
        });
        builtins.put("bigc.fire", args -> {
            double fire = args.size() > 0 ? toNumber(args.get(0)) : 0.0;
            issueCommand("bigc.fire", fire);
            return 0.0;
        });
        builtins.put("bigc.aimpos", args -> {
            List<Double> pos = args.size() > 0 ? toPosList(args.get(0)) : List.of(0.0,0.0,0.0);
            issueCommand("bigc.aimpos", pos.get(0), pos.get(1), pos.get(2));
            return 0.0;
        });
        builtins.put("slrw.firechin", args -> {
            double fire = args.size() > 0 ? toNumber(args.get(0)) : 0.0;
            issueCommand("slrw.firechin", fire);
            return 0.0;
        });
        builtins.put("slrw.aimchinangel", args -> {
            double pitch = args.size() > 0 ? toNumber(args.get(0)) : 0.0;
            double yaw = args.size() > 1 ? toNumber(args.get(1)) : 0.0;
            issueCommand("slrw.aimchinangel", pitch, yaw);
            return 0.0;
        });
        builtins.put("slrw.aimchinpos", args -> {
            List<Double> pos = args.size() > 0 ? toPosList(args.get(0)) : List.of(0.0,0.0,0.0);
            issueCommand("slrw.aimchinpos", pos.get(0), pos.get(1), pos.get(2));
            return 0.0;
        });
        builtins.put("aero.send", args -> {
            String name_id = args.size() > 0 ? args.get(0).toString() : "";
            String message = args.size() > 1 ? args.get(1).toString() : "";
            ComputerRegistry.get(name_id).sendMessage(message);
            return 0.0;
        });
        builtins.put("aero.read", args -> {
            if (this.messages.isEmpty()) {
                return "";
            }
            return this.messages.remove(0);
        });
        builtins.put("aero.clear", args -> {
            this.messages.clear();
            return 0.0;
        });
    }


    @Override
    public void sable$tick(ServerSubLevel subLevel) {
        this.currentSubLevel = subLevel;
        this.subLevelSeenThisCycle = true;
    }

    private void registerNativeClasses() {

        nativeClasses.put("hud.Label", args -> {
            DrawObject obj = new DrawObject();
            obj.id = "d" + (drawObjectIdCounter++);
            obj.type = "label";
            obj.x = args.size() > 0 ? toNumber(args.get(0)) : 0;
            obj.y = args.size() > 1 ? toNumber(args.get(1)) : 0;
            obj.text = args.size() > 2 ? stringify(args.get(2)) : "";
            obj.color = toColorList(args.size() > 3 ? args.get(3) : null);
            obj.fontSize = args.size() > 4 ? toNumber(args.get(4)) : 1.0;
            drawObjects.add(obj);
            markDrawDirty();
            return wrapDrawObject(obj);
        });

        nativeClasses.put("hud.Line", args -> {
            DrawObject obj = new DrawObject();
            obj.id = "d" + (drawObjectIdCounter++);
            obj.type = "line";
            obj.x = args.size() > 0 ? toNumber(args.get(0)) : 0;
            obj.y = args.size() > 1 ? toNumber(args.get(1)) : 0;
            obj.x2 = args.size() > 2 ? toNumber(args.get(2)) : 0;
            obj.y2 = args.size() > 3 ? toNumber(args.get(3)) : 0;
            // parameter order: x1, y1, x2, y2, width, color
            obj.width = args.size() > 4 ? toNumber(args.get(4)) : 1.0;
            obj.color = toColorList(args.size() > 5 ? args.get(5) : null);
            drawObjects.add(obj);
            markDrawDirty();
            return wrapDrawObject(obj);
        });

        nativeClasses.put("hud.Poly", args -> {
            DrawObject obj = new DrawObject();
            obj.id = "d" + (drawObjectIdCounter++);
            obj.type = "poly";
            obj.xs = args.size() > 0 ? toDoubleList(args.get(0)) : new ArrayList<>();
            obj.ys = args.size() > 1 ? toDoubleList(args.get(1)) : new ArrayList<>();
            obj.borderWidth = args.size() > 2 ? toNumber(args.get(2)) : 1.0;
            obj.borderColor = toColorList(args.size() > 3 ? args.get(3) : null);
            drawObjects.add(obj);
            markDrawDirty();
            return wrapDrawObject(obj);
        });

        nativeClasses.put("hud.Rect", args -> {
            DrawObject obj = new DrawObject();
            obj.id = "d" + (drawObjectIdCounter++);
            obj.type = "rect";
            obj.x = args.size() > 0 ? toNumber(args.get(0)) : 0;
            obj.y = args.size() > 1 ? toNumber(args.get(1)) : 0;
            obj.w = args.size() > 2 ? toNumber(args.get(2)) : 0;
            obj.h = args.size() > 3 ? toNumber(args.get(3)) : 0;
            obj.borderWidth = args.size() > 4 ? toNumber(args.get(4)) : 1.0;
            obj.centerColor = toColorList(args.size() > 5 ? args.get(5) : null);
            obj.borderColor = toColorList(args.size() > 6 ? args.get(6) : null);
            obj.renderCenter = args.size() > 7 ? toBool(args.get(7)) : true;
            obj.renderBorder = args.size() > 8 ? toBool(args.get(8)) : true;
            drawObjects.add(obj);
            markDrawDirty();
            return wrapDrawObject(obj);
        });
        nativeClasses.put("hud.Cam", args -> {
            DrawObject obj = new DrawObject();
            obj.x = args.size() > 0 ? toNumber(args.get(0)) : 0;
            obj.y = args.size() > 1 ? toNumber(args.get(1)) : 0;
            obj.w = args.size() > 2 ? toNumber(args.get(2)) : 0;
            obj.h = args.size() > 3 ? toNumber(args.get(3)) : 0;
            drawObjects.add(obj);
            markDrawDirty();
            return wrapDrawObject(obj);
        });
    }

    @Override
    public void tick() {
        super.tick();
        printsThisTick = 0;

        if (!subLevelSeenThisCycle) {
            currentSubLevel = null;
        }
        subLevelSeenThisCycle = false;

        if (level == null || level.isClientSide)
            return;

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
                executeNextLine();
            }
        } else {
            long start = System.nanoTime();
            long maxTime = 1_000_000; // 1ms wall-clock budget
            int maxIterations = 2000; // hard cap regardless of how cheap each line is
            int iterations = 0;

            while (System.nanoTime() - start < maxTime && iterations < maxIterations) {
                executeNextLine();
                iterations++;
            }
        }

        for (IOChannel ch : outputChannels) {
            Object raw = variables.getOrDefault(ch.variableName, 0.0);
            double num = toNumber(raw);
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

    private Object callFunction(String name, CallArgs callArgs) {
        if (builtins.containsKey(name)) {
            List<String> paramNames = LanguageSpec.BUILTIN_FUNCTIONS.getOrDefault(name, List.of());
            return builtins.get(name).apply(resolveArgs(paramNames, callArgs));
        }

        ProgramInfo.FunctionInfo def = functions.get(name);
        if (def == null) {
            AeroComputers.LOGGER.warn("Call to undefined function: {}", name);
            return 0.0;
        }

        if (callDepth >= MAX_CALL_DEPTH) {
            AeroComputers.LOGGER.error("Max call depth exceeded calling {}", name);
            return 0.0;
        }

        Map<String, Object> frame = new HashMap<>();
        bindParams(def.params(), 0, callArgs, frame);

        scopeStack.push(frame);
        callDepth++;

        Object returnValue = 0.0;
        int line = def.startLine() + 1;
        int iterations = 0;

        try {
            while (line < def.endLine() && iterations < MAX_FUNCTION_ITERATIONS) {
                String text = stripComment(programLines[line].trim());
                iterations++;

                if (isReturnLine(line)) {
                    returnValue = evaluateReturn(text);
                    break;
                }

                line = executeLine(text, line);
            }
        } finally {
            callDepth--;
            scopeStack.pop();
        }

        return returnValue;
    }

    private boolean isReturnLine(int line) {
        return line >= 0 && line < firstWords.length && firstWords[line].equals("return");
    }

    private Object evaluateReturn(String text) {
        String expr = text.length() > 6 ? text.substring(6).trim() : "";
        if (expr.isEmpty()) {
            return 0.0;
        }

        List<String> parts = splitTopLevel(expr, ',');
        if (parts.size() > 1) {
            List<Object> vals = new ArrayList<>();
            for (String p : parts) vals.add(evaluateExpression(p.trim()));
            return vals;
        }

        return evaluateExpression(expr);
    }

    private Object getVar(String name) {
        Map<String, Object> top = scopeStack.peek();
        if (top != null && top != variables && top.containsKey(name)) {
            return top.get(name);
        }
        if (persistentVariables.containsKey(name)) {
            return persistentVariables.get(name);
        }
        return variables.getOrDefault(name, 0.0);
    }

    private void setVar(String name, Object value) {
        Map<String, Object> top = scopeStack.peek();
        if (top != null && top != variables && top.containsKey(name)) {
            top.put(name, value); // only true parameters are local
            return;
        }
        if (persistentVariables.containsKey(name)) {
            persistentVariables.put(name, value);
            setChanged();
            return;
        }
        variables.put(name, value);
    }

    private void removeVar(String name) {
        Map<String, Object> top = scopeStack.peek();
        if (top != null) top.remove(name);
        variables.remove(name);
    }

    /**
     * Uses the shared analyzer (the same one the editor uses) to find blocks,
     * functions and classes.
     */
    private void compileBlocks() {
        forCounters.clear();
        forLists.clear();
        variables.clear();
        scopeStack.clear();
        scopeStack.push(variables);
        lineIndex = 0;

        ProgramInfo info = ProgramAnalyzer.analyze(program);

        programLines = info.lines();
        firstWords = info.firstWords();

        blockEnd.clear();
        blockEnd.putAll(info.blockEnd());

        loopStart.clear();
        loopStart.putAll(info.loopStart());

        functions.clear();
        functions.putAll(info.functions());

        classes.clear();
        classes.putAll(info.classes());
    }

    /** Removes a trailing "# comment" (using the shared lexer, so '#' inside strings is kept). */
    private static String stripComment(String line) {
        if (line.indexOf('#') < 0) {
            return line;
        }

        for (Token t : Lexer.lex(line)) {
            if (t.type() == TokenType.COMMENT) {
                return line.substring(0, t.start()).trim();
            }
        }

        return line;
    }


    private void executeNextLine() {
        if (programLines.length == 0) return;

        if (lineIndex >= programLines.length) {
            return; // stuck here until restart() resets lineIndex to 0
        }

        String line = programLines[lineIndex].trim();
        lineIndex = executeLine(line, lineIndex);
    }

    private int executeLine(String line, int index) {
        try {
            line = stripComment(line);

            if (line.isEmpty()) {
                return index + 1;
            }

            // Same first word the analyzer / editor used for this line
            String kw = (index >= 0 && index < firstWords.length) ? firstWords[index] : "";

            if (kw.equals("print")) {
                printLine(line);
                return index + 1;
            }

            if (kw.equals("if")) {
                boolean result = evaluateCondition(extractCondition(line, "if"));
                Integer end = blockEnd.get(index);
                return (!result && end != null) ? end + 1 : index + 1;
            }

            if (line.equals("endif")) {
                return index + 1;
            }

            if (kw.equals("while")) {
                boolean result = evaluateCondition(extractCondition(line, "while"));
                Integer end = blockEnd.get(index);
                return (!result && end != null) ? end + 1 : index + 1;
            }

            if (line.equals("endwhile")) {
                Integer start = loopStart.get(index);
                return start != null ? start : index + 1;
            }

            if (kw.equals("for")) {
                String[] parts = line.trim().split("\\s+", 4);
                if (parts.length < 4 || !parts[2].equals("in")) {
                    return index + 1;
                }

                String varName = parts[1];
                String source = parts[3].trim();
                double counter = forCounters.getOrDefault(index, 0.0);

                if (source.startsWith("range")) {
                    double limit = evaluateNumeric(extractBrackets(source));

                    if (counter >= limit) {
                        forCounters.remove(index);
                        removeVar(varName);
                        Integer end = blockEnd.get(index);
                        return end != null ? end + 1 : index + 1;
                    }

                    setVar(varName, counter);
                    return index + 1;
                } else {
                    List<Object> list = forLists.get(index);
                    if (list == null) {
                        Object evaluated = evaluateExpression(source);
                        list = (evaluated instanceof List<?> l) ? new ArrayList<>(l) : List.of();
                        forLists.put(index, list);
                    }

                    if (counter >= list.size()) {
                        forCounters.remove(index);
                        forLists.remove(index);
                        removeVar(varName);
                        Integer end = blockEnd.get(index);
                        return end != null ? end + 1 : index + 1;
                    }

                    setVar(varName, list.get((int) counter));
                    return index + 1;
                }
            }

            if (line.equals("endfor")) {
                Integer start = loopStart.get(index);
                if (start != null) {
                    forCounters.merge(start, 1.0, Double::sum);
                    return start;
                }
                return index + 1;
            }

            if (kw.equals("def")) {
                Integer end = blockEnd.get(index);
                return end != null ? end + 1 : index + 1; // skip body during linear execution
            }

            if (line.equals("enddef")) {
                return index + 1;
            }

            if (kw.equals("class")) {
                Integer end = blockEnd.get(index);
                return end != null ? end + 1 : index + 1; // skip class body
            }

            if (line.equals("endclass")) {
                return index + 1;
            }

            if (kw.equals("return")) {
                return index + 1; // no-op if reached outside a function call
            }

            // compound assignment: name += expr (also -=, *=, /=)
            for (String op : new String[]{"+=", "-=", "*=", "/="}) {
                int opIdx = line.indexOf(op);
                if (opIdx > 0) {
                    String varName = line.substring(0, opIdx).trim();
                    if (varName.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
                        Object current = getVar(varName);
                        Object rhs = evaluateExpression(line.substring(opIdx + 2).trim());
                        Object result = switch (op) {
                            case "+=" -> (current instanceof String || rhs instanceof String)
                                    ? stringify(current) + stringify(rhs)
                                    : toNumber(current) + toNumber(rhs);
                            case "-=" -> toNumber(current) - toNumber(rhs);
                            case "*=" -> toNumber(current) * toNumber(rhs);
                            case "/=" -> toNumber(current) / toNumber(rhs);
                            default -> current;
                        };
                        setVar(varName, result);
                        return index + 1;
                    }
                }
            }

            // attribute assignment: name.field = expr
            if (line.contains(".") && line.contains("=")
                    && !line.contains("==")
                    && !line.contains("!=")
                    && !line.contains(">=")
                    && !line.contains("<=")) {

                int eq = line.indexOf('=');

                // Only inspect the left-hand side
                String lhs = line.substring(0, eq).trim();

                if (lhs.matches("^[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*$")) {

                    int dot = lhs.indexOf('.');

                    String objExpr = lhs.substring(0, dot).trim();
                    String fieldName = lhs.substring(dot + 1).trim();
                    String valueExpr = line.substring(eq + 1).trim();

                    Object obj = getVar(objExpr);
                    Object value = evaluateExpression(valueExpr);

                    if (obj instanceof Instance inst) {
                        inst.fields.put(fieldName, value);
                        return index + 1;

                    } else if (obj instanceof NativeInstance ni) {
                        ni.setField(fieldName, value);
                        return index + 1;
                    }
                }
            }

            // indexed assignment: name[index] = expr
            if (line.contains("[") && line.contains("=") && !line.contains("==")
                    && !line.contains("!=") && !line.contains(">=") && !line.contains("<=")) {

                int bracketOpen = line.indexOf('[');
                int bracketClose = line.indexOf(']', bracketOpen);
                int eq = bracketClose == -1 ? -1 : line.indexOf('=', bracketClose);

                if (bracketOpen > 0 && bracketClose > bracketOpen && eq > bracketClose) {
                    String varName = line.substring(0, bracketOpen).trim();

                    // only a real assignment if the left side is a plain variable name
                    // (otherwise it's something like dummy(list[0], x=1))
                    if (varName.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
                        String indexExpr = line.substring(bracketOpen + 1, bracketClose).trim();
                        String valueExpr = line.substring(eq + 1).trim();

                        int idx = (int) evaluateNumeric(indexExpr);
                        Object value = evaluateExpression(valueExpr);

                        setIndex(varName, idx, value);
                        return index + 1;
                    }
                }
            }

            // multi-assignment: a, b, c = expr1, expr2, expr3  OR  a, b, c = exprReturningList
            if (line.contains(",") && line.contains("=") && !line.contains("==")
                    && !line.contains("!=") && !line.contains(">=") && !line.contains("<=")
                    && !line.contains("[")) {

                int eq = line.indexOf('=');
                String left = line.substring(0, eq).trim();

                if (left.matches("^[A-Za-z_][A-Za-z0-9_]*(\\s*,\\s*[A-Za-z_][A-Za-z0-9_]*)+$")) {
                    String[] names = left.split("\\s*,\\s*");
                    String rightStr = line.substring(eq + 1).trim();
                    List<String> rightParts = splitTopLevel(rightStr, ',');

                    List<Object> values;
                    if (rightParts.size() > 1) {
                        values = new ArrayList<>();
                        for (String part : rightParts) {
                            values.add(evaluateExpression(part.trim()));
                        }
                    } else {
                        Object result = evaluateExpression(rightStr);
                        if (result instanceof List<?> list) {
                            values = new ArrayList<>(list);
                        } else {
                            return index + 1;
                        }
                    }

                    for (int i = 0; i < names.length; i++) {
                        setVar(names[i], i < values.size() ? values.get(i) : 0.0);
                    }
                    return index + 1;
                }
            }


            // plain assignment: name = expr
            if (line.contains("=") && !line.contains("==") && !line.contains("!=")
                    && !line.contains(">=") && !line.contains("<=")) {
                String[] split = line.split("=", 2);
                String lhs = split[0].trim();

                // only a real assignment if the left side is a plain variable name
                // (otherwise it's something like dummy(x=1), a call with a named argument)
                if (lhs.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
                    setVar(lhs, evaluateExpression(split[1].trim()));
                    return index + 1;
                }
            }

            // bare expression statement, e.g. a function call or method call used only for its side effects
            if (line.matches("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*\\s*\\(.*\\)$")) {
                evaluateExpression(line);
                return index + 1;
            }

        } catch (Exception e) {
            AeroComputers.LOGGER.error("Interpreter error on line {}: {}", index, line, e);
        }
        return index + 1;
    }

    private List<String> splitTopLevel(String s, char delim) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == delim && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    private int printsThisTick = 0;
    private static final int MAX_PRINTS_PER_TICK = 5;

    private void printLine(String line) {
        if (printsThisTick >= MAX_PRINTS_PER_TICK) {
            return;
        }
        printsThisTick++;

        String argsStr = extractBrackets(line);

        List<String> savedToks = toks;
        int savedPos = pos;

        toks = tokenize(argsStr);
        pos = 0;

        CallArgs callArgs = new CallArgs();
        if (!peek().isEmpty()) {
            parseOneArg(callArgs);
            while (peek().equals(",")) {
                next();
                parseOneArg(callArgs);
            }
        }

        toks = savedToks;
        pos = savedPos;

        List<Object> resolved = resolveArgs(LanguageSpec.BUILTIN_FUNCTIONS.getOrDefault("print", List.of("text")), callArgs);
        Object result = resolved.isEmpty() ? "" : resolved.get(0);

        String message = stringify(result);
        for (Player player : level.players()) {
            player.sendSystemMessage(Component.literal(message));
        }
    }

    private void setIndex(String varName, int idx, Object value) {
        Object listObj = getVar(varName);
        if (!(listObj instanceof List<?>)) {
            return;
        }

        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) listObj;

        list.set(idx, value);
    }




    private String extractBrackets(String line) {

        int start = line.indexOf("(");
        int end = line.lastIndexOf(")");

        if(start == -1 || end == -1)
            return "";

        return line.substring(start + 1, end);
    }

    private List<String> toks;
    private int pos;

    private Object evaluateExpression(String expr) {
        List<String> savedToks = toks;
        int savedPos = pos;
        toks = tokenize(expr);
        pos = 0;
        Object result = parseOr();
        toks = savedToks;
        pos = savedPos;
        return result;
    }

    private Object parseOr() {
        Object left = parseAnd();
        while (peek().equalsIgnoreCase("or")) {
            next();
            Object right = parseAnd();
            left = toBool(left) || toBool(right);
        }
        return left;
    }

    private Object parseAnd() {
        Object left = parseNot();
        while (peek().equalsIgnoreCase("and")) {
            next();
            Object right = parseNot();
            left = toBool(left) && toBool(right);
        }
        return left;
    }

    private Object parseNot() {
        if (peek().equalsIgnoreCase("not")) {
            next();
            return !toBool(parseNot());
        }
        return parseComparison();
    }

    private String extractCondition(String line, String keyword) {
        String rest = line.substring(keyword.length()).trim();
        if (rest.startsWith("(") && rest.endsWith(")")) {
            return rest.substring(1, rest.length() - 1);
        }
        return rest;
    }

    /**
     * Expression tokenizer. Uses the shared lossless lexer and just drops what the
     * expression parser doesn't care about.
     */
    private List<String> tokenize(String expr) {
        List<String> tokens = new ArrayList<>();

        for (Token t : Lexer.lex(expr)) {
            switch (t.type()) {
                case WHITESPACE, COMMENT, UNKNOWN -> { }
                default -> {
                    String text = t.text();
                    if (!text.equals("{") && !text.equals("}")) {
                        tokens.add(text);
                    }
                }
            }
        }

        tokens.add("");
        return tokens;
    }

    private Object parseComparison() {
        Object left = parseAdditive();
        String op = peek();
        if (op.equals(">") || op.equals("<") || op.equals(">=") || op.equals("<=") || op.equals("==") || op.equals("!=")) {
            next();
            Object right = parseAdditive();
            double l = toNumber(left), r = toNumber(right);
            return switch (op) {
                case ">" -> l > r;
                case "<" -> l < r;
                case ">=" -> l >= r;
                case "<=" -> l <= r;
                case "==" -> Objects.equals(left, right) || l == r;
                case "!=" -> !(Objects.equals(left, right) || l == r);
                default -> false;
            };
        }
        return left;
    }

    private Object parseAdditive() {
        Object left = parseMultiplicative();
        while (peek().equals("+") || peek().equals("-")) {
            String op = next();
            Object right = parseMultiplicative();
            if (op.equals("+")) {
                left = (left instanceof String || right instanceof String)
                        ? stringify(left) + stringify(right)
                        : toNumber(left) + toNumber(right);
            } else {
                left = toNumber(left) - toNumber(right);
            }
        }
        return left;
    }

    private Object parseUnary() {
        if (peek().equals("-")) {
            next();
            return -toNumber(parseUnary());
        }
        return parsePower();
    }

    private Object parsePower() {
        Object left = parsePostfix();
        if (peek().equals("**")) {
            next();
            Object right = parseUnary(); // right-associative: 2**3**2 == 2**(3**2)
            return Math.pow(toNumber(left), toNumber(right));
        }
        return left;
    }

    private Object parseMultiplicative() {
        Object left = parseUnary();
        while (peek().equals("*") || peek().equals("/") || peek().equals("%")) {
            String op = next();
            Object right = parseUnary();
            left = switch (op) {
                case "*" -> toNumber(left) * toNumber(right);
                case "/" -> toNumber(left) / toNumber(right);
                case "%" -> toNumber(left) % toNumber(right);
                default -> left;
            };
        }
        return left;
    }

    private Object parsePostfix() {
        Object value = parsePrimary();

        while (true) {
            if (peek().equals("[")) {
                next();
                double idx = toNumber(parseComparison());
                if (peek().equals("]")) next();

                if (value instanceof List<?> list) {
                    int i = (int) idx;
                    value = (i >= 0 && i < list.size()) ? list.get(i) : 0.0;
                } else {
                    value = 0.0;
                }
            } else if (peek().equals(".")) {
                next();
                String member = next();

                if (peek().equals("(")) {
                    CallArgs callArgs = parseCallArgs();

                    if (value instanceof Instance inst) {
                        value = callInstanceMethod(inst, member, callArgs);
                    } else if (value instanceof NativeInstance ni) {
                        value = ni.callMethod(member, callArgs.positional);
                    } else {
                        value = 0.0;
                    }
                } else {
                    if (value instanceof Instance inst) {
                        value = inst.fields.getOrDefault(member, 0.0);
                    } else if (value instanceof NativeInstance ni) {
                        value = ni.getField(member);
                    } else {
                        value = 0.0;
                    }
                }
            } else {
                break;
            }
        }

        return value;
    }

    private Object parsePrimary() {
        String tok = next();
        if (tok.isEmpty()) return 0.0;

        if (tok.equals("(")) {
            Object value = parseComparison();
            if (peek().equals(")")) next();
            return value;
        }

        if (tok.equals("[")) {
            List<Object> list = new ArrayList<>();
            if (!peek().equals("]")) {
                list.add(parseComparison());
                while (peek().equals(",")) {
                    next();
                    list.add(parseComparison());
                }
            }
            if (peek().equals("]")) next();
            return list;
        }

        if (tok.startsWith("\"")) {
            return tok.length() >= 2 ? tok.substring(1, tok.length() - 1) : "";
        }

        if (tok.equalsIgnoreCase("true")) return true;
        if (tok.equalsIgnoreCase("false")) return false;

        char c = tok.charAt(0);
        if (Character.isDigit(c) || c == '.') {
            return Double.parseDouble(tok);
        }

        // module access: math.sqrt(...), hud.Label(...)
        if (LanguageSpec.MODULES.contains(tok) && peek().equals(".")) {
            next();                                   // '.'
            String member = next();
            String qualified = tok + "." + member;

            if (peek().equals("(")) {
                CallArgs callArgs = parseCallArgs();

                if (nativeClasses.containsKey(qualified)) {
                    List<String> paramNames = LanguageSpec.NATIVE_CLASSES.getOrDefault(qualified, List.of());
                    return nativeClasses.get(qualified).create(resolveArgs(paramNames, callArgs));
                }
                return callFunction(qualified, callArgs);
            }
            return 0.0;
        }

        // user-defined function or class: hi("tom"), Foo(1, 2)
        if (peek().equals("(")) {
            CallArgs callArgs = parseCallArgs();

            if (classes.containsKey(tok)) {
                return instantiate(tok, callArgs);
            }
            return callFunction(tok, callArgs);
        }

        return getVar(tok);
    }

    private String peek() { return pos < toks.size() ? toks.get(pos) : ""; }
    private String next() { return pos < toks.size() ? toks.get(pos++) : ""; }

    private double toNumber(Object value) {
        if (value instanceof Double d) return d;
        if (value instanceof Boolean b) return b ? 1.0 : 0.0;
        if (value instanceof String s) {
            try { return Double.parseDouble(s); } catch (Exception e) { return 0.0; }
        }
        return 0.0;
    }

    private boolean toBool(Object value) {
        if (value instanceof Boolean b) return b;
        return toNumber(value) != 0.0;
    }

    private String stringify(Object value) {
        if (value instanceof NativeInstance ni) {
            return ni.toString();
        }
        if (value instanceof Instance inst) {
            return inst.className + " object";
        }
        if (value instanceof Double d) {
            return (d == Math.floor(d) && !d.isInfinite()) ? String.valueOf(d.longValue()) : String.valueOf(d);
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(stringify(list.get(i)));
            }
            return sb.append("]").toString();
        }
        return String.valueOf(value);
    }

    private boolean evaluateCondition(String expr) {
        Object result = evaluateExpression(expr);
        return (result instanceof Boolean b) ? b : toNumber(result) != 0.0;
    }

    private double evaluateNumeric(String expr) {
        return toNumber(evaluateExpression(expr));
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
        if (this.level != null && !this.level.isClientSide()) {
            ComputerRegistry.register(computerName, this);
            restart();
        }
    }

    @Override
    public void remove() {
        super.remove();
        if (level != null && !level.isClientSide()) {
            ComputerRegistry.unregister(computerName, this);
        }
    }

    public void setComputerName(String name) {
        if (Objects.equals(this.computerName, name))
            return;

        boolean server = level != null && !level.isClientSide();
        if (server) ComputerRegistry.unregister(this.computerName, this);

        this.computerName = name;

        if (server) ComputerRegistry.register(name, this);

        setChanged();
        sync();
    }

    public void restart() {
        lineIndex = 0;
        variables.clear();
        forCounters.clear();
        forLists.clear();
        scopeStack.clear();
        scopeStack.push(variables);
        callDepth = 0;
        drawObjects.clear();
        commands.clear();
        drawObjectIdCounter = 0;
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

    private Object callMethod(Instance self, ProgramInfo.FunctionInfo def, CallArgs callArgs) {
        if (callDepth >= MAX_CALL_DEPTH) {
            AeroComputers.LOGGER.error("Max call depth exceeded calling method");
            return 0.0;
        }

        Map<String, Object> frame = new HashMap<>();
        frame.put("self", self);
        bindParams(def.params(), 1, callArgs, frame); // skip index 0 ("self")

        scopeStack.push(frame);
        callDepth++;

        Object returnValue = 0.0;
        int line = def.startLine() + 1;
        int iterations = 0;

        try {
            while (line < def.endLine() && iterations < MAX_FUNCTION_ITERATIONS) {
                String text = stripComment(programLines[line].trim());
                iterations++;

                if (isReturnLine(line)) {
                    returnValue = evaluateReturn(text);
                    break;
                }

                line = executeLine(text, line);
            }
        } finally {
            callDepth--;
            scopeStack.pop();
        }

        return returnValue;
    }

    private List<Integer> toColorList(Object value) {
        List<Integer> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) result.add((int) toNumber(o));
        }
        while (result.size() < 3) result.add(255);
        return result;
    }

    private List<Double> toPosList(Object value) {
        List<Double> result = new ArrayList<Double>();
        if (value instanceof List<?> list) {
            for (Object o : list) result.add((double) toNumber(o));
        }
        while (result.size() < 3) result.add(0.0);
        return result;
    }

    private List<Double> toDoubleList(Object value) {
        List<Double> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) result.add(toNumber(o));
        }
        return result;
    }

    private List<Object> toObjectList(List<? extends Number> list) {
        return new ArrayList<>(list);
    }

    private NativeInstance wrapDrawObject(DrawObject obj) {
        return new NativeInstance() {
            @Override
            public Object getField(String name) {
                return switch (name) {
                    case "x", "x1" -> obj.x;
                    case "y", "y1" -> obj.y;
                    case "x2" -> obj.x2;
                    case "y2" -> obj.y2;
                    case "text" -> obj.text;
                    case "color" -> toObjectList(obj.color);
                    case "center_color" -> toObjectList(obj.centerColor);
                    case "border_color" -> toObjectList(obj.borderColor);
                    case "font_size" -> obj.fontSize;
                    case "width" -> obj.width;
                    case "border_width" -> obj.borderWidth;
                    case "w" -> obj.w;
                    case "h" -> obj.h;
                    case "xs" -> toObjectList(obj.xs);
                    case "ys" -> toObjectList(obj.ys);
                    case "render_center" -> obj.renderCenter;
                    case "render_border" -> obj.renderBorder;
                    default -> 0.0;
                };
            }

            @Override
            public void setField(String name, Object value) {
                switch (name) {
                    case "x", "x1" -> obj.x = toNumber(value);
                    case "y", "y1" -> obj.y = toNumber(value);
                    case "x2" -> obj.x2 = toNumber(value);
                    case "y2" -> obj.y2 = toNumber(value);
                    case "text" -> obj.text = stringify(value);
                    case "color" -> obj.color = toColorList(value);
                    case "center_color" -> obj.centerColor = toColorList(value);
                    case "border_color" -> obj.borderColor = toColorList(value);
                    case "font_size" -> obj.fontSize = toNumber(value);
                    case "width" -> obj.width = toNumber(value);
                    case "border_width" -> obj.borderWidth = toNumber(value);
                    case "w" -> obj.w = toNumber(value);
                    case "h" -> obj.h = toNumber(value);
                    case "xs" -> obj.xs = toDoubleList(value);
                    case "ys" -> obj.ys = toDoubleList(value);
                    case "render_center" -> obj.renderCenter = toBool(value);
                    case "render_border" -> obj.renderBorder = toBool(value);
                }
                markDrawDirty();
            }

            @Override
            public Object callMethod(String name, List<Object> methodArgs) {
                if (name.equals("remove")) {
                    drawObjects.remove(obj);
                    markDrawDirty();
                }
                return 0.0;
            }

            @Override
            public String toString() {
                return obj.type + " draw object";
            }
        };
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

    private static class CallArgs {
        final List<Object> positional = new ArrayList<>();
        final Map<String, Object> named = new LinkedHashMap<>();
    }

    private CallArgs parseCallArgs() {
        CallArgs result = new CallArgs();
        next(); // consume '('
        if (!peek().equals(")")) {
            parseOneArg(result);
            while (peek().equals(",")) {
                next();
                parseOneArg(result);
            }
        }
        if (peek().equals(")")) next();
        return result;
    }

    private void parseOneArg(CallArgs result) {
        String name = peek();

        // named argument: name = expression
        if (name.matches("^[A-Za-z_][A-Za-z0-9_]*$")
                && pos + 1 < toks.size()
                && toks.get(pos + 1).equals("=")) {

            next(); // name
            next(); // =

            result.named.put(name, parseOr());
            return;
        }

        // positional argument
        result.positional.add(parseOr());
    }

    private void bindParams(List<ProgramInfo.ParamInfo> params, int skipFirst, CallArgs callArgs, Map<String, Object> frame) {
        int posIndex = 0;
        for (int i = skipFirst; i < params.size(); i++) {
            ProgramInfo.ParamInfo p = params.get(i);
            if (callArgs.named.containsKey(p.name())) {
                frame.put(p.name(), callArgs.named.get(p.name()));
            } else if (posIndex < callArgs.positional.size()) {
                frame.put(p.name(), callArgs.positional.get(posIndex));
                posIndex++;
            } else if (p.defaultExpr() != null) {
                frame.put(p.name(), evaluateExpression(p.defaultExpr()));
            } else {
                frame.put(p.name(), 0.0);
            }
        }
    }

    private Instance instantiate(String className, CallArgs callArgs) {
        ProgramInfo.ClassInfo cd = classes.get(className);
        Instance inst = new Instance();
        inst.className = className;

        ProgramInfo.FunctionInfo init = cd.methods().get("__init__");
        if (init != null) {
            callMethod(inst, init, callArgs);
        }
        return inst;
    }

    private Object callInstanceMethod(Instance inst, String methodName, CallArgs callArgs) {
        ProgramInfo.ClassInfo cd = classes.get(inst.className);
        if (cd == null) return 0.0;

        ProgramInfo.FunctionInfo method = cd.methods().get(methodName);
        if (method == null) {
            AeroComputers.LOGGER.warn("No such method {} on class {}", methodName, inst.className);
            return 0.0;
        }
        return callMethod(inst, method, callArgs);
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



    private List<Object> resolveArgs(List<String> paramNames, CallArgs callArgs) {
        if (callArgs.named.isEmpty()) {
            return callArgs.positional; // fast path, no named args used
        }

        List<Object> result = new ArrayList<>();
        int posIndex = 0;

        for (String paramName : paramNames) {
            if (callArgs.named.containsKey(paramName)) {
                result.add(callArgs.named.get(paramName));
            } else if (posIndex < callArgs.positional.size()) {
                result.add(callArgs.positional.get(posIndex));
                posIndex++;
            } else {
                break; // no more positional values and no named override — stop, let the callee's own arg.size() checks handle missing trailing args
            }
        }

        return result;
    }
}