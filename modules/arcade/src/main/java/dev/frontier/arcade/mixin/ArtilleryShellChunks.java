package dev.frontier.arcade.mixin;

import com.atsuishio.superbwarfare.entity.projectile.CannonShellEntity;
import dev.frontier.arcade.artillery.ArtilleryShells;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Self-propelled-gun shells fly far without SBW's path chunk loading; ArtilleryShells carries them instead. */
@Mixin(value = CannonShellEntity.class, remap = false)
public abstract class ArtilleryShellChunks {
    @Inject(method = "forceLoadChunk", at = @At("HEAD"), cancellable = true, remap = false)
    private void frontier$noPathChunks(CallbackInfoReturnable<Boolean> cir) {
        if (ArtilleryShells.tagged((CannonShellEntity) (Object) this)) cir.setReturnValue(false);
    }
}
