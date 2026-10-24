package dev.frontier.ifv.mixin.catalog;

import dev.frontier.ifv.catalog.StylePolicy;

import com.atsuishio.superbwarfare.recipe.vehicle.VehicleAssemblingRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/** The native screen retains its own categories/buttons with only available recipes. */
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.client.screens.VehicleAssemblingScreen", remap = false)
public abstract class NativeAssemblyScreenMixin {
    @Shadow @Final private Map<?, List<ResourceLocation>> recipes;
    @Shadow private VehicleAssemblingRecipe currentRecipe;

    @Inject(method = "initRecipes()V", at = @At("RETURN"), remap = false)
    private void frontierStyle$recipes(CallbackInfo callback) {
        StylePolicy.nativeAssemblyScreenHookSeen = true;
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        var manager = level.getRecipeManager();
        for (var category : recipes.values()) {
            category.removeIf(id -> manager.byKey(id).map(StylePolicy::blockedNativeAssemblingRecipe).orElse(false));
        }
        if (StylePolicy.blockedNativeAssemblingRecipe(currentRecipe)) currentRecipe = null;
    }
}
