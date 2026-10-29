package dev.frontier.aviation.systemstest.mixin;

import com.atsuishio.superbwarfare.client.model.entity.VehicleModelInstance;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.frontier.aviation.AircraftRenderer;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.nio.file.Files;
import net.minecraftforge.fml.loading.FMLPaths;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read the real model state after the aircraft renderer applies its pose. */
@Mixin(value = AircraftRenderer.class, remap = false)
public abstract class F16RenderProbe {
    @Inject(method = "transformCustomModelPart(Ldev/sbw/deepstrike/vehicle/Su24mEntity;Lcom/atsuishio/superbwarfare/client/model/entity/VehicleModelInstance;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V", at = @At("RETURN"), require = 1)
    private void observe(Su24mEntity plane, VehicleModelInstance model, PoseStack pose, float age, float partial, CallbackInfo ci) {
        if (!Boolean.getBoolean("frontier.aircraftSystemsTest") || !Files.exists(FMLPaths.GAMEDIR.get().resolve("aircraft-visual-brake.txt"))) return;
        var data = new JsonObject();
        for (String name : new String[]{"gear_nose", "gear_main_l", "gear_main_r"}) {
            for (String suffix : new String[]{"", "_suspension", "_wheel"}) {
                var bone = model.getBone(name + suffix);
                data.addProperty(name + suffix, bone != null && bone.visible);
            }
        }
        for (String side : new String[]{"left", "right"}) {
            var bone = model.getBone("f16_airbrake_" + side);
            if (bone != null) data.addProperty("brake_" + side, bone.rotation.y);
        }
        try { Files.writeString(FMLPaths.GAMEDIR.get().resolve("aircraft-render-state.json"), data.toString()); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
}
