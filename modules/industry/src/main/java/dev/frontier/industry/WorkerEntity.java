package dev.frontier.industry;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;

/** A physical civilian. IndustryEngine owns its assignment and serialized recruit custody. */
public class WorkerEntity extends PathfinderMob {
    public WorkerEntity(EntityType<? extends WorkerEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setCanPickUpLoot(false);
        getPersistentData().putBoolean("frontierIndustryWorker", true);
    }
    public static AttributeSupplier.Builder attributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 20)
            .add(Attributes.MOVEMENT_SPEED, 0.28).add(Attributes.FOLLOW_RANGE, 24);
    }
    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.65) {
            @Override public boolean canUse() {
                return getPersistentData().getString("frontierIndustrySite").isEmpty()
                    && getPersistentData().getString("frontierIndustryMilitaryStation").isEmpty()
                    && getPersistentData().getString("frontierIndustryVehicle").isEmpty() && super.canUse();
            }
        });
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }
    @Override public void tick() {
        super.tick();
        if (!level().isClientSide) IndustryEngine.workerTick(this);
    }
    @Override public void die(DamageSource source) {
        super.die(source);
        // Forge LivingDeathEvent may cancel death and restore health. Settle custody only if vanilla committed it.
        if (!level().isClientSide && dead) IndustryEngine.workerDied(this);
    }
    @Override public boolean canAttack(LivingEntity target) { return false; }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public void addAdditionalSaveData(CompoundTag tag) { super.addAdditionalSaveData(tag); }
}
