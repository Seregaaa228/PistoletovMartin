package dev.frontier.aviation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Structural test for the terminal destruction hook. It deliberately does not run a game
 * or call vehicleExplosion; native behavior is checked against the pinned class file.
 */
public final class AircraftTerminalDestructionContractTest {
    private static final String V = "com/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity";

    public static void main(String[] args) throws Exception {
        Path root = args.length == 0 ? projectRoot() : Path.of(args[0]);
        Path nativeJar = root.resolve("instance/mods/superbwarfare-0.8.9.1-hotfix-mc1.20.1-993063bed-all.jar");
        try (JarFile jar = new JarFile(nativeJar.toFile())) {
            ClassNode vehicle = read(jar, V);
            MethodNode tick = method(vehicle, "m_6075_", "()V");
            int wreck = calls(tick, V, "setWreck", "(Z)V");
            int explosion = calls(tick, V, "vehicleExplosion",
                    "(Lcom/atsuishio/superbwarfare/data/vehicle/subdata/DestroyInfo;)V");
            equal(1, wreck, "native wreck transition");
            equal(1, explosion, "native aircraft vehicleExplosion");
            equal(1, calls(tick, V, "destroy", "()V"), "unique initial destruction entry point");
            if (indexOf(tick, V, "setSympatheticDetonated", "(Z)V")
                    > indexOf(tick, V, "vehicleExplosion",
                    "(Lcom/atsuishio/superbwarfare/data/vehicle/subdata/DestroyInfo;)V")) {
                throw new AssertionError("native detonation marker must precede vehicleExplosion");
            }
        }

        requireTrue(dev.frontier.aviation.destruction.AircraftTerminalDestruction
                .destroyOnDeath("frontier_aviation:su25"), "SU25 must be terminal");
        requireTrue(dev.frontier.aviation.destruction.AircraftTerminalDestruction
                .destroyOnDeath("frontier_aviation:f16"), "F16 must be terminal");
        requireTrue(dev.frontier.aviation.destruction.AircraftTerminalDestruction
                .destroyOnDeath("frontier_aviation:j10"), "J10 must be terminal");
        requireFalse(dev.frontier.aviation.destruction.AircraftTerminalDestruction
                .destroyOnDeath("superbwarfare:j_16"), "native aircraft must retain wreck");
        requireFalse(dev.frontier.aviation.destruction.AircraftTerminalDestruction
                .destroyOnDeath("unknown:aircraft"), "unknown aircraft must be ignored");

        ClassNode mixin = compiledMixin();
        MethodNode handler = method(mixin, "aviation$discardOwnedAircraft",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V");
        AnnotationNode inject = annotations(handler).stream()
                .filter(a -> a.desc.endsWith("/Inject;"))
                .findFirst().orElseThrow(() -> new AssertionError("missing @Inject"));
        Object method = value(inject, "method");
        if (!(method instanceof java.util.List<?> selectors)
                || selectors.size() != 1
                || !"m_6075_()V".equals(selectors.get(0))) {
            throw new AssertionError("hook must target VehicleEntity.m_6075_()V");
        }
        var points = (java.util.List<?>) value(inject, "at");
        equal(2, points.size(), "new death and loaded wreck hook coverage");
        AnnotationNode head = (AnnotationNode) points.get(0);
        AnnotationNode transition = (AnnotationNode) points.get(1);
        if (!"HEAD".equals(value(head, "value")) || !"INVOKE".equals(value(transition, "value"))
                || !("L" + V + ";destroy()V").equals(value(transition, "target")))
            throw new AssertionError("must cover loaded wrecks and the original destroy transition");
        if (!Boolean.TRUE.equals(value(inject, "cancellable"))) throw new AssertionError("native overkill path must be skipped");
        System.out.println("Aircraft terminal destruction native hook contract passed");
    }

    private static ClassNode compiledMixin() throws IOException {
        ClassNode node = new ClassNode();
        try (var in = AircraftTerminalDestructionContractTest.class.getClassLoader()
                .getResourceAsStream("dev/frontier/aviation/mixin/AircraftTerminalDestruction.class")) {
            if (in == null) throw new AssertionError("missing compiled AircraftTerminalDestruction");
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }

    private static java.util.List<AnnotationNode> annotations(MethodNode method) {
        java.util.List<AnnotationNode> result = new java.util.ArrayList<>();
        if (method.visibleAnnotations != null) result.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) result.addAll(method.invisibleAnnotations);
        return result;
    }

    private static Object value(AnnotationNode node, String key) {
        if (node.values != null) for (int i = 0; i < node.values.size(); i += 2)
            if (key.equals(node.values.get(i))) return node.values.get(i + 1);
        return null;
    }

    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (var insn : method.instructions)
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name) && descriptor.equals(call.desc)) count++;
        return count;
    }

    private static int indexOf(MethodNode method, String owner, String name, String descriptor) {
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name) && descriptor.equals(call.desc)) {
                return index;
            }
            index++;
        }
        throw new AssertionError("missing native call " + owner + "." + name + descriptor);
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream().filter(m -> name.equals(m.name) && descriptor.equals(m.desc))
                .findFirst().orElseThrow(() -> new AssertionError("missing " + owner.name + "." + name + descriptor));
    }

    private static ClassNode read(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name + ".class");
        if (entry == null) throw new AssertionError("missing pinned class " + name);
        ClassNode node = new ClassNode();
        try (var in = jar.getInputStream(entry)) { new ClassReader(in).accept(node, 0); }
        return node;
    }

    private static Path projectRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("modpack.lock.json"))) path = path.getParent();
        if (path == null) throw new AssertionError("run inside project or pass project root");
        return path;
    }

    private static void equal(int expected, int actual, String what) {
        if (expected != actual) throw new AssertionError(what + ": expected " + expected + ", got " + actual);
    }

    private static void requireTrue(boolean value, String what) {
        if (!value) throw new AssertionError(what);
    }

    private static void requireFalse(boolean value, String what) {
        if (value) throw new AssertionError(what);
    }
}
