package dev.frontier.arcade.fpv;

import com.atsuishio.superbwarfare.tools.CustomExplosion;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ru.lavafrai.uncomplicatedfpv.entity.FpvDroneEntity;

public final class FpvExplosion {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceKey<DamageType> FPV_DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation("frontier_arcade_vehicles", "fpv_blast"));
    private FpvExplosion() {}

    public static void detonate(FpvDroneEntity drone, Vec3 center, Player owner) {
        if (drone.level().isClientSide) return;
        int targetsFound = 0;
        for (LivingEntity target : drone.level().getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(2.0D),
                entity -> entity.isAlive()
                        && (!(entity instanceof Player player) || (!player.isSpectator() && !player.isCreative()))
                        && entity.getBoundingBox().distanceToSqr(center) <= 4.0D)) {
            targetsFound++;
            double distance = Math.sqrt(target.getBoundingBox().distanceToSqr(center));
            float damageFraction = (float) (0.75D - 0.25D * distance);
            float damage = (target instanceof Player ? target.getMaxHealth() : 20.0F) * damageFraction;
            float absorption = target.getAbsorptionAmount();
            int previousInvulnerability = target.invulnerableTime;
            try {
                target.setAbsorptionAmount(0.0F);
                target.invulnerableTime = 0;
                float healthBefore = target.getHealth();
                boolean accepted = target.hurt(new DamageSource(drone.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(FPV_DAMAGE), drone, target == owner ? null : owner), damage);
                LOGGER.info("FPV blast: target type={}, requested={}, accepted={}, health before={}, after={}",
                        target.getType().toShortString(), damage, accepted, healthBefore, target.getHealth());
            } finally {
                target.setAbsorptionAmount(absorption);
                target.invulnerableTime = Math.max(target.invulnerableTime, previousInvulnerability);
            }
        }
        LOGGER.info("FPV blast: {} living targets within 2 blocks", targetsFound);
        for (LivingEntity nearby : drone.level().getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(8.0D), entity -> entity.isAlive())) {
            LOGGER.info("FPV blast nearby: target type={}, distance to bounding box={}",
                    nearby.getType().toShortString(), Math.sqrt(nearby.getBoundingBox().distanceToSqr(center)));
        }
        // Use SBW for visuals and sound only; block damage is built explicitly below.
        new CustomExplosion.Builder(drone).position(center).attacker(owner)
                .damage(0.0F).radius(2.0F).destroyBlock(false).explode();
        crater(drone, center);
    }

    private static void crater(FpvDroneEntity drone, Vec3 center) {
        BlockPos origin = BlockPos.containing(center.x, center.y - 0.1D, center.z);
        for (int depth = 0; depth < 3; depth++) {
            int radius = 3 - depth;
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + z * z > radius * radius) continue;
                    BlockPos pos = origin.offset(x, -depth, z);
                    BlockState state = drone.level().getBlockState(pos);
                    if (!state.isAir() && state.getDestroySpeed(drone.level(), pos) >= 0.0F) {
                        drone.level().destroyBlock(pos, false, drone);
                    }
                }
            }
        }
    }
}
