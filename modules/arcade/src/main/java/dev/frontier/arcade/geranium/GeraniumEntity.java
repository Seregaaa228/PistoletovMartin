package dev.frontier.arcade.geranium;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.atsuishio.superbwarfare.tools.CustomExplosion;
import com.atsuishio.superbwarfare.tools.ParticleTool;
import dev.frontier.arcade.relay.RelayNetwork;
import dev.frontier.arcade.control.ControlSessions;
import dev.frontier.arcade.combat.DroneSweep;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import org.joml.Vector3f;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;

/** Own manually flown Shahed variant; native monitor, movement packets and signal/REB model. */
public final class GeraniumEntity extends AddonDroneEntity {
    public static final int BATTERY = 64000, ENERGY_PER_TICK = 8;
    public static final double MAX_RANGE = 256;
    public static final float NOSE_Y = .097f, NOSE_Z = 2.2025f;
    private static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(GeraniumEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Boolean> LAUNCHED = SynchedEntityData.defineId(GeraniumEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> SPEED = SynchedEntityData.defineId(GeraniumEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> THROTTLE = SynchedEntityData.defineId(GeraniumEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> LINK_RANGE = SynchedEntityData.defineId(GeraniumEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> LINK_DISTANCE = SynchedEntityData.defineId(GeraniumEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> FLIGHT_PITCH = SynchedEntityData.defineId(GeraniumEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> FLIGHT_YAW = SynchedEntityData.defineId(GeraniumEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> FLIGHT_ROLL = SynchedEntityData.defineId(GeraniumEntity.class, EntityDataSerializers.FLOAT);
    private GeraniumFlightModel flight;
    private boolean detonated, airborne;
    private int controlledTicks;
    private Vec3 lastNose;
    public GeraniumEntity(EntityType<? extends DroneEntity> type, Level level) { super(type, level); }
    @Override protected void defineSynchedData() { super.defineSynchedData(); entityData.define(OWNER, Optional.empty());
        entityData.define(LAUNCHED, false); entityData.define(SPEED, 0f); entityData.define(THROTTLE, GeraniumFlightModel.DEFAULT_THROTTLE);
        entityData.define(LINK_RANGE, 16f); entityData.define(LINK_DISTANCE, 0f);
        entityData.define(FLIGHT_PITCH, 0f); entityData.define(FLIGHT_YAW, 0f); entityData.define(FLIGHT_ROLL, 0f); }
    public void setOwner(UUID owner) { entityData.set(OWNER, Optional.of(owner)); getPersistentData().putUUID("frontierIndustryVehicleOwner", owner); }
    public double signalRange() {
        if (level().isClientSide || getServer() == null) return entityData.get(LINK_RANGE);
        return MAX_RANGE;
    }
    @Override public void registerControllers(software.bernie.geckolib.core.animation.AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new software.bernie.geckolib.core.animation.AnimationController<>(this, "propeller", 0, state -> {
            state.setAnimation(software.bernie.geckolib.core.animation.RawAnimation.begin().thenLoop(engineRunning() ? "animation.geranium_2.flight" : "animation.geranium_2.idle"));
            return software.bernie.geckolib.core.object.PlayState.CONTINUE;
        }));
    }
    private GeraniumFlightModel flight() { if (flight == null) flight = new GeraniumFlightModel(getYRot(), getXRot()); return flight; }
    public boolean isLaunched() { return entityData.get(LAUNCHED); }
    public int throttlePercent() { return Math.round(entityData.get(THROTTLE) * 100); }
    public double flightSpeed() { return entityData.get(SPEED); }
    public double flightRange() { return entityData.get(LINK_DISTANCE); }
    private Vec3 radioOrigin() {
        Vec3 original = getOperatorPosition();
        Player owner = ownerId() == null ? null : level().getPlayerByUUID(ownerId());
        return original != null ? original : owner == null ? null : owner.getEyePosition();
    }
    public void stabilizeFlight() { if (!level().isClientSide && activeMonitor() && isLaunched()) flight().stabilize(); }
    private void syncFlight() {
        var state = flight(); entityData.set(LAUNCHED, state.launched()); entityData.set(THROTTLE, state.throttle());
        entityData.set(SPEED, (float)getDeltaMovement().length());
        entityData.set(FLIGHT_YAW, state.yaw()); entityData.set(FLIGHT_PITCH, state.pitch()); entityData.set(FLIGHT_ROLL, state.roll());
    }
    @Override public void processInput(short keys) {
        if (level().isClientSide || !activeMonitor() || getEnergy() <= 0 || isSignalLost()) return;
        super.processInput(keys);
        flight().input(keys);
    }
    @Override public void mouseInput(double x, double y) {
        if (!level().isClientSide && activeMonitor() && getEnergy() > 0 && !isSignalLost()) flight().mouse(x, y);
    }
    public UUID ownerId() { return entityData.get(OWNER).orElse(null); }
    public boolean ownedBy(Player player) { return player != null && player.getUUID().equals(ownerId()); }
    @Override public float getYaw(float partial) { return net.minecraft.util.Mth.rotLerp(partial, yRotO, getYRot()); }
    @Override public net.minecraft.sounds.SoundEvent getEngineSound() { return ru.lavafrai.uncomplicatedfpv.registry.ModSounds.SHAHED136_ENGINE.get(); }
    @Override public float getMaxHealth() { return 8f; }
    @Override public ResourceLocation getModelResource() { return new ResourceLocation("frontier_arcade_vehicles", "geo/geranium_2.geo.json"); }
    @Override public ResourceLocation getTextureResource() { return new ResourceLocation("uncomplicatedfpv", "textures/entity/shahed136.png"); }
    @Override public ResourceLocation getAnimationResource() { return new ResourceLocation("frontier_arcade_vehicles", "animations/geranium_2.animation.json"); }
    @Override public int getAmmo() { return 1; } // Built-in sealed warhead; interact never exposes an ammo-removal path.
    @Override public ItemStack getPickResult() { return new ItemStack(GeraniumContent.ITEM.get()); }
    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag); if (ownerId() != null) tag.putUUID("OwnerUUID", ownerId());
        var state = flight(); CompoundTag saved = new CompoundTag();
        saved.putFloat("Yaw",state.yaw()); saved.putFloat("Pitch",state.pitch()); saved.putFloat("Roll",state.roll());
        saved.putFloat("Speed",state.speed()); saved.putFloat("Throttle",state.throttle());
        saved.putInt("Ticks",state.flightTicks()); saved.putBoolean("Launched",state.launched()); tag.put("GeraniumFlight",saved);
        tag.putBoolean("GeraniumAirborne", airborne); tag.putInt("GeraniumControlTicks", controlledTicks);
    }
    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag); if (tag.hasUUID("OwnerUUID")) setOwner(tag.getUUID("OwnerUUID"));
        airborne = tag.getBoolean("GeraniumAirborne"); controlledTicks = Math.max(0, Math.min(12000, tag.getInt("GeraniumControlTicks")));
        setEnergy(Math.max(0, Math.min(BATTERY, getEnergy())));
        if (tag.contains("GeraniumFlight",10)) {
            var f = tag.getCompound("GeraniumFlight"); flight().restore(f.getFloat("Yaw"),f.getFloat("Pitch"),f.getFloat("Roll"),f.getFloat("Speed"),f.getFloat("Throttle"),f.getInt("Ticks"),f.getBoolean("Launched"));
        } else if (airborne) flight().restore(getYRot(),getXRot(),getRoll(1),(float)getDeltaMovement().length(),.6f,Math.max(20,controlledTicks),true);
        syncFlight();
    }
    public boolean activeMonitor() {
        if (ownerId() == null) return false;
        Player owner = level().getPlayerByUUID(ownerId()); if (owner == null) return false;
        ItemStack stack = owner.getMainHandItem(); var tag = stack.getTag();
        return ControlSessions.monitorFor(owner, stack, this) && tag != null && tag.getBoolean("Using") && tag.getBoolean("Linked")
            && getStringUUID().equals(tag.getString("LinkedDrone")) && owner.getStringUUID().equals(entityData.get(DroneEntity.CONTROLLER));
    }
    private static boolean monitor(ItemStack stack) { return ControlSessions.isMonitor(stack); }
    @Override public InteractionResult interact(Player player, InteractionHand hand) {
        if (!ownedBy(player)) { if (!level().isClientSide) player.displayClientMessage(Component.literal("Это чужая Герань-2."), true); return InteractionResult.FAIL; }
        ItemStack held = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && held.isEmpty()) {
            if (!level().isClientSide && player instanceof ServerPlayer serverPlayer) {
                if (!onGround() || getDeltaMovement().lengthSqr() > .01 || activeMonitor()) {
                    player.displayClientMessage(Component.literal("Подбор: аппарат должен стоять неподвижно на земле, монитор выключен."), true); return InteractionResult.FAIL;
                }
                ItemStack recovered = new ItemStack(GeraniumContent.ITEM.get()); recovered.getOrCreateTag().putInt("GeraniumEnergy", getEnergy());
                if (!player.getInventory().add(recovered)) { player.displayClientMessage(Component.literal("Освободи место в инвентаре."), true); return InteractionResult.FAIL; }
                ControlSessions.forgetPair(this); handleSignalLoss(serverPlayer); discard();
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (monitor(held)) return super.interact(player, hand);
        if (!held.isEmpty() && held.getCapability(ForgeCapabilities.ENERGY).isPresent()) return super.interact(player, hand);
        if (!level().isClientSide) player.displayClientMessage(Component.literal("Управление — с наземной станции; Shift+ПКМ пустой рукой — подобрать на земле."), true);
        return InteractionResult.sidedSuccess(level().isClientSide);
    }
    @Override public void travel() {
        if (level().isClientSide) return; // Parent native interpolation handles the observer.
        var state = flight();
        boolean powered = getEnergy() > 0 && !isSignalLost() && radioOrigin() != null && ownerId() != null && level().getPlayerByUUID(ownerId()) != null;
        boolean control = activeMonitor() && powered;
        state.tick(control, powered);
        if (state.launched() && powered) {
            // Original Shahed updateFlight: accelerate by .05, turn by 1.5 degrees,
            // and move strictly along the nose. Parent VehicleEntity moves once.
            setDeltaMovement(Vec3.directionFromRotation(state.pitch(), state.yaw()).scale(state.speed()));
            setNoGravity(true); setPower(.07f + .13f * state.throttle()); consumeEnergy(ENERGY_PER_TICK);
        } else {
            setPower(0); setNoGravity(false); resetControlInputs();
            setDeltaMovement(onGround() ? new Vec3(0, -.06, 0) : getDeltaMovement().multiply(.98, 1, .98).add(0, -.06, 0));
        }
        setYRot(state.yaw()); setXRot(state.pitch()); setBodyXRot(state.pitch()); setZRot(state.roll()); syncFlight();
    }
    @Override public void baseTick() {
        if (!level().isClientSide && (activeMonitor() || isLaunched())) {
            Vec3 origin = radioOrigin();
            Player owner = ownerId() == null ? null : level().getPlayerByUUID(ownerId());
            if (origin == null && owner != null) origin = owner.position();
            if (origin == null || !RelayNetwork.reachable(this, origin)) {
                if (!isSignalLost() || activeMonitor()) handleSignalLoss(owner instanceof ServerPlayer serverPlayer ? serverPlayer : null);
                resetControlInputs(); setPower(0); setNoGravity(false);
            }
        }
        if (!level().isClientSide && getFire() && activeMonitor() && isLaunched() && flight().flightTicks() >= 20) { detonate(nosePosition(1)); return; }
        if (!level().isClientSide) {
            entityData.set(LINK_RANGE,(float)signalRange()); Vec3 origin = radioOrigin();
            entityData.set(LINK_DISTANCE,origin == null ? 0f : (float)position().distanceTo(origin));
        }
        super.baseTick(); if (isRemoved()) return;
        // DroneEntity applies quadcopter damping around parent tick; restore the
        // original Shahed attitude after its shared networking/monitor work.
        setYRot(entityData.get(FLIGHT_YAW)); setXRot(entityData.get(FLIGHT_PITCH));
        setBodyXRot(entityData.get(FLIGHT_PITCH)); setZRot(entityData.get(FLIGHT_ROLL));
        if (level().isClientSide) return;
        if (activeMonitor()) controlledTicks++;
        if (isLaunched() && flight().flightTicks() >= 20) airborne = true;
        Vec3 nose = nosePosition(1);
        if (airborne && lastNose != null && lastNose.distanceToSqr(nose) > .0004) {
            var hit = DroneSweep.trace(this, lastNose, nose, this::impactTarget);
            if (hit != null) { detonate(hit.getLocation()); return; }
        }
        if (airborne && (horizontalCollision || (verticalCollision && onGround()))) { detonate(nose); return; }
        lastNose = nose;
    }
    private boolean impactTarget(net.minecraft.world.entity.Entity target) {
        return target != this && target.isAlive() && target.isPickable() && !target.isSpectator()
            && !target.getUUID().equals(ownerId()) && !(target instanceof net.minecraft.world.entity.item.ItemEntity)
            && !(target instanceof net.minecraft.world.entity.projectile.Projectile)
            && !(target instanceof net.minecraft.world.entity.AreaEffectCloud);
    }
    @Override public void hitEntityCrash(Player controller, net.minecraft.world.entity.Entity target) {
        if (!level().isClientSide && airborne && impactTarget(target)) detonate(nosePosition(1));
    }
    @Override public void destroy() { detonate(nosePosition(1)); }
    public void detonate(Vec3 point) {
        if (detonated || isRemoved() || level().isClientSide) return;
        detonated = true;
        ControlSessions.forgetPair(this);
        Player owner = ownerId() == null ? null : level().getPlayerByUUID(ownerId());
        handleSignalLoss(owner instanceof ServerPlayer serverPlayer ? serverPlayer : null);
        new CustomExplosion.Builder(this).position(point).attacker(owner).damage(300f).radius(16f)
            .withParticleType(ParticleTool.ParticleType.HUGE).destroyBlock(true).explode();
        discard();
    }
    /** Same body-yaw/pitch/roll transformation as the native monitor camera, translated to the nose lens. */
    public Vec3 nosePosition(float partial) {
        Vector3f offset = new Vector3f(0, NOSE_Y, NOSE_Z).rotateZ((float)Math.toRadians(getRoll(partial)))
            .rotateX((float)Math.toRadians(getBodyPitch(partial))).rotateY((float)Math.toRadians(-getYaw(partial)));
        double px = xo + (getX() - xo) * partial, py = yo + (getY() - yo) * partial, pz = zo + (getZ() - zo) * partial;
        return new Vec3(px + offset.x, py + offset.y, pz + offset.z);
    }
    @Override protected boolean enterSignalLostState(ServerPlayer controller) { handleSignalLoss(controller); prepareSignalLostFallTick(); return false; }
    @Override protected void tickSignalLostState(ServerPlayer controller) { prepareSignalLostFallTick(); }
}
