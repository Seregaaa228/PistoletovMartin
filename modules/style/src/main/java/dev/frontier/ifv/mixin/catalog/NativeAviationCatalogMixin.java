package dev.frontier.ifv.mixin.catalog;

import dev.frontier.ifv.catalog.StylePolicy;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

/** Filter immutable metadata and deny stale client/server requests before any assembly. */
@Pseudo
@Mixin(targets = "dev.frontier.arcade.shop.NativeAviationCatalog", remap = false)
public abstract class NativeAviationCatalogMixin {
    @Inject(method = "offers()Ljava/util/List;", at = @At("RETURN"), cancellable = true, remap = false)
    private static void frontierStyle$offers(CallbackInfoReturnable<List<?>> callback) {
        StylePolicy.offersHookSeen = true;
        if (StylePolicy.industryRecipeAssembly()) return;
        List<?> current = callback.getReturnValue();
        List<?> visible = current.stream().filter(offer -> !StylePolicy.blockedOfferRecord(offer)).toList();
        StylePolicy.hiddenOfferCount = current.size() - visible.size();
        callback.setReturnValue(visible);
    }

    @Inject(method = "offer(Ljava/lang/String;)Ljava/util/Optional;", at = @At("HEAD"), cancellable = true, remap = false)
    private static void frontierStyle$offer(String id, CallbackInfoReturnable<Optional<?>> callback) {
        StylePolicy.offerLookupHookSeen = true;
        if (StylePolicy.blockedOffer(id)) callback.setReturnValue(Optional.empty());
    }

    @Inject(method = "createStack(Lnet/minecraft/server/level/ServerLevel;Ljava/lang/String;)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), cancellable = true, remap = false)
    private static void frontierStyle$create(ServerLevel level, String id, CallbackInfoReturnable<ItemStack> callback) {
        StylePolicy.createStackHookSeen = true;
        if (StylePolicy.blockedOffer(id) && !StylePolicy.industryRecipeAssembly()) callback.setReturnValue(ItemStack.EMPTY);
    }
}
