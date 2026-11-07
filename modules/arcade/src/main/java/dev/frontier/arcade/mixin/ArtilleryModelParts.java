package dev.frontier.arcade.mixin;

import com.atsuishio.superbwarfare.client.model.entity.VehicleModelInstance;
import com.atsuishio.superbwarfare.client.renderer.entity.Plz05Renderer;
import com.atsuishio.superbwarfare.entity.vehicle.base.ArtilleryEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.frontier.arcade.artillery.client.ArtilleryModelHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Our guns' own moving parts (stabilisers, cab interior) after the PLZ-05 renderer has posed its bones. */
@Mixin(value = Plz05Renderer.class, remap = false)
public abstract class ArtilleryModelParts {
    @Inject(method = "transformCustomModelPart(Lcom/atsuishio/superbwarfare/entity/vehicle/base/ArtilleryEntity;"
        + "Lcom/atsuishio/superbwarfare/client/model/entity/VehicleModelInstance;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
        at = @At("TAIL"), remap = false)
    private void frontier$ownParts(ArtilleryEntity entity, VehicleModelInstance instance, PoseStack pose, float age, float partial, CallbackInfo ci) {
        ArtilleryModelHooks.apply(entity, instance, partial);
    }
}
