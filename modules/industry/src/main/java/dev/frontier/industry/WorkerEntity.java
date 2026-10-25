package dev.frontier.industry;

import dev.frontier.industry.repair.RepairApproachBehaviour;
import dev.frontier.industry.repair.RepairStatus;
import dev.frontier.industry.repair.RepairTargetSensor;
import dev.frontier.industry.repair.RepairWork;
import dev.frontier.industry.repair.RepairWorkBehaviour;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.tslat.smartbrainlib.api.SmartBrainOwner;
import net.tslat.smartbrainlib.api.core.BrainActivityGroup;
import net.tslat.smartbrainlib.api.core.SmartBrainProvider;
import net.tslat.smartbrainlib.api.core.sensor.ExtendedSensor;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/** A physical civilian. IndustryEngine owns its assignment and serialized recruit custody. */
public class WorkerEntity extends PathfinderMob implements SmartBrainOwner<WorkerEntity>, GeoEntity {
    private static final EntityDataAccessor<Integer> WORK_ANIMATION = SynchedEntityData.defineId(WorkerEntity.class, EntityDataSerializers.INT);
    private static final String REPAIR_STATUS = "frontierRepairStatus";
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.worker.idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.worker.walk");
    private static final RawAnimation REPAIR = RawAnimation.begin().thenLoop("animation.worker.repair");
    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);
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
    @Override protected void defineSynchedData() { super.defineSynchedData(); entityData.define(WORK_ANIMATION, 0); }
    @Override protected Brain.Provider<?> brainProvider() { return new SmartBrainProvider<>(this); }
    @Override protected void customServerAiStep() { tickBrain(this); }
    @Override public List<? extends ExtendedSensor<? extends WorkerEntity>> getSensors() { return List.of(new RepairTargetSensor()); }
    @Override public BrainActivityGroup<? extends WorkerEntity> getCoreTasks() {
        return BrainActivityGroup.coreTasks(new RepairApproachBehaviour(), new RepairWorkBehaviour());
    }
    public RepairStatus getRepairStatus() { return RepairStatus.fromKey(getPersistentData().getString(REPAIR_STATUS)); }
    public void setRepairStatus(RepairStatus status) {
        if (level().isClientSide) return;
        if (!status.key.equals(getPersistentData().getString(REPAIR_STATUS))) getPersistentData().putString(REPAIR_STATUS, status.key);
        int animation = status.animation;
        if (animation == 0 && !getNavigation().isDone() && !RepairWork.repairWorker(this)) animation = 1;
        entityData.set(WORK_ANIMATION, animation);
    }
    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "work", 4, state -> state.setAndContinue(
            entityData.get(WORK_ANIMATION) == 2 ? REPAIR : entityData.get(WORK_ANIMATION) == 1 ? WALK : IDLE)));
    }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return animationCache; }
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
        if (!level().isClientSide) {
            IndustryEngine.workerTick(this);
            if (RepairWork.repairWorker(this)) RepairWork.refreshState(this);
            else setRepairStatus(RepairStatus.IDLE);
        }
    }
    @Override public void die(DamageSource source) {
        super.die(source);
        // Forge LivingDeathEvent may cancel death and restore health. Settle custody only if vanilla committed it.
        if (!level().isClientSide && dead) { RepairWork.halt(this, RepairStatus.DEAD); IndustryEngine.workerDied(this); }
    }
    @Override public boolean canAttack(LivingEntity target) { return false; }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public void addAdditionalSaveData(CompoundTag tag) { super.addAdditionalSaveData(tag); }
}
