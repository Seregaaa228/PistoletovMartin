package com.logic.recruitstacz.mixin;

import com.google.common.collect.ImmutableMap;
import com.logic.recruitstacz.TACZRecruitsUtils;
import com.logic.recruitstacz.bridge.IPoser;
import com.logic.recruitstacz.bridge.ISpotter;
import com.logic.recruitstacz.bridge.ISwapper;
import com.logic.recruitstacz.config.TACZRecruitsConfig;
import com.logic.recruitstacz.entity.ai.*;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import com.tacz.guns.item.AmmoItem;
import com.talhanation.recruits.Main;
import com.talhanation.recruits.compat.musketmod.IWeapon;
import com.talhanation.recruits.config.RecruitsServerConfig;
import com.talhanation.recruits.entities.*;
import com.talhanation.recruits.entities.ai.async.AsyncManager;
import com.talhanation.recruits.entities.ai.async.AsyncTaskWithCallback;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Mixin(AbstractRecruitEntity.class)
public abstract class MixinAbstractRecruitEntity extends AbstractInventoryEntity implements IPoser, ISwapper, ISpotter {

    @Unique
    private static final EntityDimensions STANDING_DIMENSIONS = EntityDimensions.scalable(0.6F, 1.8F);

    @Unique
    private static final Map<Pose, EntityDimensions> POSES = ImmutableMap.<Pose, EntityDimensions>builder().put(Pose.STANDING, STANDING_DIMENSIONS).put(Pose.SLEEPING, SLEEPING_DIMENSIONS).put(Pose.FALL_FLYING, EntityDimensions.scalable(0.6F, 0.6F)).put(Pose.SWIMMING, EntityDimensions.scalable(0.6F, 0.6F)).put(Pose.SPIN_ATTACK, EntityDimensions.scalable(0.6F, 0.6F)).put(Pose.CROUCHING, EntityDimensions.scalable(0.6F, 1.5F)).put(Pose.DYING, EntityDimensions.fixed(0.2F, 0.2F)).build();

    @Shadow(remap = false)
    public TargetingConditions targetingConditions;

    @Unique
    private int poseCooldown;

    @Unique
    private int weaponSwitchCooldown;

    @Unique
    private TargetMarker lastKnownEnemyPos;

    public MixinAbstractRecruitEntity(EntityType<? extends AbstractInventoryEntity> entityType, Level world) {
        super(entityType, world);
    }

    @Inject(at=@At("TAIL"), method = "registerGoals")
    protected void registerGoals(CallbackInfo ci) {
        this.goalSelector.addGoal(2, new RecruitShootTACZGunGoal(((AbstractRecruitEntity)(Object)this)));
        this.goalSelector.addGoal(2, new RecruitsFindCoverFromTargetGoal<>(((AbstractRecruitEntity)(Object)this), 1.25));
        this.goalSelector.addGoal(3, new RecruitsChangePoseGoal<>(((AbstractRecruitEntity)(Object)this)));
        this.goalSelector.addGoal(3, new RecruitSuppressTACZGunGoal(((AbstractRecruitEntity)(Object)this)));
        this.goalSelector.addGoal(3, new RecruitWeaponSwitch(((AbstractRecruitEntity)(Object)this)));
        this.goalSelector.addGoal(3, new RecruitsAdvanceToTargetGoal(((AbstractRecruitEntity)(Object)this)));
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap=false)
    public double getMeleeStartRange() {
        if(this.getMainHandItem().getItem() instanceof IGun) {
            return (double)3.0F;
        } else {
            return 32;
        }
    }

    /**
     * @author
     * @reason Adds Customizable Search Radius
     */
    @Overwrite(remap=false)
    private void searchForTargetsAsync(ServerLevel serverLevel) {
        AABB searchBox = this.getBoundingBox().inflate((double)TACZRecruitsConfig.RECRUIT_TARGET_RADIUS.get());
        List<LivingEntity> nearby = serverLevel.getEntitiesOfClass(LivingEntity.class, searchBox, (entity) -> entity != this);
        Supplier<List<LivingEntity>> findTargetsTask = () -> {
            List<LivingEntity> copy = new ArrayList(nearby);
            copy.removeIf((potTarget) -> !this.targetingConditions.test(this, potTarget));
            copy.sort(Comparator.comparingDouble((e) -> e.distanceToSqr(this)));
            return copy.stream().limit(10L).toList();
        };
        Consumer<List<LivingEntity>> handleTargets = (targets) -> {
            if (!targets.isEmpty()) {
                this.setTarget((LivingEntity)targets.get(this.getRandom().nextInt(targets.size())));
            }

        };
        AsyncManager.executor.execute(new AsyncTaskWithCallback(findTargetsTask, handleTargets, serverLevel));
    }

    /**
     * @author
     * @reason Adds Customizable Search Radius
     */
    @Overwrite(remap=false)
    private void searchForTargetsSync(ServerLevel serverLevel) {
        AABB searchBox = this.getBoundingBox().inflate((double)TACZRecruitsConfig.RECRUIT_TARGET_RADIUS.get());
        List<LivingEntity> nearby = serverLevel.getEntitiesOfClass(LivingEntity.class, searchBox, (entity) -> entity != this);
        nearby.removeIf((potTarget) -> !this.targetingConditions.test(this, potTarget));
        nearby.sort(Comparator.comparingDouble((e) -> e.distanceToSqr(this)));
        if (!nearby.isEmpty()) {
            LivingEntity target = (LivingEntity)nearby.stream().limit(10L).toList().get(this.getRandom().nextInt(Math.min(10, nearby.size())));
            this.setTarget(target);
        }

    }

    @Override
    public boolean hasLineOfSight(Entity p_147185_) {
        if (p_147185_.level() != this.level()) {
            return false;
        } else {
            Vec3 vec3 = new Vec3(this.getX(), this.getEyeY(), this.getZ());
            Vec3 vec31 = new Vec3(p_147185_.getX(), p_147185_.getEyeY(), p_147185_.getZ());
            if (vec31.distanceTo(vec3) > 1000.0D) {
                return false;
            } else {
                return this.level().clip(new ClipContext(vec3, vec31, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
            }
        }
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap=false)
    public float getStandingEyeHeight(Pose p_36259_, EntityDimensions p_36260_) {
        switch (p_36259_) {
            case SWIMMING:
            case FALL_FLYING:
            case SPIN_ATTACK:
                return 0.4F;
            case CROUCHING:
                return 1.27F;
            default:
                return p_36260_.height * 0.98F;
        }
    }

    @Override
    public void setWeaponSwitchCooldown(int weaponSwitchCooldown) {
        this.weaponSwitchCooldown = weaponSwitchCooldown;
    }

    @Override
    public int getWeaponSwitchCooldown() {
        return weaponSwitchCooldown;
    }

    @Inject(method = "tick", at = @At("TAIL"))
    public void tick(CallbackInfo ci) {
        poseCooldown--;
        weaponSwitchCooldown--;
    }

    @Inject(method = "needsToGetFood", at = @At("HEAD"), cancellable = true, remap = false)
    public void needsToGetFood(CallbackInfoReturnable<Boolean> cir) {
        ItemStack mainHandItem = this.getMainHandItem();

        if(mainHandItem.getItem() instanceof IGun && this.getAmmoCount(mainHandItem) <= 0 && RecruitsServerConfig.RangedRecruitsNeedArrowsToShoot.get()) {
            cir.setReturnValue(true);
        }
    }

    @Override
    public void changePose() {
        if(this.getPose() == Pose.STANDING) {
            this.setPose(Pose.CROUCHING);
        } else if(this.getPose() == Pose.CROUCHING && TACZRecruitsConfig.SHOULD_RECRUIT_PRONE.get()) {
            ((IGunOperator)this).crawl(true);
        } else {
            ((IGunOperator)this).crawl(false);
            this.setPose(Pose.STANDING);
        }
    }

    @Override
    public void setPoseCooldown(int poseCooldown) {
        this.poseCooldown = poseCooldown;
    }

    @Override
    public int getPoseCooldown() {
        return poseCooldown;
    }


    public EntityDimensions getDimensions(Pose pose) {
        return POSES.getOrDefault(pose, STANDING_DIMENSIONS);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public void upkeepReequip(@NotNull Container container) {
        for(int i = 0; i < container.getContainerSize(); ++i) {
            ItemStack itemstack = container.getItem(i);
            if (!this.canEatItemStack(itemstack) && this.wantsToPickUp(itemstack)) {
                label63: {
                    if (this.canEquipItem(itemstack)) {
                        ItemStack equipment = itemstack.copy();
                        equipment.setCount(1);
                        this.equipItem(equipment);
                        itemstack.shrink(1);
                    }

                    if (((AbstractRecruitEntity)(Object)this) instanceof CrossBowmanEntity) {
                        CrossBowmanEntity crossBowmanEntity = (CrossBowmanEntity)((AbstractRecruitEntity)(Object)this);
                        if (Main.isMusketModLoaded && IWeapon.isMusketModWeapon(crossBowmanEntity.getMainHandItem()) && itemstack.getDescriptionId().contains("cartridge")) {
                            if (this.canTakeCartridge()) {
                                ItemStack equipment = itemstack.copy();
                                this.inventory.addItem(equipment);
                                itemstack.shrink(equipment.getCount());
                            }
                            break label63;
                        }
                    }

                    if (this instanceof IRangedRecruit && itemstack.is(ItemTags.ARROWS) && this.canTakeArrows()) {
                        ItemStack equipment = itemstack.copy();
                        this.inventory.addItem(equipment);
                        itemstack.shrink(equipment.getCount());
                    }

                    ItemStack mainHandItem = this.getMainHandItem();

                    if (mainHandItem.getItem() instanceof IGun) {
                        if(itemstack.getItem() instanceof AmmoItem ammoItem) {
                            if(ammoItem.isAmmoOfGun(mainHandItem, itemstack)) {
                                if(this.canTakeAmmoForGun(mainHandItem)) {
                                    for(int j = 5; j < 15; j++) {
                                        ItemStack itemStack = this.inventory.getItem(j);

                                        if(itemStack.isEmpty()) {
                                            ItemStack equipment = itemstack.copy();
                                            this.inventory.setItem(j, equipment);
                                            itemstack.shrink(equipment.getCount());
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (((AbstractRecruitEntity)(Object)this) instanceof CaptainEntity && Main.isSmallShipsLoaded) {
                if (itemstack.getDescriptionId().contains("cannon_ball")) {
                    if (this.canTakeCannonBalls()) {
                        ItemStack equipment = itemstack.copy();
                        this.inventory.addItem(equipment);
                        itemstack.shrink(equipment.getCount());
                    }
                } else if (itemstack.is(ItemTags.PLANKS)) {
                    if (this.canTakePlanks()) {
                        ItemStack equipment = itemstack.copy();
                        this.inventory.addItem(equipment);
                        itemstack.shrink(equipment.getCount());
                    }
                } else if (itemstack.is(Items.IRON_NUGGET) && this.canTakeIronNuggets()) {
                    ItemStack equipment = itemstack.copy();
                    this.inventory.addItem(equipment);
                    itemstack.shrink(equipment.getCount());
                }
            }
        }

    }

    @Unique
    private boolean canTakeAmmoForGun(ItemStack gunStack) {
        int count = getAmmoCount(gunStack);

        return count < TACZRecruitsUtils.getGunTargetAmmo(gunStack);
    }

    private int getAmmoCount(ItemStack gunStack) {
        int count = 0;

        if(gunStack.getItem() instanceof AbstractGunItem) {
            for(ItemStack itemStack : this.inventory.items) {
                Item item = itemStack.getItem();

                if(item instanceof AmmoItem ammoItem) {
                    if(ammoItem.isAmmoOfGun(gunStack, itemStack)) {
                        count += itemStack.getCount();
                    }
                }
            }
        }

        return count;
    }

    @Shadow(remap = false)
    public boolean canEatItemStack(ItemStack stack) {
        throw new AssertionError();
    }

    @Override
    public TargetMarker getLastKnownEnemyPos() {
        return lastKnownEnemyPos;
    }

    @Override
    public void setLastKnownEnemyPos(TargetMarker lastKnownEnemyPos) {
        this.lastKnownEnemyPos = lastKnownEnemyPos;
    }
}

