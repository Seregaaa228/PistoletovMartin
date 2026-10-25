package dev.frontier.arcade.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import dev.frontier.arcade.combat.DroneImpactState;
import dev.frontier.arcade.combat.FpvCombat;
import dev.frontier.arcade.combat.DroneSweep;
import dev.frontier.arcade.control.ControlSessions;
import dev.frontier.arcade.geranium.GeraniumEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.lavafrai.uncomplicatedfpv.entity.FpvDroneEntity;

/** Pinned SBW methods; all other native drone types retain their own behavior. */
@Mixin(value = DroneEntity.class, remap = false)
public abstract class FpvServerImpact implements DroneImpactState {
    @Unique private boolean frontier$spent;
    @Override public boolean frontier$claimImpact() {
        if (frontier$spent) return false;
        frontier$spent = true; return true;
    }
    @Inject(method = "destroy", at = @At("HEAD"), cancellable = true, remap = false)
    private void frontier$destroy(CallbackInfo callback) {
        if ((Object)this instanceof FpvDroneEntity fpv) {
            if (!fpv.level().isClientSide) FpvCombat.detonate(fpv, fpv.getBoundingBox().getCenter(), null);
            callback.cancel();
        }
    }
    @Inject(method = "hitEntityCrash", at = @At("HEAD"), cancellable = true, remap = false)
    private void frontier$impact(Player controller, Entity target, CallbackInfo callback) {
        if ((Object)this instanceof FpvDroneEntity fpv && (fpv.isRemoved() || frontier$spent)) { callback.cancel(); return; }
        if ((Object)this instanceof FpvDroneEntity fpv && FpvCombat.armed(fpv)) {
            if (!fpv.level().isClientSide && fpv.getLastTickSpeed() > .05 && FpvCombat.impactTarget(fpv, target))
                FpvCombat.detonate(fpv, fpv.getBoundingBox().getCenter(), target);
            callback.cancel();
        }
    }
    @Inject(method = "m_6478_", at = @At("HEAD"), cancellable = true, remap = false)
    private void frontier$sweptMove(MoverType type, Vec3 movement, CallbackInfo callback) {
        if ((Object)this instanceof FpvDroneEntity && frontier$spent) { callback.cancel(); return; }
        if ((Object)this instanceof GeraniumEntity geranium && !geranium.level().isClientSide
                && !DroneSweep.loadedPath(geranium, geranium.position(), geranium.position().add(movement))) {
            geranium.setDeltaMovement(Vec3.ZERO); callback.cancel(); return;
        }
        if ((Object)this instanceof FpvDroneEntity fpv && FpvCombat.sweep(fpv, movement)) callback.cancel();
    }
    @Inject(method = "m_6075_", at = @At("HEAD"), cancellable = true, remap = false)
    private void frontier$manual(CallbackInfo callback) {
        if ((Object)this instanceof FpvDroneEntity fpv && !fpv.level().isClientSide) {
            ControlSessions.rememberNativeOwner(fpv);
            if (fpv.getFire() && FpvCombat.armed(fpv)) {
                FpvCombat.detonate(fpv, fpv.getBoundingBox().getCenter(), null); callback.cancel();
            }
        }
    }
}
