package dev.frontier.aviation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

/** Verifies pinned native constants and mixin selectors; does not exercise runtime application. */
public final class NativeFlightHudTextContractTest {
    private static final String HUD = "com/atsuishio/superbwarfare/client/overlay/weapon/AircraftHud";
    private static final String DESC = "(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lnet/minecraft/world/entity/player/Player;Lnet/minecraftforge/client/gui/overlay/ForgeGui;Lnet/minecraft/client/gui/GuiGraphics;FII)V";

    public static void main(String[] args) throws Exception {
        Path root = args.length == 0 ? Path.of("").toAbsolutePath() : Path.of(args[0]);
        while (root != null && !Files.exists(root.resolve("modpack.lock.json"))) root = root.getParent();
        if (root == null) throw new AssertionError("Project root not found");
        ClassNode nativeHud = new ClassNode();
        try (JarFile jar = new JarFile(root.resolve("instance/mods/superbwarfare-0.8.9.1-hotfix-mc1.20.1-993063bed-all.jar").toFile());
             var input = jar.getInputStream(jar.getJarEntry(HUD + ".class"))) {
            new ClassReader(input).accept(nativeHud, 0);
        }
        MethodNode render = nativeHud.methods.stream().filter(m -> m.name.equals("render") && m.desc.equals(DESC))
            .findFirst().orElseThrow(() -> new AssertionError("Missing native render"));
        ClassNode mixin = new ClassNode();
        try (var input = NativeFlightHudTextContractTest.class.getClassLoader().getResourceAsStream(
            "dev/frontier/aviation/mixin/FlightNativeHudText.class")) {
            if (input == null) throw new AssertionError("Missing compiled HUD text mixin");
            new ClassReader(input).accept(mixin, 0);
        }
        verify(render, mixin, "NO POWER!", "aviation$noPower", "hud.frontier_aviation.no_power");
        verify(render, mixin, "LOW POWER", "aviation$lowPower", "hud.frontier_aviation.low_power");
        verify(render, mixin, "TGT", "aviation$target", "hud.frontier_aviation.target");
        System.out.println("Native flight HUD text anchors passed (not runtime acceptance)");
    }

    private static void verify(MethodNode render, ClassNode mixin, String text, String name, String key) {
        int matches = 0;
        for (AbstractInsnNode insn : render.instructions) {
            if (!(insn instanceof LdcInsnNode ldc) || !text.equals(ldc.cst)) continue;
            matches++;
            AbstractInsnNode next = insn.getNext();
            while (next != null && next.getOpcode() < 0) next = next.getNext();
            if (!(next instanceof MethodInsnNode call) || !call.owner.equals("net/minecraft/network/chat/Component")
                || !call.name.equals("m_237113_") || !call.desc.equals("(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;")) {
                throw new AssertionError(text + " is no longer passed to Component.literal");
            }
        }
        if (matches != 1) throw new AssertionError(text + " expected once, found " + matches);
        MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError("Missing handler " + name));
        List<AnnotationNode> annotations = new ArrayList<>();
        if (handler.visibleAnnotations != null) annotations.addAll(handler.visibleAnnotations);
        if (handler.invisibleAnnotations != null) annotations.addAll(handler.invisibleAnnotations);
        AnnotationNode modify = annotations.stream().filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyConstant;"))
            .findFirst().orElseThrow(() -> new AssertionError("Missing ModifyConstant " + name));
        if (!List.of("render" + DESC).equals(value(modify, "method")) || !Integer.valueOf(1).equals(value(modify, "require"))
            || !Integer.valueOf(1).equals(value(modify, "allow"))) throw new AssertionError("Unexpected selector " + name);
        List<?> constants = (List<?>) value(modify, "constant");
        if (constants == null || constants.size() != 1 || !text.equals(value((AnnotationNode) constants.get(0), "stringValue")))
            throw new AssertionError("Unexpected constant " + name);
        boolean foundKey = false;
        for (AbstractInsnNode insn : handler.instructions) if (insn instanceof LdcInsnNode ldc && key.equals(ldc.cst)) foundKey = true;
        if (!foundKey) throw new AssertionError("Missing translation key " + key);
    }

    private static Object value(AnnotationNode node, String name) {
        if (node.values != null) for (int i = 0; i < node.values.size(); i += 2) {
            if (name.equals(node.values.get(i))) return node.values.get(i + 1);
        }
        return null;
    }
}
