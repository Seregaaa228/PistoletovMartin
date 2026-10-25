package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.impact.AircraftCollisions;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Native movement only checks one collision body; these jets have separate wing/nose OBBs. */
@Mixin(value = VehicleEntity.class, remap = false)
public abstract class AircraftBodyImpact {
    @ModifyArg(method = "m_6478_(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", index = 1,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;m_6478_(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"), remap = false)
    private Vec3 aviation$vanillaBodyImpact(MoverType mover, Vec3 requested) {
        return AircraftCollisions.movement((VehicleEntity) (Object) this, mover, requested);
    }

    @ModifyArg(method = "m_6478_(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", index = 1,
            at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;vMove(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"), remap = false)
    private Vec3 aviation$nativeObbImpact(MoverType mover, Vec3 requested) {
        return AircraftCollisions.movement((VehicleEntity) (Object) this, mover, requested);
    }
}
