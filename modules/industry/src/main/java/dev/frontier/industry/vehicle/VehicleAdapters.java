package dev.frontier.industry.vehicle;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.items.IItemHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Optional vehicle classes are never linked from common code. Unknown versions fail closed. */
final class VehicleAdapters {
    interface Driver {
        String name();
        boolean aircraft();
        String unavailable(Entity craft);
        void steer(Entity craft, Vec3 target, double distance) throws ReflectiveOperationException;
        void stop(Entity craft) throws ReflectiveOperationException;
    }

    static Driver find(Entity craft) {
        if (craft instanceof Boat) return BOAT;
        for (Class<?> type = craft.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals("com.crescentine.trajanscore.basetank.BaseTankEntity")) {
                try { return new Tank(type); } catch (ReflectiveOperationException | LinkageError ignored) { return null; }
            }
            if (type.getName().equals("xyz.przemyk.simpleplanes.entities.PlaneEntity")) {
                // Helicopter's vertical input is different; do not claim to fly it with fixed-wing controls.
                if (craft.getClass().getName().contains("Helicopter")) return null;
                try { return new Plane(type); } catch (ReflectiveOperationException | LinkageError ignored) { return null; }
            }
        }
        return null;
    }

    static float yawTo(Entity craft, Vec3 target) {
        return (float) Math.toDegrees(Math.atan2(-(target.x - craft.getX()), target.z - craft.getZ()));
    }

    private static final Driver BOAT = new Driver() {
        public String name() { return "лодка / канонерская лодка"; }
        public boolean aircraft() { return false; }
        public String unavailable(Entity craft) { return craft.isInWater() ? null : "Лодка должна находиться в воде."; }
        public void steer(Entity craft, Vec3 target, double distance) {
            Boat boat = (Boat) craft;
            float error = Mth.wrapDegrees(yawTo(craft, target) - craft.getYRot());
            craft.setYRot(craft.getYRot() + Mth.clamp(error, -2.5F, 2.5F));
            double yaw = Math.toRadians(craft.getYRot());
            double thrust = Math.abs(error) > 65 ? 0 : Math.min(0.028, distance * 0.004);
            Vec3 velocity = craft.getDeltaMovement();
            double x = velocity.x + -Math.sin(yaw) * thrust;
            double z = velocity.z + Math.cos(yaw) * thrust;
            double speed = Math.sqrt(x * x + z * z);
            if (speed > 0.28) { x *= 0.28 / speed; z *= 0.28 / speed; }
            craft.setDeltaMovement(x, velocity.y, z);
            boat.setPaddleState(thrust > 0 || error > 2, thrust > 0 || error < -2);
            craft.hurtMarked = true;
        }
        public void stop(Entity craft) {
            ((Boat) craft).setPaddleState(false, false);
            Vec3 motion = craft.getDeltaMovement();
            craft.setDeltaMovement(0, motion.y, 0);
            craft.hurtMarked = true;
        }
    };

    private static final class Tank implements Driver {
        private final Method input, fuel, npcSupport;
        Tank(Class<?> base) throws ReflectiveOperationException {
            input = base.getMethod("setInput", boolean.class, boolean.class, boolean.class, boolean.class);
            fuel = base.getMethod("getFuelAmount");
            // Stock 1.6 has a Player cast in push() and never runs NPC engine physics on the server.
            // Only the explicitly marked arcade compatibility build is safe to drive with a mob.
            npcSupport = base.getMethod("frontierSupportsNpcDriver");
        }
        public String name() { return "Trajan tank (native NPC physics)"; }
        public boolean aircraft() { return false; }
        public String unavailable(Entity craft) {
            try {
                if (!Boolean.TRUE.equals(npcSupport.invoke(craft))) return "Установленная версия танка не поддерживает NPC-водителя.";
                return ((Number) fuel.invoke(craft)).intValue() > 0 ? null : "У танка нет топлива. Заправьте его обычным способом.";
            } catch (ReflectiveOperationException failure) { return "API танка несовместим с NPC-управлением."; }
        }
        public void steer(Entity craft, Vec3 target, double distance) throws ReflectiveOperationException {
            float error = Mth.wrapDegrees(yawTo(craft, target) - craft.getYRot());
            input.invoke(craft, error < -3, error > 3, Math.abs(error) < 50 && distance > 2.5, false);
        }
        public void stop(Entity craft) throws ReflectiveOperationException {
            input.invoke(craft, false, false, false, false);
            Vec3 motion = craft.getDeltaMovement();
            craft.setDeltaMovement(0, motion.y, 0);
            craft.hurtMarked = true;
        }
    }

    private static final class Plane implements Driver {
        private final Method throttle, pitch, yaw, powered;
        private final Field engine, blacklistedDimensions;
        Plane(Class<?> base) throws ReflectiveOperationException {
            throttle = base.getMethod("setThrottle", int.class);
            pitch = base.getMethod("setPitchUp", byte.class);
            yaw = base.getMethod("setYawRight", byte.class);
            powered = base.getMethod("isPowered");
            engine = base.getField("engineUpgrade");
            blacklistedDimensions = base.getField("BLACKLISTED_DIMENSIONS_TAG");
        }
        public String name() { return "SimplePlanes fixed wing (native throttle/pitch/yaw)"; }
        public boolean aircraft() { return true; }
        @SuppressWarnings("unchecked")
        public String unavailable(Entity craft) {
            try {
                TagKey<DimensionType> denied = (TagKey<DimensionType>) blacklistedDimensions.get(null);
                if (craft.level().dimensionTypeRegistration().is(denied)) return "SimplePlanes запрещает полёт в этом измерении.";
                if (Boolean.TRUE.equals(powered.invoke(craft))) return null;
                Object installed = engine.get(craft);
                // A cold furnace engine is not powered until a native tick receives throttle > 0.
                // Preflight must accept its real fuel without inventing burn time or consuming it twice.
                if (installed != null && installed.getClass().getName().equals("xyz.przemyk.simpleplanes.upgrades.engines.furnace.FurnaceEngineUpgrade")) {
                    Object stock = installed.getClass().getField("itemStackHandler").get(installed);
                    if (stock instanceof IItemHandler items && items.getSlots() > 0
                        && ForgeHooks.getBurnTime(items.getStackInSlot(0), null) > 0) return null;
                }
                return "Самолёт требует двигатель, пропеллер и топливо; соберите и заправьте его обычным способом.";
            }
            catch (ReflectiveOperationException failure) { return "API SimplePlanes несовместим с NPC-управлением."; }
        }
        public void steer(Entity craft, Vec3 target, double distance) throws ReflectiveOperationException {
            float headingError = Mth.wrapDegrees(yawTo(craft, target) - craft.getYRot());
            double horizontal = Math.max(1, new Vec3(target.x - craft.getX(), 0, target.z - craft.getZ()).length());
            float wantedPitch = Mth.clamp((float) Math.toDegrees(Math.atan2(target.y - craft.getY(), horizontal)), -12F, 18F);
            if (craft.onGround() && target.y > craft.getY() + 2) wantedPitch = 12;
            float pitchError = wantedPitch - craft.getXRot();
            // Feed the same input setters used by the mod's packets; engine consumes its own fuel.
            throttle.invoke(craft, 10);
            yaw.invoke(craft, (byte) (Math.abs(headingError) < 4 ? 0 : headingError > 0 ? 1 : -1));
            pitch.invoke(craft, (byte) (Math.abs(pitchError) < 2 ? 0 : pitchError > 0 ? 1 : -1));
        }
        public void stop(Entity craft) throws ReflectiveOperationException {
            throttle.invoke(craft, 0); pitch.invoke(craft, (byte) 0); yaw.invoke(craft, (byte) 0);
        }
    }
}
