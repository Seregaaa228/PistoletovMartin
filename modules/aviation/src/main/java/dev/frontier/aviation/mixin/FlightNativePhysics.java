package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleEngineUtils;
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import dev.frontier.aviation.control.nativebridge.FlightNativeHooks;
import dev.frontier.aviation.control.nativebridge.NativeFlightAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Intercepts control writers; all native forces, energy and movement execute. */
@Mixin(value = VehicleEngineUtils.class, remap = false)
public abstract class FlightNativePhysics {
    @Inject(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At("HEAD"), require = 1)
    private static void flight$aircraftEngineHEAD(VehicleEntity v, EngineInfo.Aircraft info, CallbackInfo ci) {
        FlightNativeHooks.beforePhysics(v);
    }
    @Inject(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At("TAIL"), require = 1)
    private static void flight$aircraftEngineTAIL(VehicleEntity v, EngineInfo.Aircraft info, CallbackInfo ci) {
        NativeFlightAdapter.INSTANCE.applyAerodynamicCorrection(v);
    }
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;m_146926_(F)V"), require = 5, expect = 5, allow = 5)
    private static void flight$aircraftEnginesetXRot(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowAngle(v)) v.setXRot(value);
    }
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;m_146922_(F)V"), require = 1, expect = 1, allow = 1)
    private static void flight$aircraftEnginesetYRot(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowAngle(v)) v.setYRot(value);
    }
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setZRot(F)V"), require = 4, expect = 4, allow = 4)
    private static void flight$aircraftEnginesetZRot(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowAngle(v)) v.setZRot(value);
    }
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setRoll(F)V"), require = 1, expect = 1, allow = 1)
    private static void flight$aircraftEnginesetRoll(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowAngle(v)) v.setRoll(value);
    }
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setEngineStart(Z)V"), require = 4, expect = 4, allow = 4)
    private static void flight$aircraftEnginesetEngineStart(VehicleEntity v, boolean value) {
        v.setEngineStart(NativeFlightAdapter.INSTANCE.startFlag(v, value, false));
    }
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setEngineStartOver(Z)V"), require = 3, expect = 3, allow = 3)
    private static void flight$aircraftEnginesetEngineStartOver(VehicleEntity v, boolean value) {
        v.setEngineStartOver(NativeFlightAdapter.INSTANCE.startFlag(v, value, true));
    }
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 0), require = 1)
    private static void flight$aircraftEnginePower0(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "emergency")) v.setPower(value);
    }
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 1), require = 1)
    private static void flight$aircraftEnginePower1(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "control")) v.setPower(value);
    }
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 2), require = 1)
    private static void flight$aircraftEnginePower2(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "control")) v.setPower(value);
    }
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 3), require = 1)
    private static void flight$aircraftEnginePower3(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "control")) v.setPower(value);
    }
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 4), require = 1)
    private static void flight$aircraftEnginePower4(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "control")) v.setPower(value);
    }
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 5), require = 1)
    private static void flight$aircraftEnginePower5(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "control")) v.setPower(value);
    }
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 6), require = 1)
    private static void flight$aircraftEnginePower6(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "control")) v.setPower(value);
    }
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 7), require = 1)
    private static void flight$aircraftEnginePower7(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "emergency")) v.setPower(value);
    }
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 8), require = 1)
    private static void flight$aircraftEnginePower8(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "ground")) v.setPower(value);
    }
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 9), require = 1)
    private static void flight$aircraftEnginePower9(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "damage")) v.setPower(value);
    }
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 10), require = 1)
    private static void flight$aircraftEnginePower10(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "damage")) v.setPower(value);
    }
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;getPower()F", ordinal = 16), require = 1)
    private static float flight$aircraftEnginePropulsion(VehicleEntity v) {
        return NativeFlightAdapter.INSTANCE.propulsion(v, v.getPower());
    }
    @Inject(method = "helicopterEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Helicopter;)V", at = @At("HEAD"), require = 1)
    private static void flight$helicopterEngineHEAD(VehicleEntity v, EngineInfo.Helicopter info, CallbackInfo ci) {
        FlightNativeHooks.beforePhysics(v);
    }
    @Redirect(method = "helicopterEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Helicopter;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;m_146926_(F)V"), require = 6, expect = 6, allow = 6)
    private static void flight$helicopterEnginesetXRot(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowAngle(v)) v.setXRot(value);
    }
    @Redirect(method = "helicopterEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Helicopter;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;m_146922_(F)V"), require = 2, expect = 2, allow = 2)
    private static void flight$helicopterEnginesetYRot(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowAngle(v)) v.setYRot(value);
        else if (v.getSubEngineDamaged() && v.getEngineInfo() instanceof EngineInfo.Helicopter info) {
            // This writer combines pilot yaw and tail damage. Preserve damage before force reads.
            float drift = net.minecraft.util.Mth.clamp(25 * v.getSynchedPropellerRot(), -10, 10);
            v.setYRot(v.getYRot() + info.getYawSpeed() * drift);
        }
    }
    @Redirect(method = "helicopterEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Helicopter;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setZRot(F)V"), require = 4, expect = 4, allow = 4)
    private static void flight$helicopterEnginesetZRot(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowAngle(v)) v.setZRot(value);
    }
    @Redirect(method = "helicopterEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Helicopter;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setRoll(F)V"), require = 4, expect = 4, allow = 4)
    private static void flight$helicopterEnginesetRoll(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowAngle(v)) v.setRoll(value);
    }
    @Redirect(method = "helicopterEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Helicopter;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setEngineStart(Z)V"), require = 4, expect = 4, allow = 4)
    private static void flight$helicopterEnginesetEngineStart(VehicleEntity v, boolean value) {
        v.setEngineStart(NativeFlightAdapter.INSTANCE.startFlag(v, value, false));
    }
    @Redirect(method = "helicopterEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Helicopter;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setEngineStartOver(Z)V"), require = 3, expect = 3, allow = 3)
    private static void flight$helicopterEnginesetEngineStartOver(VehicleEntity v, boolean value) {
        v.setEngineStartOver(NativeFlightAdapter.INSTANCE.startFlag(v, value, true));
    }
    @Redirect(method = "helicopterEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Helicopter;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 0), require = 1)
    private static void flight$helicopterEnginePower0(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "control")) v.setPower(value);
    }
    @Redirect(method = "helicopterEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Helicopter;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 1), require = 1)
    private static void flight$helicopterEnginePower1(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "emergency")) v.setPower(value);
    }
    @Redirect(method = "helicopterEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Helicopter;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 2), require = 1)
    private static void flight$helicopterEnginePower2(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "control")) v.setPower(value);
    }
    @Redirect(method = "helicopterEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Helicopter;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 3), require = 1)
    private static void flight$helicopterEnginePower3(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "control")) v.setPower(value);
    }
    @Redirect(method = "helicopterEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Helicopter;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 4), require = 1)
    private static void flight$helicopterEnginePower4(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "control")) v.setPower(value);
    }
    @Redirect(method = "helicopterEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Helicopter;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 5), require = 1)
    private static void flight$helicopterEnginePower5(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "control")) v.setPower(value);
    }
    @Redirect(method = "helicopterEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Helicopter;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 6), require = 1)
    private static void flight$helicopterEnginePower6(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "hover")) v.setPower(value);
    }
    @Redirect(method = "helicopterEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Helicopter;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 7), require = 1)
    private static void flight$helicopterEnginePower7(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "emergency")) v.setPower(value);
    }
    @Redirect(method = "helicopterEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Helicopter;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 8), require = 1)
    private static void flight$helicopterEnginePower8(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "damage")) v.setPower(value);
    }
    @Redirect(method = "helicopterEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Helicopter;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;getSynchedPropellerRot()F", ordinal = 9), require = 1)
    private static float flight$helicopterEnginePropulsion(VehicleEntity v) {
        return NativeFlightAdapter.INSTANCE.propulsion(v, v.getSynchedPropellerRot());
    }
    @Inject(method = "tomEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Tom6;)V", at = @At("HEAD"), require = 1)
    private static void flight$tomEngineHEAD(VehicleEntity v, EngineInfo.Tom6 info, CallbackInfo ci) {
        FlightNativeHooks.beforePhysics(v);
    }
    @Redirect(method = "tomEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Tom6;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;m_146926_(F)V", ordinal = 0), require = 1)
    private static void flight$tomEngineUnpilotedPitch(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowAngle(v)) v.setXRot(value);
    }
    @Redirect(method = "tomEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Tom6;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;m_146926_(F)V", ordinal = 1), require = 1)
    private static void flight$tomEnginePilotPitch(VehicleEntity v, float value) {
        if (!NativeFlightAdapter.INSTANCE.controls(v)) v.setXRot(value);
    }
    @Redirect(method = "tomEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Tom6;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;m_146922_(F)V"), require = 1, expect = 1, allow = 1)
    private static void flight$tomEnginesetYRot(VehicleEntity v, float value) {
        if (!NativeFlightAdapter.INSTANCE.controls(v)) v.setYRot(value);
    }
    @Redirect(method = "tomEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Tom6;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setZRot(F)V"), require = 3, expect = 3, allow = 3)
    private static void flight$tomEnginesetZRot(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowAngle(v)) v.setZRot(value);
    }
    @Redirect(method = "tomEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Tom6;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setEngineStart(Z)V"), require = 1, expect = 1, allow = 1)
    private static void flight$tomEnginesetEngineStart(VehicleEntity v, boolean value) {
        v.setEngineStart(NativeFlightAdapter.INSTANCE.startFlag(v, value, false));
    }
    @Redirect(method = "tomEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Tom6;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setEngineStartOver(Z)V"), require = 1, expect = 1, allow = 1)
    private static void flight$tomEnginesetEngineStartOver(VehicleEntity v, boolean value) {
        v.setEngineStartOver(NativeFlightAdapter.INSTANCE.startFlag(v, value, true));
    }
    @Redirect(method = "tomEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Tom6;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 0), require = 1)
    private static void flight$tomEnginePower0(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "emergency")) v.setPower(value);
    }
    @Redirect(method = "tomEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Tom6;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 1), require = 1)
    private static void flight$tomEnginePower1(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "control")) v.setPower(value);
    }
    @Redirect(method = "tomEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Tom6;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 2), require = 1)
    private static void flight$tomEnginePower2(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "control")) v.setPower(value);
    }
    @Redirect(method = "tomEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Tom6;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 3), require = 1)
    private static void flight$tomEnginePower3(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "control")) v.setPower(value);
    }
    @Redirect(method = "tomEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Tom6;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 4), require = 1)
    private static void flight$tomEnginePower4(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "control")) v.setPower(value);
    }
    @Redirect(method = "tomEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Tom6;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;setPower(F)V", ordinal = 5), require = 1)
    private static void flight$tomEnginePower5(VehicleEntity v, float value) {
        if (NativeFlightAdapter.INSTANCE.allowPower(v, "ground")) v.setPower(value);
    }
    @Redirect(method = "tomEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Tom6;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;getPower()F", ordinal = 9), require = 1)
    private static float flight$tomEnginePropulsion(VehicleEntity v) {
        return NativeFlightAdapter.INSTANCE.propulsion(v, v.getPower());
    }
    @Inject(method = "updateRotation(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void flight$skipPassiveAlignment(VehicleEntity v, CallbackInfo ci) {
        if (!NativeFlightAdapter.INSTANCE.allowAngle(v)) ci.cancel();
    }
    // Scope flight input suppression to the engine; passenger/weapon state stays available.
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;getMouseMoveSpeedX()F"), require = 5, expect = 5, allow = 5)
    private static float flight$aircraftEnginegetMouseMoveSpeedX(VehicleEntity v) {
        return NativeFlightAdapter.INSTANCE.controls(v) ? 0 : v.getMouseMoveSpeedX();
    }
    // Scope flight input suppression to the engine; passenger/weapon state stays available.
    @Redirect(method = "aircraftEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Aircraft;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;getMouseMoveSpeedY()F"), require = 4, expect = 4, allow = 4)
    private static float flight$aircraftEnginegetMouseMoveSpeedY(VehicleEntity v) {
        return NativeFlightAdapter.INSTANCE.controls(v) ? 0 : v.getMouseMoveSpeedY();
    }
    // Scope flight input suppression to the engine; passenger/weapon state stays available.
    @Redirect(method = "helicopterEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Helicopter;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;getMouseMoveSpeedX()F"), require = 2, expect = 2, allow = 2)
    private static float flight$helicopterEnginegetMouseMoveSpeedX(VehicleEntity v) {
        return NativeFlightAdapter.INSTANCE.controls(v) ? 0 : v.getMouseMoveSpeedX();
    }
    // Scope flight input suppression to the engine; passenger/weapon state stays available.
    @Redirect(method = "helicopterEngine(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo$Helicopter;)V", at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;getMouseMoveSpeedY()F"), require = 1, expect = 1, allow = 1)
    private static float flight$helicopterEnginegetMouseMoveSpeedY(VehicleEntity v) {
        return NativeFlightAdapter.INSTANCE.controls(v) ? 0 : v.getMouseMoveSpeedY();
    }
}
