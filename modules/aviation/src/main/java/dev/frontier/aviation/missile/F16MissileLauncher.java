package dev.frontier.aviation.missile;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Server-only entry point installed on the native aircraft by the F16 mixin. */
public interface F16MissileLauncher {
    /**
     * Submits native deferred missile shots.
     *
     * @return true when the request passed the launcher checks and was submitted
     *         to the native deferred shooter
     */
    default boolean aviation$fireMissiles(LivingEntity pilot, int count, Vec3 target) {
        return aviation$fireMissiles(pilot, count, target, null);
    }

    boolean aviation$fireMissiles(LivingEntity pilot, int count, Vec3 target, UUID trackedEntity);
}
