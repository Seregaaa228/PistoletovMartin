package dev.frontier.arcade.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.damage.DamageModifier;
import com.atsuishio.superbwarfare.init.ModDamageTypes;
import com.tacz.guns.entity.EntityKineticBullet;
import dev.frontier.arcade.balance.VehicleBallistics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Adjust only recognized gunfire after native immunity and team checks, before subsystem/hull damage. */
@Mixin(value = VehicleEntity.class, remap = false)
public abstract class VehicleBallisticsMixin {
    @Redirect(method = "m_6469_(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/vehicle/damage/DamageModifier;compute(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;F)F"), remap = false)
    private float frontier$balanceGunfire(DamageModifier modifier, Entity entity, DamageSource source, float amount) {
        String ammo = "", gun = "";
        if (source.is(com.tacz.guns.init.ModDamageTypes.BULLETS_TAG) && source.getDirectEntity() instanceof EntityKineticBullet bullet) {
            ammo = bullet.getAmmoId() == null ? "" : bullet.getAmmoId().toString();
            gun = bullet.getGunId() == null ? "" : bullet.getGunId().toString();
        } else if (source.is(ModDamageTypes.GUN_FIRE) || source.is(ModDamageTypes.GUN_FIRE_ABSOLUTE)
                || source.is(ModDamageTypes.GUN_FIRE_HEADSHOT) || source.is(ModDamageTypes.GUN_FIRE_HEADSHOT_ABSOLUTE)) {
            if (source.getEntity() instanceof LivingEntity shooter) {
                ItemStack held = shooter.getMainHandItem();
                ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(held.getItem());
                gun = itemId == null ? "" : itemId.toString();
            }
        } else {
            return modifier.compute(entity, source, amount);
        }
        VehicleEntity vehicle = (VehicleEntity) (Object) this;
        if (!Float.isFinite(vehicle.getMaxHealth()) || vehicle.getMaxHealth() <= 0) return modifier.compute(entity, source, amount);
        ResourceLocation id = EntityType.getKey(vehicle.getType());
        return VehicleBallistics.damage(VehicleBallistics.vehicle(id == null ? "" : id.toString()),
                VehicleBallistics.ammo(ammo, gun), amount, vehicle.getMaxHealth(), gun);
    }
}
