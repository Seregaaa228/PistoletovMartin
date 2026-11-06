package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.control.nativebridge.FlightNativeHooks;
import dev.frontier.aviation.control.nativebridge.NativeFlightAdapter;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Capture the final native attitude after terrain and collision updates. */
@Mixin(value = VehicleEntity.class, remap = false)
public abstract class FlightNativeEndTick {
    @Inject(method = "m_6075_()V", at = @At("TAIL"), require = 1)
    private void flight$endTick(CallbackInfo ci) {
        FlightNativeHooks.afterPhysics((VehicleEntity) (Object) this);
    }

    /**
     * Our jets have no collision OBB, so native terrain compat would tilt them wheel by wheel
     * at 1% per tick toward up to 45 degrees over every block edge. On the wheels they sit
     * on their gear stance instead (both sides, piloted or parked); in the air or in water
     * the native pass still runs.
     */
    @Inject(method = "terrainCompact(Ljava/util/List;)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void flight$gearStance(List<Vec3> positions, CallbackInfo ci) {
        if (NativeFlightAdapter.settleOnGear((VehicleEntity) (Object) this)) ci.cancel();
    }
}
