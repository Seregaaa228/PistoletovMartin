package dev.frontier.aviation.systemstest.mixin;

import com.atsuishio.superbwarfare.client.model.entity.VehicleModelInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.AircraftRenderer;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.nio.file.Files;
import net.minecraftforge.fml.loading.FMLPaths;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only: hold every rudder at a fixed deflection, through the production hinge rotation. */
@Mixin(value = AircraftRenderer.class, remap = false)
public abstract class RudderPoseProbe {
    @Inject(method = "transformCustomModelPart(Ldev/sbw/deepstrike/vehicle/Su24mEntity;Lcom/atsuishio/superbwarfare/client/model/entity/VehicleModelInstance;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V", at = @At("RETURN"), require = 1)
    private void pose(Su24mEntity plane, VehicleModelInstance model, PoseStack pose, float age, float partial, CallbackInfo ci) {
        if (!Boolean.getBoolean("frontier.aircraftSystemsTest")) return;
        var file = FMLPaths.GAMEDIR.get().resolve("aircraft-rudder-pose.txt");
        if (!Files.isRegularFile(file)) return;
        float degrees;
        try { degrees = Float.parseFloat(Files.readString(file).trim()); }
        catch (java.io.IOException | NumberFormatException failure) { return; }
        AircraftRenderer.rotateRudder(model, AircraftProfile.of(plane), degrees);
    }
}
