package dev.frontier.nations.mixin.common;

import com.diego.recruitsvillages.config.RecruitsVillagesConfig;
import com.diego.recruitsvillages.data.FactionData;
import com.diego.recruitsvillages.data.LivingVillagesSavedData;
import com.talhanation.recruits.ClaimEvents;
import dev.frontier.nations.compat.RelationsTickCache;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** One lookup per country and diplomacy pass (see {@link RelationsTickCache}). */
@Mixin(targets = "com.diego.recruitsvillages.sim.Relations", remap = false)
public abstract class RelationsTickCacheMixin {
    @org.spongepowered.asm.mixin.Unique private static final String SAVED = "Lcom/diego/recruitsvillages/data/LivingVillagesSavedData;";
    @org.spongepowered.asm.mixin.Unique private static final String FACTION = "Lcom/diego/recruitsvillages/data/FactionData;";

    @Inject(method = "tick(Lnet/minecraft/server/level/ServerLevel;)V", at = @At("HEAD"), remap = false, require = 1)
    private static void frontier$beginPass(ServerLevel level, CallbackInfo ci) {
        RelationsTickCache.begin();
    }

    @Inject(method = "tick(Lnet/minecraft/server/level/ServerLevel;)V", at = @At("RETURN"), remap = false, require = 1)
    private static void frontier$endPass(ServerLevel level, CallbackInfo ci) {
        RelationsTickCache.end();
    }

    @Redirect(method = "*", remap = false, require = 1,
            at = @At(value = "INVOKE", remap = false,
                target = SAVED + "getFactionVillages(" + FACTION + ")Ljava/util/List;"))
    private static List<FactionData> frontier$villages(LivingVillagesSavedData data, FactionData faction) {
        return RelationsTickCache.villages(faction, key -> data.getFactionVillages((FactionData) key));
    }

    @Inject(method = "strength(" + SAVED + FACTION + ")I", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void frontier$cachedStrength(LivingVillagesSavedData data, FactionData faction, CallbackInfoReturnable<Integer> result) {
        Integer cached = RelationsTickCache.strength(faction);
        if (cached != null) result.setReturnValue(cached);
    }

    @Inject(method = "strength(" + SAVED + FACTION + ")I", at = @At("RETURN"), remap = false, require = 1)
    private static void frontier$rememberStrength(LivingVillagesSavedData data, FactionData faction, CallbackInfoReturnable<Integer> result) {
        RelationsTickCache.rememberStrength(faction, result.getReturnValueI());
    }

    @Shadow
    private static Set<Long> claimChunks(LivingVillagesSavedData data, FactionData faction) { throw new AssertionError(); }

    /** Countries whose claims are further apart than the border gap cannot border each other. */
    @Inject(method = "claimsAdjacent(Lnet/minecraft/server/level/ServerLevel;" + SAVED + FACTION + FACTION + ")Z",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void frontier$farApart(ServerLevel level, LivingVillagesSavedData data, FactionData first, FactionData second,
            CallbackInfoReturnable<Boolean> result) {
        if (!RelationsTickCache.active() || ClaimEvents.recruitsClaimManager == null) return;
        int[] firstBox = RelationsTickCache.box(first, claimChunks(data, first));
        int[] secondBox = RelationsTickCache.box(second, claimChunks(data, second));
        if (firstBox != null && secondBox != null
                && RelationsTickCache.apart(firstBox, secondBox, RecruitsVillagesConfig.REL_BORDER_GAP_CHUNKS.get())) {
            result.setReturnValue(false);
        }
    }

    @Inject(method = "claimChunks(" + SAVED + FACTION + ")Ljava/util/Set;", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void frontier$cachedClaims(LivingVillagesSavedData data, FactionData faction, CallbackInfoReturnable<Set<Long>> result) {
        Set<Long> cached = RelationsTickCache.claims(faction);
        if (cached != null) result.setReturnValue(cached);
    }

    @Inject(method = "claimChunks(" + SAVED + FACTION + ")Ljava/util/Set;", at = @At("RETURN"), remap = false, require = 1)
    private static void frontier$rememberClaims(LivingVillagesSavedData data, FactionData faction, CallbackInfoReturnable<Set<Long>> result) {
        RelationsTickCache.rememberClaims(faction, result.getReturnValue());
    }
}
