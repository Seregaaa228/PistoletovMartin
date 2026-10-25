package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.MissileDirectBlast;
import dev.sbw.deepstrike.game.GuidedMissileEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Guided is a plain Entity, so vanilla's projectile owner discovery cannot see its recorded owner. */
@Mixin(value=Explosion.class,remap=false)
public abstract class NativeMissileBlastOwner {
    @Redirect(method="<init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;"
            +"Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;"
            +"DDDFZLnet/minecraft/world/level/Explosion$BlockInteraction;)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/damagesource/DamageSources;"
                    +"m_269093_(Lnet/minecraft/world/level/Explosion;)Lnet/minecraft/world/damagesource/DamageSource;"),
            remap=false,require=1)
    private DamageSource flamingo$recordedOwner(DamageSources sources,Explosion explosion) {
        DamageSource original=sources.explosion(explosion);
        // During the base constructor, the subclass fields are not initialized.
        // The direct source has already been stored by vanilla and is sufficient.
        if(!(explosion instanceof MissileDirectBlast) || original.getEntity()!=null
                || !(original.getDirectEntity() instanceof GuidedMissileEntity missile)
                || missile.ownerId()==null || !(missile.level() instanceof ServerLevel level))return original;
        Entity owner=level.getEntity(missile.ownerId()); // Exactly the pinned native getOwner lookup.
        if(owner==null)return original; // No substitute player/entity for a logged-out or unknown owner.
        // DamageSources.explosion(missile,owner) would change EXPLOSION to PLAYER_EXPLOSION.
        // Keep the original holder, direct entity and optional position for native armor/tags.
        return new DamageSource(original.typeHolder(),original.getDirectEntity(),owner,original.sourcePositionRaw());
    }
}
