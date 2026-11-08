package dev.frontier.arcade.airdefense;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import java.lang.reflect.Method;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * A warhead burst removes a set fraction of a target's maximum health. Every aircraft carries its
 * own damage modifiers (a jet multiplies explosions, a truck divides them), so a fixed number
 * would kill one type and tickle another; the fraction is what the balance tables mean.
 */
public final class SamDamage {
    private static final String GUIDED_MISSILE = "dev.sbw.deepstrike.game.GuidedMissileEntity";

    private SamDamage() { }

    public static void apply(Entity target, double fraction, DamageSource source) {
        if (!(fraction > 0) || !target.isAlive()) return;
        if (target instanceof VehicleEntity vehicle) {
            float wanted = (float) Math.min(1e6, fraction * vehicle.getMaxHealth());
            float before = vehicle.getHealth();
            // The native path keeps kill credit, hit sounds and wreck handling.
            target.invulnerableTime = 0;
            vehicle.hurt(source, wanted);
            if (vehicle.isAlive() && !vehicle.isWreck() && before - vehicle.getHealth() < wanted * 0.95f)
                vehicle.setHealth(Math.max(0, before - wanted));
            return;
        }
        if (target.getClass().getName().equals(GUIDED_MISSILE)) {
            // DeepStrike's own interception: the missile loses thrust and falls.
            try {
                Method intercept = target.getClass().getMethod("intercept", int.class);
                intercept.invoke(target, 0);
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                target.discard();
            }
            return;
        }
        target.invulnerableTime = 0;
        if (target instanceof LivingEntity living) {
            living.hurt(source, (float) Math.max(6, fraction * living.getMaxHealth()));
            return;
        }
        // Small foreign drones (UF FPV/Mavic) are plain entities: a burst destroys them.
        if (!target.hurt(source, 1000) && target.isAlive()) target.discard();
    }
}
