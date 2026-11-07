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
            ClassNode wire = new ClassNode();
            String wireOwner = "com/atsuishio/superbwarfare/entity/projectile/WireGuideMissileEntity";
            try (var input = file.getInputStream(file.getJarEntry(wireOwner + ".class"))) {
                new ClassReader(input).accept(wire, 0);
            }
            var wireTick = wire.methods.stream().filter(method -> method.name.equals("m_8119_") && method.desc.equals("()V")).findFirst().orElseThrow();
            long trailCalls = java.util.stream.StreamSupport.stream(wireTick.instructions.spliterator(), false)
                .filter(insn -> insn instanceof MethodInsnNode call && call.owner.equals(wireOwner)
                    && call.name.equals("mediumTrail") && call.desc.equals("()V")).count();
            if (trailCalls != 1) throw new AssertionError("wire guidance hook anchor changed");
            // HumanWeaponSeeking shadows these fields and replaces notFriendly with an
            // own-vehicle filter: the vehicle seeker relies on notFriendly to skip the
            // pilot's own aircraft (it is built without excludeSelf).
            String builder = "com/atsuishio/superbwarfare/tools/SeekTool$Builder";
            ClassNode seek = new ClassNode();
            try (var input = file.getInputStream(file.getJarEntry(builder + ".class"))) {
                new ClassReader(input).accept(seek, 0);
            }
            if (seek.fields.stream().noneMatch(f -> f.name.equals("entity") && f.desc.equals("Lnet/minecraft/world/entity/Entity;"))
                    || seek.fields.stream().noneMatch(f -> f.name.equals("filters") && f.desc.equals("Ljava/util/List;"))
                    || seek.methods.stream().noneMatch(m -> m.name.equals("notFriendly") && m.desc.equals("()L" + builder + ";")))
                throw new AssertionError("SeekTool.Builder shadow targets changed");
            ClassNode client = new ClassNode();
            try (var input = file.getInputStream(file.getJarEntry("com/atsuishio/superbwarfare/event/ClientEventHandler.class"))) {
                new ClassReader(input).accept(client, 0);
            }
            boolean vehicleSeeker = client.methods.stream().anyMatch(method -> {
                boolean noVehicle = false, notFriendly = false;
                for (var insn : method.instructions) if (insn instanceof MethodInsnNode call && call.owner.equals(builder)) {
                    noVehicle |= call.name.equals("noVehicle");
                    notFriendly |= call.name.equals("notFriendly");
                }
                return noVehicle && notFriendly;
            });
            if (!vehicleSeeker) throw new AssertionError("native vehicle seeker no longer filters through notFriendly");
            // F16MissileTriggerClient fires at the seeker's current candidate when not locked.
            if (client.fields.stream().noneMatch(f -> f.name.equals("nearestEntityVehicle") && f.desc.equals("Lnet/minecraft/world/entity/Entity;")))
                throw new AssertionError("SBW seeker candidate field changed");
            // AircraftMissileFuse: injects at the tail of MissileProjectile's tick and bursts
            // through SBW's own public hit path.
            ClassNode missile = new ClassNode(), fast = new ClassNode();
            try (var input = file.getInputStream(file.getJarEntry("com/atsuishio/superbwarfare/entity/projectile/MissileProjectile.class"))) {
                new ClassReader(input).accept(missile, 0);
            }
            try (var input = file.getInputStream(file.getJarEntry("com/atsuishio/superbwarfare/entity/projectile/FastThrowableProjectile.class"))) {
                new ClassReader(input).accept(fast, 0);
            }
            if (missile.methods.stream().noneMatch(m -> m.name.equals("m_8119_") && m.desc.equals("()V")))
                throw new AssertionError("MissileProjectile tick anchor changed");
            for (String signature : new String[]{"performDamage(Lnet/minecraft/world/entity/Entity;FZ)V",
                    "afterHitEntity(Lnet/minecraft/world/phys/EntityHitResult;)V"}) {
                String name = signature.substring(0, signature.indexOf('('));
                String descriptor = signature.substring(signature.indexOf('('));
                if (fast.methods.stream().noneMatch(m -> m.name.equals(name) && m.desc.equals(descriptor) && (m.access & Opcodes.ACC_PUBLIC) != 0))
                    throw new AssertionError("SBW projectile hit path changed: " + signature);
            }
        }
        // AircraftSeekKey: DeepStrike's console key opens the aircraft tablet through exactly one call.
        try (JarFile dsm = new JarFile(Path.of("instance/mods/dsm-0.9.0.jar").toFile())) {
            ClassNode screen = new ClassNode();
            try (var input = dsm.getInputStream(dsm.getJarEntry("dev/sbw/deepstrike/client/PatriotDefenseScreen.class"))) {
                new ClassReader(input).accept(screen, 0);
            }
            MethodNode tick = screen.methods.stream().filter(m -> m.name.equals("clientTick")).findFirst().orElseThrow();
            long opens = java.util.stream.StreamSupport.stream(tick.instructions.spliterator(), false)
                .filter(insn -> insn instanceof MethodInsnNode call && call.owner.equals("dev/sbw/deepstrike/client/Su24mScreen")
                    && call.name.equals("open") && call.desc.equals("(I)V")).count();
            if (opens != 1) throw new AssertionError("DeepStrike console key no longer opens the aircraft tablet through Su24mScreen.open");
        }
        // Tablet racks (GlideBombPylon, F16StormStoreLimit): an owned jet's launch point comes
        // from pylonPosition, which DeepStrike's launch must still call.
        try (JarFile dsm = new JarFile(Path.of("instance/mods/dsm-0.9.0.jar").toFile())) {
            String su24 = "dev/sbw/deepstrike/vehicle/Su24mEntity";
            ClassNode node = new ClassNode();
            try (var input = dsm.getInputStream(dsm.getJarEntry(su24 + ".class"))) {
                new ClassReader(input).accept(node, 0);
            }
            for (String signature : new String[]{"pylonPosition(I)Lnet/minecraft/world/phys/Vec3;",
                    "loadStore(Lnet/minecraft/server/level/ServerPlayer;I)Z", "loaded(I)Z",
                    "launch(Lnet/minecraft/server/level/ServerLevel;IL" + su24 + "$Order;)V"}) {
                String name = signature.substring(0, signature.indexOf('('));
                String descriptor = signature.substring(signature.indexOf('('));
                if (node.methods.stream().noneMatch(m -> m.name.equals(name) && m.desc.equals(descriptor)))
                    throw new AssertionError("DeepStrike rack method changed: " + signature);
            }
            MethodNode launch = node.methods.stream().filter(m -> m.name.equals("launch")).findFirst().orElseThrow();
            boolean calls = false;
            for (var insn : launch.instructions) if (insn instanceof MethodInsnNode call
                    && call.owner.equals(su24) && call.name.equals("pylonPosition")) calls = true;
            if (!calls) throw new AssertionError("DeepStrike launch no longer asks pylonPosition");
        }
        System.out.println("Pinned missile deferred-shot bytecode contract passed; runtime mixin application remains unverified");
    }
}
