package dev.frontier.ifv.mixin.catalog;

import dev.frontier.ifv.catalog.StylePolicy;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The existing Arcade creative/JEI/shop guards all converge on these two overloads. */
@Pseudo
@Mixin(targets = "dev.frontier.arcade.VehicleAccessPolicy", remap = false)
public abstract class ArcadeAccessPolicyMixin {
    @Inject(method = "isBlocked(Lnet/minecraft/resources/ResourceLocation;)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private static void frontierStyle$entity(ResourceLocation id, CallbackInfoReturnable<Boolean> callback) {
        StylePolicy.arcadeEntityHookSeen = true;
        callback.setReturnValue(StylePolicy.blockedEntity(id));
    }

    @Inject(method = "isBlocked(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private static void frontierStyle$stack(ItemStack stack, CallbackInfoReturnable<Boolean> callback) {
        StylePolicy.arcadeStackHookSeen = true;
        callback.setReturnValue(StylePolicy.blocked(stack));
    }
}
