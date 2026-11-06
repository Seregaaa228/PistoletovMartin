package dev.frontier.aviation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Checks the exact native hint call sites, not runtime Mixin application. */
public final class NativeFlightHudHooksContractTest {
    private static final String HUD = "com/atsuishio/superbwarfare/client/overlay/VehicleHudOverlay";
    private static final String DESC = "(Lnet/minecraft/client/gui/GuiGraphics;Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;IIFI)V";
    private static final String KEY_DESC = "()Lcom/mojang/blaze3d/platform/InputConstants$Key;";

    public static void main(String[] args) throws Exception {
        Path root = args.length == 0 ? Path.of("").toAbsolutePath() : Path.of(args[0]);
        while (root != null && !Files.exists(root.resolve("modpack.lock.json"))) root = root.getParent();
        if (root == null) throw new AssertionError("Project root not found");
        try (JarFile jar = new JarFile(root.resolve("instance/mods/superbwarfare-0.8.9.1-hotfix-mc1.20.1-993063bed-all.jar").toFile());
             var input = jar.getInputStream(jar.getJarEntry(HUD + ".class"))) {
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            verify(node, "renderGearInfo");
            verify(node, "renderHoverInfo");
        }
        try (var input = NativeFlightHudHooksContractTest.class.getClassLoader().getResourceAsStream(
            "dev/frontier/aviation/mixin/FlightNativeHudKeys.class")) {
            if (input == null) throw new AssertionError("Missing compiled HUD mixin");
            ClassNode mixin = new ClassNode();
            new ClassReader(input).accept(mixin, 0);
            verifyRedirect(mixin, "aviation$gearHint", "renderGearInfo");
            verifyRedirect(mixin, "aviation$hoverHint", "renderHoverInfo");
        }
        System.out.println("Native flight HUD key anchors passed (not runtime acceptance)");
    }

    private static void verifyRedirect(ClassNode node, String handlerName, String targetName) {
        MethodNode handler = node.methods.stream().filter(m -> m.name.equals(handlerName)).findFirst()
            .orElseThrow(() -> new AssertionError("Missing mixin handler " + handlerName));
        java.util.List<AnnotationNode> annotations = new java.util.ArrayList<>();
        if (handler.visibleAnnotations != null) annotations.addAll(handler.visibleAnnotations);
        if (handler.invisibleAnnotations != null) annotations.addAll(handler.invisibleAnnotations);
        AnnotationNode redirect = annotations.stream()
            .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;"))
            .findFirst().orElseThrow(() -> new AssertionError("Missing redirect " + handlerName));
        if (!java.util.List.of(targetName + DESC).equals(value(redirect, "method"))
            || !Integer.valueOf(2).equals(value(redirect, "require"))
            || !Integer.valueOf(2).equals(value(redirect, "allow"))) {
            throw new AssertionError("Unexpected redirect selector/count " + handlerName);
        }
        AnnotationNode at = (AnnotationNode) value(redirect, "at");
        if (!"INVOKE".equals(value(at, "value")) || !("Lnet/minecraft/client/KeyMapping;getKey" + KEY_DESC).equals(value(at, "target"))) {
            throw new AssertionError("Unexpected redirect anchor " + handlerName);
        }
    }

    private static Object value(AnnotationNode node, String name) {
        if (node.values != null) for (int i = 0; i < node.values.size(); i += 2) {
            if (name.equals(node.values.get(i))) return node.values.get(i + 1);
        }
        return null;
    }

    private static void verify(ClassNode node, String name) {
        MethodNode method = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(DESC)).findFirst()
            .orElseThrow(() -> new AssertionError("Missing " + name + DESC));
        int matches = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode call) || !call.owner.equals("net/minecraft/client/KeyMapping")
                || !call.name.equals("getKey") || !call.desc.equals(KEY_DESC)) continue;
            AbstractInsnNode previous = call.getPrevious();
            while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
            if (!(previous instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC
                || !field.owner.equals("com/atsuishio/superbwarfare/init/ModKeyMappings")
                || !field.name.equals("MOVE_SPACE") || !field.desc.equals("Lnet/minecraft/client/KeyMapping;")) {
                throw new AssertionError(name + " key call is no longer the native MOVE_SPACE hint");
            }
            matches++;
        }
        if (matches != 2) throw new AssertionError(name + " expected 2 native hint calls, found " + matches);
    }
}
