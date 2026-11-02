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
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

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
    public static final float GRAVITY = 0.08F;
    /**
     * Parasitic drag per tick. SBW's projectile move applies none of its own, so this is
     * the only drag: a dense 500 kg store keeps most of its speed (terminal ~27 b/t).
     */
    public static final double DRAG = 0.997;
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
    /**
     * Share of gravity the deployed glide wings carry at REFERENCE_SPEED and above. Below
     * 1 the store still always sinks (never climbs), but trades height for range instead
     * of falling short of a designated point a few hundred metres away.
     */
    public static final double WING_LIFT = 0.5;
    /** The whistle (kab_whistle, 3.2 s) starts this many ticks before the expected impact. */
    private static final int WHISTLE_LEAD_TICKS = 64;

    private static final String RELEASED = "FrontierKabReleased";
    private static final String VARIANT = "FrontierKabVariant";
    /** Life limit (3 min): far beyond any glide from build height, short enough to clean up strays. */
    public static final int LIFE_TICKS = 3600;
    /** Players this far from the impact see the fireball/smoke column and hear the boom. */
    private static final double FAR_EFFECT_RANGE = 1024;
    private final BasicProjectileAnimationInstance<KabEntity> animation;
    private boolean released;
    private boolean whistling;
    private boolean flying;
    private int variant;

    public KabEntity(EntityType<? extends KabEntity> type, Level level) {
        super(type, level);
        setDurability(1);
        // SBW detonates every projectile at its life limit (default 400 ticks = 20 s) wherever
        // it is; a high, long glide takes longer, so the КАБ only goes off on impact.
        setLife(LIFE_TICKS);
        setVariant(0);
        // Fold-out wing deployment (animation.projectile.start) plays once on release.
        animation = level.isClientSide ? new BasicProjectileAnimationInstance<>(this, false) : null;
    }

    @Override protected Item getDefaultItem() { return ModItems.MEDIUM_AERIAL_BOMB.get(); }

    /** КАБ class (index into PylonOrdnance.KABS): sets the blast. */
    public void setVariant(int index) {
        variant = Mth.clamp(index, 0, PylonOrdnance.KABS.size() - 1);
        PylonOrdnance.Kab kab = PylonOrdnance.KABS.get(variant);
        setDamageValue(kab.hitDamage());
        setExplosionDamageValue(kab.blastDamage());
        setExplosionRadiusValue(kab.radius());
    }

    public int variant() { return variant; }

    @Override public void causeExplode(Vec3 at) {
        super.causeExplode(at);
        if (level() instanceof ServerLevel server) KabImpact.broadcast(server, at, variant, FAR_EFFECT_RANGE);
    }

    @Override public void tick() {
        if (!level().isClientSide && !released) release();
        if (level().isClientSide && !flying) {
            flying = true;
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.frontier.aviation.client.KabFlySound.startAirflow(this));
        }
        if (level().isClientSide && !whistling && impactWithin(WHISTLE_LEAD_TICKS)) {
            whistling = true;
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.frontier.aviation.client.KabFlySound.startWhistle(this));
        }
        super.tick();
        if (isRemoved()) return;
        setDeltaMovement(getDeltaMovement().scale(DRAG));
        // Both sides: the client predicts SBW fast projectiles itself (missile-fix ignores
        // their server move packets), so without the same lift and steering it would draw
        // the КАБ falling short of where it really flies.
        if (tickCount > SEPARATION_TICKS) {
            steer();
            glide();
        }
        alignToFlightPath();
    }

    /** Rough time to ground from the current sink rate, for the incoming whistle. */
    private boolean impactWithin(int ticks) {
        double sink = -getDeltaMovement().y;
        if (sink <= 1.0E-3) return false;
        int ground = level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, getBlockX(), getBlockZ());
        return (getY() - ground) / sink <= ticks;
    }

    /** Wing lift once the wings are out; scales with airspeed and ramps in with the fins. */
    private void glide() {
        if (getTargetPos() == null) return;
        Vec3 velocity = getDeltaMovement();
        double ramp = Mth.clamp((tickCount - SEPARATION_TICKS) / 10.0, 0.0, 1.0);
        double lift = GRAVITY * WING_LIFT * Mth.clamp(velocity.length() / REFERENCE_SPEED, 0.0, 1.0) * ramp;
        setDeltaMovement(velocity.add(0, lift, 0));
    }

    /**
     * First server tick: inherit the carrier's motion; a bomb is dropped, not launched.
     * If the owner is no longer aboard (bailed out, or the tablet launch already set the
     * carrier's velocity at spawn), keep the velocity the store was released with.
     */
    private void release() {
        released = true;
        Entity owner = getOwner();
        Entity carrier = owner == null ? null : owner.getRootVehicle();
        Vec3 base = carrier != null && carrier != owner ? carrierVelocity(carrier) : getDeltaMovement();
        if (!finite(base)) base = Vec3.ZERO;
        setDeltaMovement(base.add(0, -EJECTION, 0));
        alignToFlightPath();
    }

    private void steer() {
        Vec3 target = getTargetPos();
        Vec3 velocity = getDeltaMovement();
        double speed = velocity.length();
        if (target == null || speed < 1.0E-4) return; // No designation: plain ballistic fall.
        Vec3 desired = target.subtract(position());
        if (getGuideType() == 0) {
            Entity tracked = level() instanceof ServerLevel server ? trackedEntity(server) : null;
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

    /**
     * The carrier's real velocity. A piloted aircraft can be moved by position (flight
     * control) while its server-side delta movement stays small, so take whichever is
     * larger: the delta movement or the distance actually travelled this tick.
     */
    public static Vec3 carrierVelocity(Entity carrier) {
        Vec3 moved = carrier.position().subtract(carrier.xo, carrier.yo, carrier.zo);
        Vec3 delta = carrier.getDeltaMovement();
        return finite(moved) && moved.lengthSqr() > delta.lengthSqr() ? moved : delta;
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    @Override public float getCustomGravity() { return GRAVITY; }

    /** Satellite/inertial guidance: flares are not a target for it. */
    @Override public void distractedByDecoy() { }

    /**
     * Own ordnance in the air (a second КАБ, a Storm Shadow from the same pilot) is not a
     * target: SBW's path scan would otherwise detonate the bomb on it mid-air.
     */
    @Override public void onHitEntity(EntityHitResult hit) {
        if (hit.getEntity() instanceof Projectile other && other.getOwner() != null && other.getOwner() == getOwner()) return;
        super.onHitEntity(hit);
    }

    /** No motor. SBW's own fly sound is muted: KabFlySound plays a timed incoming whistle instead. */
    @Override public SoundEvent getSound() { return ModSounds.SHELL_FLY.get(); }
    @Override public float getVolume() { return 0.0F; }
    @Override public float getMaxHealth() { return 40.0F; }

    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean(RELEASED, released);
        tag.putInt(VARIANT, variant);
    }

    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        released = tag.getBoolean(RELEASED);
        setVariant(tag.getInt(VARIANT));
        setLife(LIFE_TICKS); // Bombs saved with SBW's 400-tick limit.
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
