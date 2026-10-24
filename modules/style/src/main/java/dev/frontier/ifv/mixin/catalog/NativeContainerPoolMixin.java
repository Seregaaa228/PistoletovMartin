package dev.frontier.ifv.mixin.catalog;

import dev.frontier.ifv.catalog.StylePolicy;

import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** Never mutate loaded data: unopened random containers use a filtered weighted copy. */
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.data.container.ContainerDataManager", remap = false)
public abstract class NativeContainerPoolMixin {
    @Inject(method = "getEntityTypes(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/List;", at = @At("RETURN"), cancellable = true, remap = false)
    private void frontierStyle$pool(ResourceLocation id, CallbackInfoReturnable<List<?>> callback) {
        StylePolicy.containerPoolHookSeen = true;
        var current = callback.getReturnValue();
        var filtered = new ArrayList<Object>();
        for (Object pair : current) {
            if (!StylePolicy.blockedContainerPoolEntry(pair)) filtered.add(pair);
        }
        StylePolicy.hiddenContainerPoolEntries = current.size() - filtered.size();
        callback.setReturnValue(filtered);
    }
}
