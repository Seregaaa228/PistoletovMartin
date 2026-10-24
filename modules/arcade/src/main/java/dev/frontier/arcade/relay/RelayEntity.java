package dev.frontier.arcade.relay;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.atsuishio.superbwarfare.tools.CustomExplosion;
import dev.frontier.arcade.control.ControlSessions;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Vector3f;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;

/** Native monitor and radio receiver with independent, manually flown fixed-wing movement. */
public final class RelayEntity extends AddonDroneEntity {
    public static final int BATTERY = 96_000, ENERGY_PER_TICK = 4;
    public static final float NOSE_Y = .75f, NOSE_Z = 2.15f;
    private static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(RelayEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Boolean> ACTIVE = SynchedEntityData.defineId(RelayEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> LAUNCHED = SynchedEntityData.defineId(RelayEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> ORBIT = SynchedEntityData.defineId(RelayEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> SPEED = SynchedEntityData.defineId(RelayEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> THROTTLE = SynchedEntityData.defineId(RelayEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> LINK_DISTANCE = SynchedEntityData.defineId(RelayEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> CENTER_X = SynchedEntityData.defineId(RelayEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> CENTER_Y = SynchedEntityData.defineId(RelayEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> CENTER_Z = SynchedEntityData.defineId(RelayEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> FLIGHT_YAW = SynchedEntityData.defineId(RelayEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> FLIGHT_PITCH = SynchedEntityData.defineId(RelayEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> FLIGHT_ROLL = SynchedEntityData.defineId(RelayEntity.class, EntityDataSerializers.FLOAT);
    private RelayOrbitModel flight;
    private boolean airborne, detonated;
    private double launchY;
    private Vec3 previousPosition;

    public RelayEntity(EntityType<? extends DroneEntity> type, Level level) { super(type, level); }
    @Override protected void defineSynchedData() {
        super.defineSynchedData(); entityData.define(OWNER, Optional.empty()); entityData.define(ACTIVE, false);
        entityData.define(LAUNCHED, false); entityData.define(ORBIT, false);
        entityData.define(SPEED, 0f); entityData.define(THROTTLE, .525f); entityData.define(LINK_DISTANCE, 0f);
        entityData.define(CENTER_X, 0f); entityData.define(CENTER_Y, 0f); entityData.define(CENTER_Z, 0f);
        entityData.define(FLIGHT_YAW, 0f); entityData.define(FLIGHT_PITCH, 0f); entityData.define(FLIGHT_ROLL, 0f);
    }
    private RelayOrbitModel flight() { if (flight == null) flight = new RelayOrbitModel(getYRot()); return flight; }
    public UUID ownerId() { return entityData.get(OWNER).orElse(null); }
    public void setOwner(UUID owner) {
        entityData.set(OWNER, Optional.ofNullable(owner));
        if (owner != null) getPersistentData().putUUID("frontierIndustryVehicleOwner", owner);
        else getPersistentData().remove("frontierIndustryVehicleOwner");
    }
    public boolean ownedBy(Player player) { return player != null && player.getUUID().equals(ownerId()); }
    public boolean isLaunched() { return entityData.get(LAUNCHED); }
    public boolean isOrbit() { return entityData.get(ORBIT); }
    public double flightSpeed() { return entityData.get(SPEED); }
    public int throttlePercent() { return Math.round(entityData.get(THROTTLE) * 100); }
    public double signalRange() { return RelayNetwork.RELAY_RANGE; }
    public double flightRange() { return entityData.get(LINK_DISTANCE); }
    public Vec3 orbitCenter() { return new Vec3(entityData.get(CENTER_X), entityData.get(CENTER_Y), entityData.get(CENTER_Z)); }
    public boolean isFlightRunning() {
        return !isRemoved() && !detonated && getHealth() > 0 && getEnergy() >= ENERGY_PER_TICK
            && flight().launched() && !flight().stopped() && ownerOnline();
    }
    /** Flight tickets also cover the unpowered glide until impact/landing. */
    public boolean shouldKeepFlightLoaded() {
        return !isRemoved() && !detonated && ownerOnline() && flight().launched()
            && (isFlightRunning() || !onGround());
    }
    public boolean isRelayActive() { return level().isClientSide ? entityData.get(ACTIVE) : isFlightRunning() && airborne; }
    private Player owner() { return ownerId() == null ? null : level().getPlayerByUUID(ownerId()); }
    private boolean ownerOnline() { Player owner = owner(); return owner != null && owner.isAlive() && !owner.isRemoved(); }
    private Vec3 radioOrigin() {
        Vec3 origin = getOperatorPosition(); Player owner = owner();
        return origin != null ? origin : owner == null ? null : owner.getEyePosition();
    }
    private static boolean monitor(ItemStack stack) {
        return ControlSessions.isMonitor(stack);
    }
    public boolean activeMonitor() {
        Player owner = owner();
        if (owner == null || !owner.isAlive() || owner.isRemoved() || owner.isSpectator() || owner.level() != level()) return false;
        ItemStack stack = owner.getMainHandItem(); CompoundTag tag = stack.getTag();
        return ControlSessions.monitorFor(owner, stack, this) && tag != null && tag.getBoolean("Using") && tag.getBoolean("Linked")
            && entityData.get(DroneEntity.LINKED) && getStringUUID().equals(tag.getString("LinkedDrone"))
            && owner.getStringUUID().equals(entityData.get(DroneEntity.CONTROLLER));
    }
    private boolean radioConnected() {
        Vec3 origin = radioOrigin();
        return origin != null && RelayNetwork.reachable(this, origin);
    }
    private boolean controlAllowed() {
        return activeMonitor() && getEnergy() >= ENERGY_PER_TICK && getHealth() > 0 && !isSignalLost() && radioConnected();
    }
    /** Commands/test fixtures may launch explicitly; item placement never invokes this. */
    public void launch() {
        if (level().isClientSide || flight().launched()) return;
        launchY = getY(); flight().launchManual(getX(), getY(), getZ()); syncFlight();
    }
    public void toggleOrbit() {
        if (level().isClientSide || !controlAllowed() || !flight().launched() || flight().stopped()) return;
        if (flight().isOrbit()) flight().setManual(); else flight().setOrbit(getX(), getY(), getZ());
        syncFlight();
    }
    public void stabilizeFlight() {
        if (!level().isClientSide && controlAllowed() && flight().launched() && !flight().stopped()) { flight().stabilize(); syncFlight(); }
    }
    @Override public float getMaxHealth() { return 20; }
    @Override public ResourceLocation getModelResource() { return new ResourceLocation("frontier_arcade_vehicles", "geo/relay_plane.geo.json"); }
    @Override public ResourceLocation getTextureResource() { return new ResourceLocation("frontier_arcade_vehicles", "textures/entity/relay_plane.png"); }
    @Override public ResourceLocation getAnimationResource() { return new ResourceLocation("frontier_arcade_vehicles", "animations/relay_plane.animation.json"); }
    @Override public net.minecraft.sounds.SoundEvent getEngineSound() { return ru.lavafrai.uncomplicatedfpv.registry.ModSounds.SHAHED136_ENGINE.get(); }
    @Override public float getYaw(float partial) { return Mth.rotLerp(partial, yRotO, getYRot()); }
    @Override public ItemStack getPickResult() { return new ItemStack(RelayContent.ITEM.get()); }
    @Override public int getAmmo() { return 0; }
    @Override public void setAmmo(int count) { super.setAmmo(0); }
    @Override protected Set<String> getAllowedAttachments() { return Set.of(); }
    @Override protected boolean canAddPassenger(Entity passenger) { return false; }
    @Override public void processInput(short keys) { if (!level().isClientSide && controlAllowed()) flight().input(keys); }
    @Override public void mouseInput(double x, double y) { if (!level().isClientSide && controlAllowed()) flight().mouse(x, y); }
    @Override public void registerControllers(software.bernie.geckolib.core.animation.AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new software.bernie.geckolib.core.animation.AnimationController<>(this, "propeller", 0, state -> {
            state.setAnimation(software.bernie.geckolib.core.animation.RawAnimation.begin()
                .thenLoop(engineRunning() ? "animation.relay.flight" : "animation.relay.idle"));
            return software.bernie.geckolib.core.object.PlayState.CONTINUE;
        }));
    }

    @Override public InteractionResult interact(Player player, InteractionHand hand) {
        if (isRemoved()) return InteractionResult.FAIL;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!ownedBy(player)) {
            if (!level().isClientSide) player.displayClientMessage(Component.literal("Это чужой ретранслятор."), true);
            return InteractionResult.FAIL;
        }
        ItemStack held = player.getItemInHand(hand);
        if (monitor(held)) return super.interact(player, hand);
        if (!held.isEmpty() && held.getCapability(ForgeCapabilities.ENERGY).isPresent()) return super.interact(player, hand);
        if (!held.isEmpty()) {
            if (!level().isClientSide) player.displayClientMessage(Component.literal("ПКМ по наземной станции — выбрать аппарат; Space — взлёт, I — круг, F6 — горизонт."), true);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (!onGround() || getDeltaMovement().lengthSqr() > .01 || activeMonitor()) {
            if (!level().isClientSide) player.displayClientMessage(Component.literal("Выключение и подбор доступны неподвижному самолёту на земле; выключи монитор."), true);
            return InteractionResult.FAIL;
        }
        if (player.isShiftKeyDown()) {
            if (!level().isClientSide) {
                ItemStack recovered = new ItemStack(RelayContent.ITEM.get());
                recovered.getOrCreateTag().putInt("RelayEnergy", RelayOrbitModel.boundedEnergy(getEnergy()));
                recovered.getOrCreateTag().putFloat("RelayHealth", Math.max(1, boundedHealth(getHealth())));
                if (!player.getInventory().add(recovered)) {
                    player.displayClientMessage(Component.literal("Освободи место в инвентаре."), true); return InteractionResult.FAIL;
                }
                unlinkForRemoval(); discard();
            }
        } else if (!level().isClientSide) {
            if (flight().launched()) flight().stop();
            entityData.set(ACTIVE, false); setPower(0); resetControlInputs();
            player.displayClientMessage(Component.literal("Двигатель выключен. ПКМ монитором — привязать и пилотировать."), true);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    /** VehicleEntity moves once; the model supplies complete velocity, including its unpowered glide. */
    @Override public void travel() {
        if (level().isClientSide || isRemoved()) return;
        RelayOrbitModel state = flight(); boolean wasLaunched = state.launched();
        boolean powered = getEnergy() >= ENERGY_PER_TICK && ownerOnline() && getHealth() > 0;
        state.tick(getX(), getY(), getZ(), powered, powered && controlAllowed());
        if (!wasLaunched && state.launched()) launchY = getY();
        resetControlInputs();
        if (state.launched()) {
            Vec3 motion = new Vec3(state.velocityX(), state.velocityY(), state.velocityZ());
            if (!level().hasChunkAt(blockPosition().offset((int)Math.signum(motion.x) * 4, 0, (int)Math.signum(motion.z) * 4))) {
                state.stop(); setPower(0); setNoGravity(false); entityData.set(ACTIVE, false); setDeltaMovement(new Vec3(0, -.045, 0));
            } else {
                setDeltaMovement(motion); setNoGravity(true);
                boolean engine = powered && !state.stopped(); setPower(engine ? .07f + .13f * state.throttle() : 0);
                if (engine) consumeEnergy(ENERGY_PER_TICK);
            }
        } else { setPower(0); setNoGravity(false); setDeltaMovement(new Vec3(0, -.045, 0)); }
        setYRot(state.yaw()); setXRot(state.pitch()); setBodyXRot(state.pitch()); setZRot(state.roll()); syncFlight();
    }
    private void syncFlight() {
        RelayOrbitModel state = flight(); entityData.set(LAUNCHED, state.launched()); entityData.set(ORBIT, state.isOrbit());
        entityData.set(SPEED, (float)getDeltaMovement().length()); entityData.set(THROTTLE, state.throttle());
        entityData.set(CENTER_X, (float)state.centerX()); entityData.set(CENTER_Y, (float)state.centerY()); entityData.set(CENTER_Z, (float)state.centerZ());
        entityData.set(FLIGHT_YAW, state.yaw()); entityData.set(FLIGHT_PITCH, state.pitch()); entityData.set(FLIGHT_ROLL, state.roll());
        Vec3 origin = radioOrigin(); entityData.set(LINK_DISTANCE, origin == null ? 0f : (float)position().distanceTo(origin));
        entityData.set(ACTIVE, isRelayActive());
    }
    private void captureOrbit() {
        if (flight().launched() && !flight().stopped() && !flight().isOrbit()) flight().setOrbit(getX(), getY(), getZ());
        flight().input((short)0); resetControlInputs();
    }
    private void loseRadio(ServerPlayer controller) { captureOrbit(); handleSignalLoss(controller); }
    @Override protected boolean enterSignalLostState(ServerPlayer controller) { loseRadio(controller); return false; }
    @Override protected void tickSignalLostState(ServerPlayer controller) { captureOrbit(); }
    @Override public void baseTick() {
        if (!level().isClientSide) {
            // No payload, including native state loaded from old/edited saves.
            setAmmo(0); setFire(false); setCurrentItem(ItemStack.EMPTY);
            entityData.set(DroneEntity.IS_KAMIKAZE, false); entityData.set(DroneEntity.MAX_AMMO, 0); entityData.set(DroneEntity.DISPLAY_ENTITY, "");
            if (activeMonitor() && !radioConnected()) loseRadio(owner() instanceof ServerPlayer player ? player : null);
            else if (!activeMonitor()) captureOrbit();
        }
        Vec3 start = position(); super.baseTick(); if (isRemoved()) return;
        // Restore fixed-wing attitude after native DroneEntity's quadcopter damping.
        setYRot(entityData.get(FLIGHT_YAW)); setXRot(entityData.get(FLIGHT_PITCH));
        setBodyXRot(entityData.get(FLIGHT_PITCH)); setZRot(entityData.get(FLIGHT_ROLL));
        if (level().isClientSide) return;
        if (flight().launched() && getY() > launchY + .75) airborne = true;
        if (flight().launched() && (horizontalCollision || (airborne && verticalCollision && onGround()) || isInWater())) { detonate(position()); return; }
        if (airborne && previousPosition != null && start.distanceToSqr(position()) > .00001) {
            Vec3 from = start.add(0, .35, 0), to = position().add(0, .35, 0);
            var blockHit = level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            Vec3 end = blockHit.getType() == HitResult.Type.BLOCK ? blockHit.getLocation() : to;
            var entityHit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(level(), this, from, end,
                getBoundingBox().expandTowards(start.subtract(position())).inflate(.15), this::impactTarget);
            if (entityHit != null) { detonate(entityHit.getLocation()); return; }
            if (blockHit.getType() == HitResult.Type.BLOCK) { detonate(blockHit.getLocation()); return; }
        }
        previousPosition = start; syncFlight();
    }
    private boolean impactTarget(Entity target) {
        return target != this && target.isAlive() && target.isPickable() && !target.isSpectator()
            && !target.getUUID().equals(ownerId()) && !(target instanceof net.minecraft.world.entity.item.ItemEntity)
            && !(target instanceof net.minecraft.world.entity.projectile.Projectile)
            && !(target instanceof net.minecraft.world.entity.AreaEffectCloud);
    }
    @Override public void hitEntityCrash(Player controller, Entity target) { if (!level().isClientSide && airborne && impactTarget(target)) detonate(position()); }
    @Override public void destroy() { detonate(position()); }
    private void unlinkForRemoval() { ControlSessions.forgetPair(this); handleSignalLoss(owner() instanceof ServerPlayer player ? player : null); }
    public void detonate(Vec3 point) {
        if (detonated || isRemoved() || level().isClientSide) return;
        detonated = true; entityData.set(ACTIVE, false); setPower(0); unlinkForRemoval();
        new CustomExplosion.Builder(this).position(point).attacker(owner()).damage(8f).radius(2f).destroyBlock(false).explode(); discard();
    }
    /** Forward lens using the native monitor's roll/pitch/yaw transformation. */
    public Vec3 nosePosition(float partial) {
        Vector3f offset = new Vector3f(0, NOSE_Y, NOSE_Z).rotateZ((float)Math.toRadians(getRoll(partial)))
            .rotateX((float)Math.toRadians(getBodyPitch(partial))).rotateY((float)Math.toRadians(-getYaw(partial)));
        return new Vec3(xo + (getX() - xo) * partial + offset.x, yo + (getY() - yo) * partial + offset.y,
            zo + (getZ() - zo) * partial + offset.z);
    }

    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag); if (ownerId() != null) tag.putUUID("OwnerUUID", ownerId());
        RelayOrbitModel state = flight(); CompoundTag saved = new CompoundTag(); saved.putInt("Version", 2);
        saved.putDouble("CenterX", state.centerX()); saved.putDouble("CenterY", state.centerY()); saved.putDouble("CenterZ", state.centerZ());
        saved.putFloat("Yaw", state.yaw()); saved.putFloat("Pitch", state.pitch()); saved.putFloat("Roll", state.roll()); saved.putFloat("Speed", state.speed());
        saved.putFloat("Throttle", state.throttle()); saved.putDouble("VelocityX", state.velocityX()); saved.putDouble("VelocityY", state.velocityY()); saved.putDouble("VelocityZ", state.velocityZ());
        saved.putInt("Ticks", state.flightTicks()); saved.putBoolean("Launched", state.launched()); saved.putBoolean("Stopped", state.stopped()); saved.putBoolean("Orbit", state.isOrbit());
        tag.put("RelayFlight", saved); tag.putBoolean("RelayAirborne", airborne); tag.putDouble("RelayLaunchY", launchY);
        tag.putInt("RelayEnergy", RelayOrbitModel.boundedEnergy(getEnergy())); tag.putFloat("RelayHealth", boundedHealth(getHealth()));
    }
    @Override public void readAdditionalSaveData(CompoundTag tag) {
        CompoundTag clean = tag.copy();
        clean.putBoolean("Linked", false); clean.putString("Controller", "none"); clean.putInt("Ammo", 0); clean.putBoolean("KamikazeMode", false);
        for (String key : new String[]{"Item", "MaxAmmo", "DisplayEntity", "DisplayEntityTag", "DisplayData"}) clean.remove(key);
        super.readAdditionalSaveData(clean);
        if (tag.hasUUID("OwnerUUID")) setOwner(tag.getUUID("OwnerUUID"));
        else if (getPersistentData().hasUUID("frontierIndustryVehicleOwner")) setOwner(getPersistentData().getUUID("frontierIndustryVehicleOwner"));
        airborne = tag.getBoolean("RelayAirborne");
        launchY = Double.isFinite(tag.getDouble("RelayLaunchY")) ? tag.getDouble("RelayLaunchY") : getY();
        launchY = Math.max(level().getMinBuildHeight(), Math.min(level().getMaxBuildHeight() - 8, launchY));
        setEnergy(RelayOrbitModel.boundedEnergy(tag.contains("RelayEnergy", 3) ? tag.getInt("RelayEnergy") : getEnergy()));
        setHealth(boundedHealth(tag.contains("RelayHealth", 5) ? tag.getFloat("RelayHealth") : getHealth()));
        if (tag.contains("RelayFlight", 10)) {
            CompoundTag f = tag.getCompound("RelayFlight"); double altitude = f.getDouble("CenterY");
            altitude = Double.isFinite(altitude) ? Math.max(level().getMinBuildHeight() + 8, Math.min(level().getMaxBuildHeight() - 8, altitude)) : getY();
            if (f.getInt("Version") >= 2) {
                flight().restore(f.getDouble("CenterX"), altitude, f.getDouble("CenterZ"), f.getFloat("Yaw"), f.getFloat("Pitch"), f.getFloat("Roll"),
                    f.getFloat("Speed"), f.getFloat("Throttle"), f.getDouble("VelocityX"), f.getDouble("VelocityY"), f.getDouble("VelocityZ"),
                    f.getInt("Ticks"), f.getBoolean("Launched"), f.getBoolean("Stopped"), f.getBoolean("Orbit"));
            } else {
                // v1.5 keeps the saved center and acquires the new orbit through flight, without moving the entity.
                flight().restore(f.getDouble("CenterX"), altitude, f.getDouble("CenterZ"), f.getFloat("Yaw"), f.getFloat("Pitch"),
                    f.getFloat("Roll"), f.getFloat("Speed"), f.getInt("Ticks"), f.getBoolean("Launched"), f.getBoolean("Stopped"));
            }
        }
        previousPosition = null; setAmmo(0); setFire(false); syncFlight(); entityData.set(ACTIVE, false);
    }
    private static float boundedHealth(float health) { return Float.isFinite(health) ? Math.max(0, Math.min(20, health)) : 20; }
}
