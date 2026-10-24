package dev.frontier.flamingofix.mixin;
import dev.frontier.flamingofix.LauncherPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Scope only the launcher crew gate; the separate interceptor and defense policies stay intact. */
@Mixin(targets="dev.frontier.industry.combat.NativeCombatEvents",remap=false)
public abstract class NativeLauncherPolicy {
    @Redirect(method="join(Lnet/minecraftforge/event/entity/EntityJoinLevelEvent;)V",
            at=@At(value="INVOKE",target="Ldev/frontier/industry/combat/ReadyWeapons;nativeLauncher(Ljava/lang/Object;)Z",remap=false),
            remap=false,require=1)
    private static boolean frontierFlamingo$managedLauncher(Object candidate) {
        return LauncherPolicy.requiresFrontierCrew(candidate);
    }
}
