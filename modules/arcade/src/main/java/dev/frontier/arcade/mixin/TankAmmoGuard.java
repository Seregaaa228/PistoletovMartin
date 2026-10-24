package dev.frontier.arcade.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The native shoot method indexes offhand by main inventory size without ammo. */
@Pseudo
@Mixin(targets="com.crescentine.trajanscore.basetank.BaseTankEntity", remap=false)
public abstract class TankAmmoGuard {
    @Shadow @Final public static Ingredient AMMO;

    @Inject(method="shoot",at=@At("HEAD"),cancellable=true,remap=false)
    private void frontier$requireOffhandShell(Player player,@Coerce Object tank,Level level,CallbackInfo callback) {
        if(!AMMO.test(player.getOffhandItem())) {
            player.displayClientMessage(Component.literal("Положи танковый снаряд во вторую руку (F)."),true);
            callback.cancel();
        }
    }
}
