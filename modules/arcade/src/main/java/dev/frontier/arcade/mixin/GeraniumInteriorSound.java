package dev.frontier.arcade.mixin;

import dev.frontier.arcade.geranium.GeraniumEntity;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.RegistryObject;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;
import ru.lavafrai.uncomplicatedfpv.registry.ModSounds;

/** Selects the original Shahed engine only for this variant's existing native monitor audio. */
@Mixin(targets = "ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity$ClientAudio$InteriorSound", remap = false)
public abstract class GeraniumInteriorSound {
    /* The field read occurs before super(); a static redirect captures only the
       constructor's drone argument and never touches its uninitialized this. */
    @Redirect(
        method = "<init>(Lru/lavafrai/uncomplicatedfpv/entity/AddonDroneEntity;)V",
        at = @At(
            value = "FIELD",
            target = "Lru/lavafrai/uncomplicatedfpv/registry/ModSounds;FPV_DRONE_ENGINE_INT:Lnet/minecraftforge/registries/RegistryObject;",
            opcode = Opcodes.GETSTATIC,
            remap = false
        ),
        remap = false,
        require = 1
    )
    private static RegistryObject<SoundEvent> frontier$geraniumInteriorEngine(AddonDroneEntity drone) {
        return drone instanceof GeraniumEntity ? ModSounds.SHAHED136_ENGINE : ModSounds.FPV_DRONE_ENGINE_INT;
    }
}
