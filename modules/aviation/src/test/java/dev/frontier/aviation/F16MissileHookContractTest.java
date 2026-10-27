package dev.frontier.aviation;

import java.nio.file.Path;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Checks the exact deferred-shot lambda in the pinned SBW artifact, not runtime mixin application. */
public final class F16MissileHookContractTest {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of("instance/mods/superbwarfare-0.8.9.1-hotfix-mc1.20.1-993063bed-all.jar");
        String owner = "com/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity";
        String desc = "(L" + owner + ";Lnet/minecraft/world/entity/LivingEntity;Ljava/lang/String;Ljava/util/UUID;Lnet/minecraft/world/phys/Vec3;Lcom/atsuishio/superbwarfare/data/gun/GunData;)V";
        try (JarFile file = new JarFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            try (var input = file.getInputStream(file.getJarEntry(owner + ".class"))) {
                new ClassReader(input).accept(node, 0);
            }
            MethodNode lambda = node.methods.stream().filter(m -> m.name.equals("vehicleShoot$lambda$28$lambda$27") && m.desc.equals(desc)).findFirst().orElseThrow();
            if ((lambda.access & Opcodes.ACC_STATIC) == 0) throw new AssertionError("deferred lambda is not static");
            boolean canShoot = false, shoot = false;
            for (var insn : lambda.instructions) if (insn instanceof MethodInsnNode call) {
                if (call.owner.equals("com/atsuishio/superbwarfare/data/gun/GunData") && call.name.equals("canShoot")) canShoot = true;
                if (call.owner.equals("com/atsuishio/superbwarfare/data/gun/GunData") && call.name.equals("shoot")) shoot = true;
            }
            if (!canShoot || !shoot) throw new AssertionError("deferred missile path changed");
        }
        System.out.println("Pinned missile deferred-shot bytecode contract passed; runtime mixin application remains unverified");
    }
}
