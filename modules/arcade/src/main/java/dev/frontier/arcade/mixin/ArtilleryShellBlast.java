package dev.frontier.arcade.mixin;

import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile;
import dev.frontier.arcade.artillery.ArtilleryBlast;
import dev.frontier.arcade.artillery.ArtilleryPenetration;
import dev.frontier.arcade.artillery.ArtilleryShells;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * A self-propelled-gun shell's or launcher rocket's burst: ArtilleryBlast's fireball and travelling boom where it
 * struck, and SBW's explosion where it ends up after going through roof and floors (ArtilleryPenetration).
 */
@Mixin(value = FastThrowableProjectile.class, remap = false)
public abstract class ArtilleryShellBlast {
    @ModifyVariable(method = "causeExplode(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), argsOnly = true, remap = false)
    private Vec3 frontier$artilleryBurst(Vec3 at) {
        var self = (FastThrowableProjectile) (Object) this;
        if (!ArtilleryShells.round(self) || !(self.level() instanceof ServerLevel level) || !ArtilleryShells.tagged(self)) return at;
        ArtilleryBlast.burst(level, at, self.getExplosionRadius());
        return ArtilleryPenetration.burst(level, self, at);
    }
}
