package dev.frontier.ifv.mixin.catalog;

import dev.frontier.ifv.catalog.StylePolicy;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The native server transaction resolves its recipe before extracting any ingredients. */
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.inventory.menu.VehicleAssemblingMenu", remap = false)
public abstract class NativeAssemblyMenuMixin {
    @Inject(method = "getRecipeById(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/world/item/crafting/RecipeManager;)Lcom/atsuishio/superbwarfare/recipe/vehicle/VehicleAssemblingRecipe;", at = @At("RETURN"), cancellable = true, remap = false)
    private void frontierStyle$recipe(ResourceLocation id, RecipeManager manager, CallbackInfoReturnable<Object> callback) {
        StylePolicy.nativeAssemblyMenuHookSeen = true;
        if (StylePolicy.blockedNativeAssemblingRecipe(callback.getReturnValue())) callback.setReturnValue(null);
    }
}
