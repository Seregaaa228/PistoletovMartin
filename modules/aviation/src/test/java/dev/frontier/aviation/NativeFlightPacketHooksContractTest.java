package dev.frontier.aviation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Compiled packet/client declarations against pinned bytecode; does not prove runtime mixin application. */
public final class NativeFlightPacketHooksContractTest {
    private static final String V = "com/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity";
    private static final String DRONE = "com/atsuishio/superbwarfare/entity/vehicle/DroneEntity";
    private static final String SEND = "com/atsuishio/superbwarfare/network/message/send/";
    private static final String EVENT = "com/atsuishio/superbwarfare/event/";
    private static final String MIXIN = "dev/frontier/aviation/mixin/";
    private static final String HANDLER = "(Ljava/util/function/Supplier;)V";
    private static int assertions;
    public static void main(String[] args) throws Exception {
        Path root = args.length == 0 ? projectRoot() : Path.of(args[0]);
        Path minecraft = root.resolve("vendor/gradle-cache/caches/forge_gradle/mcp_repo/net/minecraft/joined/1.20.1-20230612.114412/joined-1.20.1-20230612.114412-srg.jar");
        try (JarFile sbw = new JarFile(root.resolve("instance/mods/superbwarfare-0.8.9.1-hotfix-mc1.20.1-993063bed-all.jar").toFile());
             JarFile mc = new JarFile(minecraft.toFile())) {
            ClassNode movement = read(sbw, SEND + "VehicleMovementMessage");
            ClassNode mouse = read(sbw, SEND + "MouseMoveMessage");
            ClassNode movementGuard = compiled(MIXIN + "FlightServerMovementGuard");
            ClassNode mouseGuard = compiled(MIXIN + "FlightServerMouseGuard");
            verifyMixinTarget(movementGuard, movement.name);
            verifyMixinTarget(mouseGuard, mouse.name);
            verifyInvocation(movementGuard, "aviation$flightMovement", movement, "handler", HANDLER,
                "L" + V + ";processInput(S)V", 1, 1);
            verifyInvocation(mouseGuard, "aviation$flightMouse", mouse, "handler", HANDLER,
                "L" + V + ";mouseInput(DD)V", 1, 1);
            MethodNode nativeMouse = method(mouse, "handler", HANDLER);
            equal(1, calls(nativeMouse, DRONE, "mouseInput", "(DD)V").size(), "separate pinned UAV mouse invocation");
            for (MethodNode handler : mouseGuard.methods) {
                equal(0, calls(handler, DRONE, "mouseInput", "(DD)V").size(), "guard never redirects/replays UAV call");
                for (AnnotationNode annotation : annotations(handler)) {
                    Object at = value(annotation, "at");
                    if (at instanceof AnnotationNode anchor) check(!String.valueOf(value(anchor, "target")).contains(DRONE), "guard anchor excludes drone");
                }
            }
            // F's six client mixins: verify exact production invocation/field/method anchors too.
            ClassNode attitude = compiled(MIXIN + "FlightClientAttitude");
            ClassNode vehicle = read(sbw, V);
            // Pinned render getters interpolate raw degrees; previous fields must be unwrapped by our client.
            for (String getter : List.of("getRoll", "getYaw", "getPitch")) {
                equal(1, calls(method(vehicle, getter, "(F)F"), "net/minecraft/util/Mth", "m_14179_", "(FFF)F").size(),
                    "pinned raw angle interpolation " + getter);
            }
            verifyMixinTarget(attitude, V);
            verifyInvocation(attitude, "aviation$yaw", vehicle, "handleClientSync", "()V", "L" + V + ";m_146922_(F)V", 1, 1);
            verifyInvocation(attitude, "aviation$pitch", vehicle, "handleClientSync", "()V", "L" + V + ";m_146926_(F)V", 1, 1);
            ClassNode mousePackets = compiled(MIXIN + "FlightClientMousePackets");
            ClassNode nativeMouseClient = read(sbw, EVENT + "ClientMouseHandler");
            verifyMixinTarget(mousePackets, nativeMouseClient.name);
            verifyInvocation(mousePackets, "aviation$mousePacket", nativeMouseClient, "handleClientTick",
                "(Lnet/minecraftforge/event/TickEvent$ClientTickEvent;)V",
                "Lcom/atsuishio/superbwarfare/tools/MinecraftUtil;sendPacketToServer(Ljava/lang/Object;)V", 6, 6);
            ClassNode movementPackets = compiled(MIXIN + "FlightClientMovementPackets");
            ClassNode nativeClient = read(sbw, EVENT + "ClientEventHandler");
            verifyMixinTarget(movementPackets, nativeClient.name);
            verifyInvocation(movementPackets, "aviation$movementBits", nativeClient, "handleControlVehicle",
                "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)V", "L" + SEND + "VehicleMovementMessage;<init>(S)V", 1, 1);
            ClassNode keyConflicts = compiled(MIXIN + "FlightClientKeyConflicts");
            ClassNode nativeKeys = read(sbw, EVENT + "ClickEventHandler");
            verifyMixinTarget(keyConflicts, nativeKeys.name);
            verifyInvocation(keyConflicts, "aviation$editBinding", nativeKeys, "onKeyPressed",
                "(Lnet/minecraftforge/client/event/InputEvent$Key;)V",
                "Lnet/minecraft/client/KeyMapping;getKey()Lcom/mojang/blaze3d/platform/InputConstants$Key;", 30, 1);
            MethodNode editBinding = keyConflicts.methods.stream().filter(m -> m.name.equals("aviation$editBinding")).findFirst().orElseThrow();
            equal(1, calls(editBinding, "net/minecraftforge/client/settings/KeyMappingLookup", "getAll",
                "(Lcom/mojang/blaze3d/platform/InputConstants$Key;)Ljava/util/List;").size(), "edit conflict uses Forge modifier-prioritized lookup");
            equal(2, calls(editBinding, "java/util/List", "contains", "(Ljava/lang/Object;)Z").size(), "gear and hover must both be selected by Forge");
            ClassNode nativeMouseHandler = read(mc, "net/minecraft/client/MouseHandler");
            ClassNode capture = compiled(MIXIN + "FlightClientMouseCapture");
            verifyMixinTarget(capture, nativeMouseHandler.name);
            verifyHeadAliases(capture, "aviation$rawMouse", nativeMouseHandler, "m_91561_", "(JDD)V");
            verifyHeadAliases(capture, "aviation$pilotLook", nativeMouseHandler, "m_91523_", "()V");
            int shadows = 0;
            for (FieldNode field : capture.fields) for (AnnotationNode annotation : annotations(field)) {
                if (!annotation.desc.endsWith("/Shadow;")) continue;
                @SuppressWarnings("unchecked") List<String> aliases = (List<String>) value(annotation, "aliases");
                check(aliases != null && nativeMouseHandler.fields.stream().anyMatch(f -> aliases.contains(f.name) && f.desc.equals(field.desc)), "production mouse field alias " + field.name);
                shadows++;
            }
            equal(6, shadows, "mouse capture shadow fields");
            ClassNode invoker = compiled(MIXIN + "FlightMouseInvoker");
            verifyMixinTarget(invoker, nativeMouseHandler.name);
            MethodNode invoke = invoker.methods.stream().filter(m -> m.name.equals("aviation$onMove")).findFirst().orElseThrow();
            AnnotationNode annotation = annotation(invoke, "/Invoker;");
            check("m_91561_".equals(value(annotation, "value")), "mouse invoker production name");
            method(nativeMouseHandler, "m_91561_", invoke.desc);
        }
        try (JarFile curios = new JarFile(root.resolve("instance/mods/curios-forge-5.14.1+1.20.1.jar").toFile())) {
            ClassNode handler = read(curios, "top/theillusivec4/curios/client/ClientEventHandler");
            ClassNode registry = read(curios, "top/theillusivec4/curios/client/KeyRegistry");
            ClassNode mixin = compiled(MIXIN + "FlightCuriosKeyConflict");
            verifyMixinTarget(mixin, handler.name);
            check(annotations(mixin).stream().anyMatch(a -> a.desc.endsWith("/Pseudo;")), "Curios mixin is optional when Curios is absent");
            verifyHeadAliases(mixin, "aviation$consumeConflictingCurios", handler, "onKeyInput", "(Lnet/minecraftforge/client/event/InputEvent$Key;)V");
            MethodNode injection = mixin.methods.stream().filter(m -> m.name.equals("aviation$consumeConflictingCurios")).findFirst().orElseThrow();
            equal(2, calls(injection, "net/minecraftforge/client/event/InputEvent$Key", "getAction", "()I").size(), "Curios conflict checks both press and repeat actions");
            check(hasIntConstant(injection, 1) && hasIntConstant(injection, 2), "Curios conflict checks GLFW PRESS and REPEAT");
            equal(1, calls(injection, "net/minecraftforge/client/settings/KeyMappingLookup", "getAll", "(Lcom/mojang/blaze3d/platform/InputConstants$Key;)Ljava/util/List;").size(), "Curios conflict uses Forge selected mappings");
            equal(1, calls(injection, "net/minecraft/client/KeyMapping", "consumeClick", "()Z").size(), "only selected Curios click is consumed");
            equal(1, calls(method(handler, "onClientTick", "(Lnet/minecraftforge/event/TickEvent$ClientTickEvent;)V"), "net/minecraft/client/KeyMapping", "m_90859_", "()Z").size(), "pinned Curios consumes queued key at tick end");
            check(hasConstant(method(registry, "<clinit>", "()V"), "key.curios.open.desc") && hasConstant(injection, "key.curios.open.desc"), "Curios binding identity matches pinned registry");
        }
        // Regression: cadence broadcast cannot add another explicit pilot delivery.
        ClassNode network = compiled("dev/frontier/aviation/control/net/FlightNetwork");
        MethodNode broadcast = method(network, "broadcastSnapshot", "(Lnet/minecraft/world/entity/Entity;Ldev/frontier/aviation/control/FlightSnapshot;)V");
        equal(1, calls(broadcast, "net/minecraftforge/network/simple/SimpleChannel", "send", "(Lnet/minecraftforge/network/PacketDistributor$PacketTarget;Ljava/lang/Object;)V").size(), "single tracking broadcast");
        equal(0, calls(broadcast, network.name, "sendSnapshot", "(Lnet/minecraft/server/level/ServerPlayer;Ldev/frontier/aviation/control/FlightSnapshot;)V").size(), "no duplicated cadence owner send");
        ClassNode client = compiled("dev/frontier/aviation/control/client/FlightClient");
        MethodNode beforePhysics = method(client, "beforePhysics", "(Lnet/minecraft/world/entity/Entity;)V");
        equal(1, calls(beforePhysics, "dev/frontier/aviation/control/client/FlightCommandBuffer", "next", "()Ldev/frontier/aviation/control/PilotCommand;").size(),
            "one command frame consumed per guarded physics callback");
        String submitDescriptor = "(Ldev/frontier/aviation/control/ControlLease;Ldev/frontier/aviation/control/PilotCommand;)V";
        equal(1, calls(beforePhysics, network.name, "submit", submitDescriptor).size(), "physics callback submits its command frame once");
        equal(0, calls(method(client, "tick", "(Lnet/minecraftforge/event/TickEvent$ClientTickEvent;)V"), network.name, "submit", submitDescriptor).size(),
            "input tick cannot independently submit a simulation command");
        equal(3, calls(method(client, "afterPhysics", "(Lnet/minecraft/world/entity/Entity;)V"),
            "dev/frontier/aviation/control/client/FlightRenderAngles", "previous", "(FF)F").size(),
            "client unwraps all three previous render angles after native physics");
        System.out.println("NativeFlightPacketHooksContractTest: " + assertions + " assertions passed (structural only)");
    }
    private static void verifyInvocation(ClassNode mixin, String handlerName, ClassNode target, String methodName,
                                         String descriptor, String invocation, int pinnedCount, int require) {
        MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals(handlerName)).findFirst().orElseThrow();
        AnnotationNode injection = annotations(handler).stream().filter(a -> a.desc.endsWith("/Redirect;") || a.desc.endsWith("/ModifyArg;")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") List<String> selectors = (List<String>) value(injection, "method");
        check(selectors.contains(methodName) || selectors.contains(methodName + descriptor), "compiled method selector " + handlerName);
        equal(require, (Integer) value(injection, "require"), "required anchor " + handlerName);
        Object allow = value(injection, "allow"); if (allow != null) equal(pinnedCount, (Integer) allow, "bounded anchor " + handlerName);
        AnnotationNode at = (AnnotationNode) value(injection, "at");
        check("INVOKE".equals(value(at, "value")) && invocation.equals(value(at, "target")), "compiled invocation selector " + handlerName);
        int ownerEnd = invocation.indexOf(';'), desc = invocation.indexOf('(', ownerEnd);
        equal(pinnedCount, calls(method(target, methodName, descriptor), invocation.substring(1, ownerEnd),
            invocation.substring(ownerEnd + 1, desc), invocation.substring(desc)).size(), "pinned call count " + handlerName);
    }
    private static void verifyHeadAliases(ClassNode mixin, String handlerName, ClassNode target, String nativeName, String descriptor) {
        MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals(handlerName)).findFirst().orElseThrow();
        AnnotationNode injection = annotation(handler, "/Inject;");
        @SuppressWarnings("unchecked") List<String> selectors = (List<String>) value(injection, "method");
        check(selectors.contains(nativeName + descriptor), "production mouse method alias " + handlerName);
        method(target, nativeName, descriptor); equal(1, (Integer) value(injection, "require"), "required mouse capture " + handlerName);
        Object anchors = value(injection, "at"); AnnotationNode at = anchors instanceof List<?> list ? (AnnotationNode) list.get(0) : (AnnotationNode) anchors;
        check("HEAD".equals(value(at, "value")), "raw mouse HEAD " + handlerName);
    }
    private static void verifyMixinTarget(ClassNode mixin, String expected) {
        AnnotationNode annotation = annotations(mixin).stream().filter(a -> a.desc.endsWith("/Mixin;")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") List<String> targets = (List<String>) value(annotation, "targets");
        @SuppressWarnings("unchecked") List<Type> values = (List<Type>) value(annotation, "value");
        check(targets != null && targets.size() == 1 && targets.get(0).replace('.', '/').equals(expected)
            || values != null && values.size() == 1 && values.get(0).getInternalName().equals(expected), "exact compiled mixin owner " + mixin.name);
        check(Boolean.FALSE.equals(value(annotation, "remap")), "pinned non-remapped mixin " + mixin.name);
    }
    private static AnnotationNode annotation(MethodNode method, String suffix) {
        return annotations(method).stream().filter(a -> a.desc.endsWith(suffix)).findFirst().orElseThrow();
    }
    private static List<AnnotationNode> annotations(Object node) {
        List<AnnotationNode> visible, invisible;
        if (node instanceof MethodNode m) { visible = m.visibleAnnotations; invisible = m.invisibleAnnotations; }
        else if (node instanceof FieldNode f) { visible = f.visibleAnnotations; invisible = f.invisibleAnnotations; }
        else { ClassNode c = (ClassNode) node; visible = c.visibleAnnotations; invisible = c.invisibleAnnotations; }
        List<AnnotationNode> result = new ArrayList<>(); if (visible != null) result.addAll(visible); if (invisible != null) result.addAll(invisible); return result;
    }
    private static Object value(AnnotationNode node, String key) {
        if (node.values != null) for (int i = 0; i < node.values.size(); i += 2) if (node.values.get(i).equals(key)) return node.values.get(i + 1); return null;
    }
    private static List<MethodInsnNode> calls(MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (var insn : method.instructions) if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor)) result.add(call);
        return result;
    }
    private static boolean hasConstant(MethodNode method, String value) {
        for (var insn : method.instructions) if (insn instanceof LdcInsnNode constant && value.equals(constant.cst)) return true;
        return false;
    }
    private static boolean hasIntConstant(MethodNode method, int value) {
        for (var insn : method.instructions) {
            if (insn.getOpcode() == org.objectweb.asm.Opcodes.ICONST_0 + value) return true;
            if (insn instanceof IntInsnNode constant && constant.operand == value) return true;
            if (insn instanceof LdcInsnNode constant && Integer.valueOf(value).equals(constant.cst)) return true;
        }
        return false;
    }
    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst()
            .orElseThrow(() -> new AssertionError("Missing pinned method " + owner.name + "." + name + descriptor));
    }
    private static ClassNode compiled(String name) throws IOException {
        try (var in = NativeFlightPacketHooksContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (in == null) throw new AssertionError("Missing compiled declaration " + name); ClassNode result = new ClassNode(); new ClassReader(in).accept(result, 0); return result;
        }
    }
    private static ClassNode read(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name + ".class"); if (entry == null) throw new AssertionError("Missing pinned class " + name);
        try (var in = jar.getInputStream(entry)) { ClassNode result = new ClassNode(); new ClassReader(in).accept(result, 0); return result; }
    }
    private static Path projectRoot() {
        Path path = Path.of("").toAbsolutePath(); while (path != null && !Files.exists(path.resolve("modpack.lock.json"))) path = path.getParent();
        if (path == null) throw new AssertionError("Run inside project or pass project root"); return path;
    }
    private static void equal(int expected, int actual, String message) { check(expected == actual, message + ": expected " + expected + ", got " + actual); }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
