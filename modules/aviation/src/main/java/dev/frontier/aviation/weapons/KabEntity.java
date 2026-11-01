package dev.frontier.aviation.weapons;

import com.atsuishio.superbwarfare.client.animation.entity.BasicProjectileAnimationInstance;
import com.atsuishio.superbwarfare.entity.projectile.BasicGeoProjectileEntity;
import com.atsuishio.superbwarfare.entity.projectile.MissileProjectile;
import com.atsuishio.superbwarfare.init.ModItems;
import com.atsuishio.superbwarfare.init.ModSounds;
import com.atsuishio.superbwarfare.tools.RangeTool;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * KAB-500-class guided glide bomb. Unpowered: it leaves the pylon with the
 * carrier's velocity, falls under gravity and can only bend its flight path
 * with tail-fin/wing authority that scales with airspeed. SBW's native
 * shooter fills the target exactly as for a missile (GuideType 1 = a fixed
 * designated point, 0 = a tracked entity), so the pilot designates a map
 * point with the common reticle; no live lock is required.
 */
public class KabEntity extends MissileProjectile implements BasicGeoProjectileEntity {
    /** Plain gravity of an unpowered store (blocks/tick^2); no boost phase. */
    public static final float GRAVITY = 0.045F;
    /** Parasitic drag per tick. */
    public static final double DRAG = 0.99;
    /** Ejector-rack push away from the carrier. */
    public static final double EJECTION = 0.15;
    /** Unguided fall that clears the aircraft before the fins unlock. */
    public static final int SEPARATION_TICKS = 12;
    /** Peak fin authority (degrees of flight-path change per tick) at REFERENCE_SPEED and above. */
    public static final double MAX_TURN_DEGREES = 2.5;
    /** Airspeed (blocks/tick) at which the fins reach full authority. */
    public static final double REFERENCE_SPEED = 1.5;
    /** The fins cannot recover a target further off the flight path than this. */
    public static final double MAX_OFF_AXIS_DEGREES = 75;
    /** Fraction of airspeed bled per degree of path change (induced drag of a lifting turn). */
    public static final double TURN_ENERGY_COST = 0.003;

    private static final String RELEASED = "FrontierKabReleased";
    private final BasicProjectileAnimationInstance<KabEntity> animation;
    private boolean released;

    public KabEntity(EntityType<? extends KabEntity> type, Level level) {
        super(type, level);
        setDamageValue(600.0F);
        setExplosionDamageValue(260.0F);
        setExplosionRadiusValue(16.0F);
        setDurability(1);
        // Fold-out wing deployment (animation.projectile.start) plays once on release.
        animation = level.isClientSide ? new BasicProjectileAnimationInstance<>(this, false) : null;
    }

    @Override protected Item getDefaultItem() { return ModItems.MEDIUM_AERIAL_BOMB.get(); }

    @Override public void tick() {
        if (!level().isClientSide && !released) release();
        super.tick();
        if (isRemoved()) return;
        setDeltaMovement(getDeltaMovement().scale(DRAG));
        if (level() instanceof ServerLevel server && tickCount > SEPARATION_TICKS) steer(server);
        alignToFlightPath();
    }

    /** First server tick: inherit the carrier's motion; a bomb is dropped, not launched. */
    private void release() {
        released = true;
        Entity owner = getOwner();
        Entity carrier = owner == null ? null : owner.getRootVehicle();
        Vec3 base = carrier != null && carrier != owner ? carrier.getDeltaMovement() : Vec3.ZERO;
        if (!finite(base)) base = Vec3.ZERO;
        setDeltaMovement(base.add(0, -EJECTION, 0));
        alignToFlightPath();
    }

    private void steer(ServerLevel server) {
        Vec3 target = getTargetPos();
        Vec3 velocity = getDeltaMovement();
        double speed = velocity.length();
        if (target == null || speed < 1.0E-4) return; // No designation: plain ballistic fall.
        Vec3 desired = target.subtract(position());
        if (getGuideType() == 0) {
            Entity tracked = trackedEntity(server);
            if (tracked != null) {
                Vec3 lead = RangeTool.calculateFiringSolution(position(), target, tracked.getDeltaMovement(), speed, 0.0);
                if (finite(lead) && lead.lengthSqr() > 1.0E-8) desired = lead;
            }
        }
        if (desired.lengthSqr() < 1.0E-6) return;
        // An unpowered glider cannot climb to a point above it: hold level flight at best.
        if (desired.y > 0) desired = new Vec3(desired.x, 0, desired.z);
        if (desired.lengthSqr() < 1.0E-6) return;
        Vec3 forward = velocity.scale(1 / speed);
        Vec3 wanted = desired.normalize();
        double cos = Mth.clamp(forward.dot(wanted), -1, 1);
        double offAxis = Math.toDegrees(Math.acos(cos));
        if (offAxis < 1.0E-3 || offAxis > MAX_OFF_AXIS_DEGREES) return;
        double authority = Mth.clamp((speed / REFERENCE_SPEED) * (speed / REFERENCE_SPEED), 0.3, 1.0);
        double ramp = Mth.clamp((tickCount - SEPARATION_TICKS) / 10.0, 0.0, 1.0);
        double turn = Math.min(offAxis, MAX_TURN_DEGREES * authority * ramp);
        if (turn <= 0) return;
        Vec3 normal = wanted.subtract(forward.scale(cos));
        if (normal.lengthSqr() < 1.0E-10) return;
        normal = normal.normalize();
        double radians = Math.toRadians(turn);
        Vec3 heading = forward.scale(Math.cos(radians)).add(normal.scale(Math.sin(radians)));
        double kept = speed * Math.max(0.0, 1.0 - TURN_ENERGY_COST * turn);
        setDeltaMovement(heading.scale(kept));
    }

    private Entity trackedEntity(ServerLevel server) {
        String id = getTargetUUID();
        if (id == null || id.equals("none")) return null;
        try {
            return server.getEntity(UUID.fromString(id));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** The body weathervanes into the airflow; this is also what the renderer draws. */
    private void alignToFlightPath() {
        Vec3 v = getDeltaMovement();
        if (v.lengthSqr() < 1.0E-8) return;
        setYRot((float) (-Mth.atan2(v.x, v.z) * Mth.RAD_TO_DEG));
        setXRot((float) (-Mth.atan2(v.y, v.horizontalDistance()) * Mth.RAD_TO_DEG));
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    @Override public float getCustomGravity() { return GRAVITY; }

    /** Satellite/inertial guidance: flares are not a target for it. */
    @Override public void distractedByDecoy() { }

    /** No motor: a falling-store whistle instead of a rocket roar. */
    @Override public SoundEvent getSound() { return ModSounds.SHELL_FLY.get(); }
    @Override public float getVolume() { return 0.6F; }
    @Override public float getMaxHealth() { return 40.0F; }

    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean(RELEASED, released);
    }

    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        released = tag.getBoolean(RELEASED);
    }

    @SuppressWarnings("deprecation")
    @Override public ResourceLocation getModel() { return BasicGeoProjectileEntity.DefaultImpls.getModel(this); }
    @SuppressWarnings("deprecation")
    @Override public ResourceLocation getAnimation() { return BasicGeoProjectileEntity.DefaultImpls.getAnimation(this); }
    @Override public BasicProjectileAnimationInstance<?> getAnimationInstance() { return animation; }
    @Override public ResourceLocation getEmissiveTexture() { return BasicGeoProjectileEntity.DefaultImpls.getEmissiveTexture(this); }
    @Override public int getHiddenTicks() { return BasicGeoProjectileEntity.DefaultImpls.getHiddenTicks(this); }
    @Override public int getFlareHiddenTicks() { return BasicGeoProjectileEntity.DefaultImpls.getFlareHiddenTicks(this); }
    @Override public BakedModelInstance getModelInstance() { return BasicGeoProjectileEntity.DefaultImpls.getModelInstance(this); }
}
