package dev.frontier.aviation.mixin;

import dev.frontier.aviation.policy.HumanPlayers;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remove the human lock-on IFF filter without changing radar identity or automated seeking. */
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.tools.SeekTool$Builder", remap = false)
public abstract class HumanWeaponSeeking {
    @Shadow @Final private Entity entity;

    @Inject(method = "notFriendly", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void aviation$humanSeeking(CallbackInfoReturnable<Object> result) {
        if (HumanPlayers.seeking(entity)) result.setReturnValue(this);
    }
}
