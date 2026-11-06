package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.damagesource.DamageSource;

/** Public bridge for the hurt probe mixin (the scenarios themselves stay package-private). */
public final class TesterFixHooks {
    private TesterFixHooks() {}

    public static void hurtHead(VehicleEntity vehicle, DamageSource source, float amount) {
        TesterFix.vehicleHurtHead(vehicle, source, amount);
    }

    public static void hurtReturn(VehicleEntity vehicle, DamageSource source, float amount, boolean accepted) {
        TesterFix.vehicleHurtReturn(vehicle, source, amount, accepted);
    }
}
