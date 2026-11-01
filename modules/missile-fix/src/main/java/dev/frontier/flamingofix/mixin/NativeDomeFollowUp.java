package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.NativeDefenseEngagements;
import dev.sbw.deepstrike.vehicle.IronDomeEntity;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * DeepStrike's coordinator refuses a manual Tamir shot while any engagement record exists for
 * the target, and clears that record only when the target dies. Follow-up waves need native
 * automatic defense, which the accepted NativeCombatEvents turns off every tick, so one miss
 * left the target "engaged" forever; NativeCombatEvents.defend() then returned on that same
 * contact every scan and the whole battery went silent. A record now blocks only while one of
 * its interceptors is still flying for the same side; otherwise the shot proceeds and the
 * native code overwrites the record with the new engagement.
 */
@Mixin(targets = "dev.sbw.deepstrike.vehicle.IronDomeDefenseCoordinator", remap = false)
public abstract class NativeDomeFollowUp {
    @Redirect(method = "fireManual", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;containsKey(Ljava/lang/Object;)Z"), remap = false, require = 1)
    private static boolean flamingo$liveEngagement(Map<?, ?> engagements, Object target,
            ServerLevel level, IronDomeEntity battery, Entity targetEntity) {
        return NativeDefenseEngagements.blocksManualShot(level, battery, engagements.get(target));
    }
}
