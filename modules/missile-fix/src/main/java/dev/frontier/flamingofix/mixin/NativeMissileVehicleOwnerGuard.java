package dev.frontier.flamingofix.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModDamageTypes;
import dev.sbw.deepstrike.game.GuidedMissileEntity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** An absent attacker and absent historical driver are not a self-hit. */
@Mixin(value=VehicleEntity.class,remap=false)
public abstract class NativeMissileVehicleOwnerGuard {
    @Redirect(method="m_6469_(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/damagesource/DamageSource;"
                    +"m_276093_(Lnet/minecraft/resources/ResourceKey;)Z",ordinal=1),remap=false,require=1)
    private boolean flamingo$realSelfHitOnly(DamageSource source,ResourceKey<DamageType> type) {
        // Pinned SBW's second ResourceKey check is the VEHICLE_STRIKE exception
        // inside attacker == nativeDriver. Reaching it with null attacker proves
        // nativeDriver is also null. Only skip that rejection for actual Guided
        // explosion damage; do not change the source type or any later native stage.
        if(source.is(type))return true;
        if(type!=ModDamageTypes.VEHICLE_STRIKE || source.getEntity()!=null
                || !(source.getDirectEntity() instanceof GuidedMissileEntity missile)
                || !source.is(DamageTypeTags.IS_EXPLOSION))return false;
        // Retain a known self-hit identity after logout, without constructing an
        // offline player or inferring a team from an unresolved historical UUID.
        return missile.ownerId()==null
                || !missile.ownerId().toString().equals(((VehicleEntity)(Object)this).getLastDriverUUID());
    }
}
