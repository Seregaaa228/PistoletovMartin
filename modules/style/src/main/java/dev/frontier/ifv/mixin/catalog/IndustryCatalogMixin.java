package dev.frontier.ifv.mixin.catalog;

import dev.frontier.ifv.catalog.StylePolicy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hides new orders while retaining the original recipe map for already-paid saved queues. */
@Pseudo
@Mixin(targets = "dev.frontier.industry.IndustryCatalog", remap = false)
public abstract class IndustryCatalogMixin {
    @Inject(method = "visible(Ldev/frontier/industry/IndustryCatalog$Recipe;)Z", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void frontierStyle$visible(@Coerce Object recipe, CallbackInfoReturnable<Boolean> callback) {
        StylePolicy.industryVisibilityHookSeen = true;
        if (callback.getReturnValueZ() && StylePolicy.blockedIndustryRecipe(recipe)) callback.setReturnValue(false);
    }
}
