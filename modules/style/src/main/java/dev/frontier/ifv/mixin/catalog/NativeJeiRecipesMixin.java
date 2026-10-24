package dev.frontier.ifv.mixin.catalog;

import dev.frontier.ifv.catalog.StylePolicy;

import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/** Ingredient hiding alone does not hide native assembly/perk recipe previews. */
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.compat.jei.SbwJEIPlugin", remap = false)
public abstract class NativeJeiRecipesMixin {
    @Redirect(method = "registerRecipes(Lmezz/jei/api/registration/IRecipeRegistration;)V",
        at = @At(value = "INVOKE", target = "Lmezz/jei/api/registration/IRecipeRegistration;addRecipes(Lmezz/jei/api/recipe/RecipeType;Ljava/util/List;)V", remap = false), remap = false)
    private <T> void frontierStyle$recipes(IRecipeRegistration registration, RecipeType<T> type, List<T> recipes) {
        StylePolicy.nativeJeiRecipeHookSeen = true;
        List<T> visible = recipes.stream().filter(recipe -> {
            if (recipe instanceof ItemStack stack) return !StylePolicy.blocked(stack);
            return !StylePolicy.blockedNativeAssemblingRecipe(recipe);
        }).toList();
        StylePolicy.hiddenNativeJeiRecipes += recipes.size() - visible.size();
        registration.addRecipes(type, visible);
    }
}
