package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.NativeDefenseEngagements;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runtime patch of the accepted preserved NativeCombatEvents.class (AGENTS.md: the artifact
 * itself is never edited or replaced; this intercepts it the same way DeepStrike is patched).
 *
 * engaged(): the shipped check counted any live PatriotInterceptorEntity aimed at the target,
 * whoever fired it, so an enemy could hand-fire one cheap shot at its own aircraft or missile
 * and make this side's automatic Patriot/Iron Dome hold fire. Only interceptors of this
 * station or of a same-side station count now (existing CombatOwnership.sameTeamCached).
 *
 * tick(): the shipped loop copied every loaded entity into a new list every tick and ran
 * class-name comparisons on each, with or without any air defense present. It is fed instead
 * from a roster of the only four DeepStrike types the loop acts on, maintained from the same
 * join event the class already listens to; the roster also serves the engagement check.
 */
@Pseudo
@Mixin(targets = "dev.frontier.industry.combat.NativeCombatEvents", remap = false)
public abstract class NativeDefenseEngagement {
    @Inject(method = "join", at = @At("HEAD"), remap = false, require = 1)
    private static void flamingo$roster(EntityJoinLevelEvent event, CallbackInfo info) {
        if (event.getLevel() instanceof ServerLevel level) NativeDefenseEngagements.track(event.getEntity(), level);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Ljava/lang/Iterable;forEach(Ljava/util/function/Consumer;)V"), remap = false, require = 1)
    private static void flamingo$rosterOnly(Iterable<?> allEntities, Consumer<? super Entity> sink,
            TickEvent.LevelTickEvent event) {
        if (event.level instanceof ServerLevel level) NativeDefenseEngagements.feed(level, sink);
    }

    @Redirect(method = "defend", at = @At(value = "INVOKE", target =
            "Ldev/frontier/industry/combat/NativeCombatEvents;engaged(Lnet/minecraft/server/level/ServerLevel;Ljava/util/UUID;)Z"),
            remap = false, require = 1)
    private static boolean flamingo$sideEngaged(ServerLevel calledLevel, UUID target, ServerLevel level, Entity station) {
        return NativeDefenseEngagements.engagedBySide(calledLevel, target, station);
    }
}
