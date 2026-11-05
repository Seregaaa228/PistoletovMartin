package dev.frontier.arcade.fpv;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ru.lavafrai.uncomplicatedfpv.entity.FpvDroneEntity;

/**
 * FPV blast against the living: a share of the target's health that falls with distance, up to
 * 2 blocks. Vehicles, splash further out and blocks stay on the native warhead explosion, so
 * their armour rules, claims and the block budget still apply (FpvCombat, DroneBlastLimits).
 */
public final class FpvExplosion {
    private static final ResourceKey<DamageType> FPV_DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation("frontier_arcade_vehicles", "fpv_blast"));
    /** Crater bowl for the native explosion's block list: radius 3, 2, 1 going down. */
    public static final int CRATER_RADIUS = 3;
    private FpvExplosion() {}

    /** @return the entities already damaged here, to keep them out of the native explosion's splash */
    public static Set<Entity> damageLiving(FpvDroneEntity drone, Vec3 center, Player owner) {
        Set<Entity> damaged = new HashSet<>();
        if (drone.level().isClientSide) return damaged;
        for (LivingEntity target : drone.level().getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(2.0D),
                entity -> entity.isAlive()
                        && (!(entity instanceof Player player) || (!player.isSpectator() && !player.isCreative()))
                        && entity.getBoundingBox().distanceToSqr(center) <= 4.0D)) {
            damaged.add(target);
            double distance = Math.sqrt(target.getBoundingBox().distanceToSqr(center));
            float damageFraction = (float) (0.75D - 0.25D * distance);
            float damage = (target instanceof Player ? target.getMaxHealth() : 20.0F) * damageFraction;
            float absorption = target.getAbsorptionAmount();
            int previousInvulnerability = target.invulnerableTime;
            try {
                target.setAbsorptionAmount(0.0F);
                target.invulnerableTime = 0;
                target.hurt(new DamageSource(drone.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(FPV_DAMAGE), drone, target == owner ? null : owner), damage);
            } finally {
                target.setAbsorptionAmount(absorption);
                target.invulnerableTime = Math.max(target.invulnerableTime, previousInvulnerability);
            }
        }
        return damaged;
    }

    /** The crater bowl under the blast, before the native explosion's own loaded-chunk, budget and hardness filters. */
    public static void crater(BlockPos origin, java.util.function.Consumer<BlockPos> out) {
        for (int depth = 0; depth < CRATER_RADIUS; depth++) {
            int radius = CRATER_RADIUS - depth;
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + z * z <= radius * radius) out.accept(origin.offset(x, -depth, z));
                }
            }
        }
    }
}
