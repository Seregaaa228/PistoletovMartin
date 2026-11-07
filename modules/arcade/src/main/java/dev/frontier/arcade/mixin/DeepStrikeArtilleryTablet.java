package dev.frontier.arcade.mixin;

import dev.frontier.arcade.artillery.client.ArtilleryClient;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.event.TickEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DeepStrike's console key (X, rebindable) opens its tablet in its own vehicles; in a self-propelled gun the same
 * key opens the fire-mission tablet. Clicks are consumed only there, so DeepStrike's vehicles are unaffected.
 */
@Pseudo
@Mixin(targets = "dev.sbw.deepstrike.client.PatriotDefenseScreen", remap = false)
public abstract class DeepStrikeArtilleryTablet {
    @Shadow @Final private static KeyMapping OPEN;

    @Inject(method = "clientTick", at = @At("HEAD"), remap = false)
    private static void frontier$artilleryTablet(TickEvent.ClientTickEvent event, CallbackInfo ci) {
        if (event.phase == TickEvent.Phase.END) ArtilleryClient.consoleKey(OPEN);
    }
}
