package dev.frontier.aviation.mixin;

import dev.frontier.aviation.policy.HumanPlayers;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Remove the human lock-on IFF filter without changing radar identity or automated seeking.
 *
 * <p>Native IFF is also what keeps a seeker off its own vehicle: SBW's vehicle seeker
 * is built with excludeSelf=false and relies on notFriendly to drop the aircraft the
 * pilot sits in. Its third-person cone starts behind the jet, so without that filter
 * the closest-angle candidate is the pilot's own aircraft. Teams stay lockable; the
 * seeker's own vehicle, its passengers and the seeker itself never are.</p>
 */
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.tools.SeekTool$Builder", remap = false)
public abstract class HumanWeaponSeeking {
    @Shadow @Final private Entity entity;
    @Shadow @Final private List<Predicate<Entity>> filters;

    @Inject(method = "notFriendly", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void aviation$humanSeeking(CallbackInfoReturnable<Object> result) {
        if (!HumanPlayers.seeking(entity)) return;
        Entity seeker = entity;
        filters.add(candidate -> candidate != seeker && candidate.getRootVehicle() != seeker.getRootVehicle());
        result.setReturnValue(this);
    }
}
