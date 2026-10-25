package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.projectile.SmallCannonShellEntity;
import dev.frontier.aviation.AircraftCannon;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SmallCannonShellEntity.class, remap = false)
public abstract class ControlledAircraftCannon {
    @Inject(method = "afterHitBlock", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void aviation$materialImpact(BlockHitResult hit, CallbackInfo callback) {
        if (!AircraftCannon.contact((SmallCannonShellEntity)(Object)this, hit)) callback.cancel();
    }
}
