package dev.frontier.aviation;

import java.nio.file.Path;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.LdcInsnNode;

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
            long returns = java.util.stream.StreamSupport.stream(lambda.instructions.spliterator(), false)
                .filter(insn -> insn.getOpcode() == Opcodes.RETURN).count();
            if (returns != 2) throw new AssertionError("deferred scope cleanup return sites changed");
            ClassNode agm = new ClassNode();
            try (var input = file.getInputStream(file.getJarEntry("com/atsuishio/superbwarfare/entity/projectile/Agm65Entity.class"))) {
                new ClassReader(input).accept(agm, 0);
            }
            var tick = agm.methods.stream().filter(method -> method.name.equals("m_8119_") && method.desc.equals("()V")).findFirst().orElseThrow();
            int loftConstants = 0;
            for (var insn : tick.instructions) if (insn instanceof LdcInsnNode constant && Double.valueOf(.4).equals(constant.cst)) loftConstants++;
            if (loftConstants != 2) throw new AssertionError("native AGM entity/point loft branches changed");
        }
        System.out.println("Pinned missile deferred-shot bytecode contract passed; runtime mixin application remains unverified");
    }
}
