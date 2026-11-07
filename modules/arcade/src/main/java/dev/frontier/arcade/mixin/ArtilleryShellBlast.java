package dev.frontier.arcade.mixin;

import com.atsuishio.superbwarfare.entity.projectile.CannonShellEntity;
import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile;
import dev.frontier.arcade.artillery.ArtilleryBlast;
import dev.frontier.arcade.artillery.ArtilleryShells;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A self-propelled-gun shell's burst gets ArtilleryBlast's fireball and travelling boom over SBW's explosion. */
@Mixin(value = FastThrowableProjectile.class, remap = false)
public abstract class ArtilleryShellBlast {
    @Inject(method = "causeExplode(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), remap = false)
    private void frontier$artilleryBurst(Vec3 at, CallbackInfo ci) {
        var self = (FastThrowableProjectile) (Object) this;
        if (self instanceof CannonShellEntity shell && shell.level() instanceof ServerLevel level && ArtilleryShells.tagged(shell))
            ArtilleryBlast.burst(level, at, shell.getExplosionRadius());
    }
}
