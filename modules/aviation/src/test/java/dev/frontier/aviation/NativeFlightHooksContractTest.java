package dev.frontier.aviation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Structural contracts against the exact production jars; does not claim mixin application. */
public final class NativeFlightHooksContractTest {
    private static final String V = "com/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity";
    private static final String E = "com/atsuishio/superbwarfare/entity/vehicle/utils/VehicleEngineUtils";
    private static final String I = "com/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$";
    public static void main(String[] args) throws Exception {
        Path root = args.length == 0 ? projectRoot() : Path.of(args[0]);
        Path mods = root.resolve("instance/mods");
        try (JarFile sbw = new JarFile(mods.resolve("superbwarfare-0.8.9.1-hotfix-mc1.20.1-993063bed-all.jar").toFile());
             JarFile dsm = new JarFile(mods.resolve("dsm-0.9.0.jar").toFile())) {
            ClassNode engine = read(sbw, E);
            verifyEngine(engine, "aircraftEngine", "Aircraft", new int[]{5,1,4,1,11,4,3}, 19, 16, "getPower");
            verifyEngine(engine, "helicopterEngine", "Helicopter", new int[]{6,2,4,4,9,4,3}, 10, 9, "getSynchedPropellerRot");
            verifyEngine(engine, "tomEngine", "Tom6", new int[]{2,1,3,0,6,1,1}, 10, 9, "getPower");
            MethodNode aircraft = method(engine, "aircraftEngine", "(L" + V + ";L" + I + "Aircraft;)V");
            equal(1, calls(aircraft, E, "updateRotation", "(L" + V + ";)V").size(), "indirect passive alignment");
            ClassNode su24 = read(dsm, "dev/sbw/deepstrike/vehicle/Su24mEntity");
            MethodNode tick = method(su24, "m_8119_", "()V");
            equal(1, calls(tick, su24.name, "setPower", "(F)V").size(), "Su24 late power write");
            method(read(sbw, "com/atsuishio/superbwarfare/entity/vehicle/Tom6Entity"), "engineRunning", "()Z");
            method(read(sbw, V), "m_7378_", "(Lnet/minecraft/nbt/CompoundTag;)V");
            // Verify the actual compiled injection declarations resolve against pinned bytecode.
            verifyTickOrder(sbw);
            verifyMixin("FlightNativePhysics", engine);
            verifyMixin("FlightNativeEndTick", read(sbw, V));
            verifyMixin("FlightNativePersistence", read(sbw, V));
            verifyMixin("FlightSu24Throttle", su24);
            verifyMixin("FlightTomEngineSound", read(sbw, "com/atsuishio/superbwarfare/entity/vehicle/Tom6Entity"));
        }
        System.out.println("Native flight pinned bytecode contracts passed (not runtime acceptance)");
    }
    private static void verifyTickOrder(JarFile sbw) throws IOException {
        ClassNode vehicle = read(sbw, V);
        MethodNode tick = method(vehicle, "m_6075_", "()V");
        List<MethodInsnNode> travel = calls(tick, V, "travel", "()V");
        List<MethodInsnNode> terrain = calls(tick, V, "terrainCompact", "(Ljava/util/List;)V");
        equal(1, travel.size(), "baseTick travel");
        equal(1, terrain.size(), "baseTick terrain");
        if (tick.instructions.indexOf(travel.get(0)) >= tick.instructions.indexOf(terrain.get(0)))
            throw new AssertionError("terrain must follow travel");
        int returns = 0;
        for (var insn : tick.instructions) if (insn.getOpcode() == Opcodes.RETURN) {
            returns++;
            if (tick.instructions.indexOf(insn) <= tick.instructions.indexOf(terrain.get(0)))
                throw new AssertionError("tick returns before terrain");
        }
        equal(1, returns, "baseTick returns");
        MethodNode travelMethod = method(vehicle, "travel", "()V");
        equal(1, calls(travelMethod, I.substring(0, I.length() - 1), "work", "(L" + V + ";)V").size(), "travel engine dispatch");
        for (String type : new String[]{"Aircraft", "Helicopter", "Tom6"}) {
            String name = type.equals("Aircraft") ? "aircraftEngine" : type.equals("Helicopter") ? "helicopterEngine" : "tomEngine";
            MethodNode work = method(read(sbw, I + type), "work", "(L" + V + ";)V");
            equal(1, calls(work, E, name, "(L" + V + ";L" + I + type + ";)V").size(), type + " work dispatch");
        }
        MethodNode compact = method(vehicle, "terrainCompact", "(Ljava/util/List;)V");
        String motion = "com/atsuishio/superbwarfare/entity/vehicle/utils/VehicleMotionUtils";
        equal(1, calls(compact, motion, "terrainCompact", "(L" + V + ";Ljava/util/List;)V").size(), "terrain delegate");
        MethodNode nativeCompact = method(read(sbw, motion), "terrainCompact", "(L" + V + ";Ljava/util/List;)V");
        if (calls(nativeCompact, V, "m_146926_", "(F)V").isEmpty() || calls(nativeCompact, V, "setZRot", "(F)V").isEmpty())
            throw new AssertionError("terrain no longer writes pitch and roll");
        ClassNode early = compiledMixin("FlightNativePhysics");
        ClassNode late = compiledMixin("FlightNativeEndTick");
        String hooks = "dev/frontier/aviation/control/nativebridge/FlightNativeHooks";
        int earlyAfter = 0, earlyBefore = 0, lateAfter = 0;
        for (MethodNode m : early.methods) {
            earlyAfter += calls(m, hooks, "afterPhysics", "(Lnet/minecraft/world/entity/Entity;)V").size();
            earlyBefore += calls(m, hooks, "beforePhysics", "(Lnet/minecraft/world/entity/Entity;)V").size();
        }
        for (MethodNode m : late.methods) lateAfter += calls(m, hooks, "afterPhysics", "(Lnet/minecraft/world/entity/Entity;)V").size();
        equal(3, earlyBefore, "engine entry callbacks");
        equal(0, earlyAfter, "early exit callbacks");
        equal(1, lateAfter, "late exit callback");
        MethodNode handler = method(late, "flight$endTick", "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V");
        AnnotationNode inject = handler.visibleAnnotations.stream().filter(a -> a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        Object atValue = value(inject, "at");
        AnnotationNode at = atValue instanceof List<?> list ? (AnnotationNode) list.get(0) : (AnnotationNode) atValue;
        if (!"TAIL".equals(value(at, "value"))) throw new AssertionError("capture must be at tick TAIL");
    }
    private static ClassNode compiledMixin(String name) throws IOException {
        String resource = "dev/frontier/aviation/mixin/" + name + ".class";
        ClassNode result = new ClassNode();
        try (var in = NativeFlightHooksContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new AssertionError("Missing compiled mixin " + resource);
            new ClassReader(in).accept(result, 0);
        }
        return result;
    }
    private static void verifyEngine(ClassNode owner, String name, String type, int[] expected,
                                     int getterCount, int propulsionOrdinal, String getter) {
        MethodNode m = method(owner, name, "(L" + V + ";L" + I + type + ";)V");
        String[] setters = {"m_146926_", "m_146922_", "setZRot", "setRoll", "setPower", "setEngineStart", "setEngineStartOver"};
        for (int n = 0; n < setters.length; n++) equal(expected[n], calls(m, V, setters[n], n < 5 ? "(F)V" : "(Z)V").size(), name + "." + setters[n]);
        List<MethodInsnNode> getters = calls(m, V, getter, "()F");
        equal(getterCount, getters.size(), name + " getter count");
        MethodInsnNode selected = getters.get(propulsionOrdinal);
        // The gated getter is consumed by a vector scale and native velocity write, not energy/rotor bookkeeping.
        boolean scale = false, motion = false;
        int remaining = 36;
        for (var insn = selected.getNext(); insn != null && remaining-- > 0; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call) {
                if (call.owner.equals("net/minecraft/world/phys/Vec3") && call.desc.equals("(D)Lnet/minecraft/world/phys/Vec3;")) scale = true;
                if (call.owner.equals(V) && call.name.equals("m_20256_")) { motion = true; break; }
            }
        }
        if (!scale || !motion) throw new AssertionError(name + " propulsion ordinal no longer feeds native motion");
    }
    private static void verifyMixin(String name, ClassNode target) throws IOException {
        String resource = "dev/frontier/aviation/mixin/" + name + ".class";
        ClassNode mixin = new ClassNode();
        try (var in = NativeFlightHooksContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new AssertionError("Missing compiled mixin " + resource);
            new ClassReader(in).accept(mixin, 0);
        }
        int checked = 0;
        for (MethodNode handler : mixin.methods) {
            List<AnnotationNode> annotations = new ArrayList<>();
            if (handler.visibleAnnotations != null) annotations.addAll(handler.visibleAnnotations);
            if (handler.invisibleAnnotations != null) annotations.addAll(handler.invisibleAnnotations);
            for (AnnotationNode annotation : annotations) {
                boolean redirect = annotation.desc.endsWith("/Redirect;");
                if (!redirect && !annotation.desc.endsWith("/Inject;")) continue;
                @SuppressWarnings("unchecked") List<String> selectors = (List<String>) value(annotation, "method");
                int require = (Integer) value(annotation, "require");
                if (require < 1) throw new AssertionError("Optional native anchor: " + handler.name);
                Object atValue = value(annotation, "at");
                AnnotationNode at = atValue instanceof List<?> list ? (AnnotationNode) list.get(0) : (AnnotationNode) atValue;
                for (String selector : selectors) {
                    int paren = selector.indexOf('(');
                    MethodNode selected = method(target, selector.substring(0, paren), selector.substring(paren));
                    if (redirect) {
                        String invoke = (String) value(at, "target");
                        int endOwner = invoke.indexOf(';');
                        int desc = invoke.indexOf('(', endOwner);
                        List<MethodInsnNode> calls = calls(selected, invoke.substring(1, endOwner), invoke.substring(endOwner + 1, desc), invoke.substring(desc));
                        Object ordinal = value(at, "ordinal");
                        equal(ordinal == null ? require : 1, ordinal == null ? calls.size() : calls.size() > (Integer) ordinal ? 1 : 0,
                            name + "." + handler.name);
                    }
                    checked++;
                }
            }
        }
        if (checked == 0) throw new AssertionError("No compiled injection declarations " + name);
    }
    private static Object value(AnnotationNode node, String key) {
        if (node.values != null) for (int i = 0; i < node.values.size(); i += 2) if (node.values.get(i).equals(key)) return node.values.get(i + 1);
        return null;
    }
    private static List<MethodInsnNode> calls(MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (var insn : method.instructions) if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor)) result.add(call);
        return result;
    }
    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst()
            .orElseThrow(() -> new AssertionError("Missing pinned method " + owner.name + "." + name + descriptor));
    }
    private static ClassNode read(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name + ".class");
        if (entry == null) throw new AssertionError("Missing pinned class " + name);
        ClassNode result = new ClassNode();
        try (var stream = jar.getInputStream(entry)) { new ClassReader(stream).accept(result, 0); }
        return result;
    }
    private static Path projectRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("modpack.lock.json"))) path = path.getParent();
        if (path == null) throw new AssertionError("Run inside the project or pass project root");
        return path;
    }
    private static void equal(int expected, int actual, String label) {
        if (expected != actual) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }
}
