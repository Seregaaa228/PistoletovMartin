package dev.frontier.industry.combat;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.UUID;

/** Minecraft arc projectile. Flight advances by ordinary loaded server ticks only. */
public final class BallisticMissileEntity extends Entity {
    private UUID owner;
    private UUID launcher;
    private Vec3 origin = Vec3.ZERO;
    private Vec3 target = Vec3.ZERO;
    private int elapsed;
    private int duration = 45;
    private long launchedAt;
    private long lastTick;
    private boolean configured;

    public BallisticMissileEntity(EntityType<? extends BallisticMissileEntity> type, Level level) { super(type, level); setNoGravity(true); }
    @Override protected void defineSynchedData() { }
    public void configure(UUID owner, UUID launcher, Vec3 origin, Vec3 target) {
        this.owner = owner; this.launcher = launcher; this.origin = origin; this.target = target;
        duration = CombatRules.flightTicks(origin.distanceTo(target)); elapsed = 0;
        launchedAt = level().getGameTime(); lastTick = launchedAt; configured = true; setPos(origin);
    }
    @Nullable public UUID ownerUUID() { return owner; }
    @Nullable public UUID launcherUUID() { return launcher; }
    public Vec3 targetPosition() { return target; }
    public long launchedAt() { return launchedAt; }
    public int elapsedTicks() { return elapsed; }
    public int flightDuration() { return duration; }
    public Vec3 pointAt(int tick) {
        double progress = Mth.clamp((double) tick / duration, 0, 1);
        return origin.lerp(target, progress).add(0, CombatRules.arcOffset(progress, origin.distanceTo(target)), 0);
    }
    @Override public boolean isPickable() { return false; }
    // Vanilla's size-based distance cull (~47 blocks for this hitbox) would hide an
    // in-flight missile well inside its tracking range; let tracking range be the only limit.
    @Override public boolean shouldRenderAtSqrDistance(double distance) { return true; }
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket() { return NetworkHooks.getEntitySpawningPacket(this); }
    @Override public void tick() {
        super.tick();
        if (level().isClientSide) {
            level().addParticle(ParticleTypes.SMOKE, getX(), getY(), getZ(), 0, 0.015, 0); return;
        }
        if (!(level() instanceof ServerLevel server)) return;
        long now = server.getGameTime();
        // A missile never resumes a stale flight after its chunk was unavailable for several ticks.
        if (!configured || owner == null || elapsed >= CombatRules.MAX_FLIGHT_TICKS || now - launchedAt > CombatRules.MAX_FLIGHT_TICKS
                || now - lastTick > 5 || now < lastTick) { discard(); return; }
        lastTick = now;
        Vec3 next = pointAt(++elapsed);
        if (!CombatOperations.loadedSegment(server, position(), next) || !server.getWorldBorder().isWithinBounds(net.minecraft.core.BlockPos.containing(next))) { discard(); return; }
        HitResult hit = server.clip(new ClipContext(position(), next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        Vec3 delta = next.subtract(position()); setDeltaMovement(delta); orient(delta);
        if (hit.getType() != HitResult.Type.MISS) { setPos(hit.getLocation()); impact(server); return; }
        setPos(next);
        if (elapsed >= duration) impact(server);
        else if (elapsed % 2 == 0) server.sendParticles(ParticleTypes.FLAME, getX(), getY(), getZ(), 2, 0.12, 0.12, 0.12, 0.01);
    }
    private void orient(Vec3 movement) {
        if (movement.lengthSqr() < 0.001) return;
        setYRot((float) Math.toDegrees(Math.atan2(movement.x, movement.z)));
        setXRot((float) -Math.toDegrees(Math.atan2(movement.y, movement.horizontalDistance())));
    }
    private void impact(ServerLevel server) {
        if (isRemoved()) return;
        // A single small vanilla explosion; no custom block deletion and no fire.
        server.explode(this, getX(), getY(), getZ(), 3.0F, false, Level.ExplosionInteraction.TNT);
        discard();
    }
    public void intercepted() {
        if (level() instanceof ServerLevel server && !isRemoved()) {
            server.sendParticles(ParticleTypes.EXPLOSION, getX(), getY(), getZ(), 1, 0, 0, 0, 0);
            discard(); // Interception does not detonate the ballistic warhead over friendly ground.
        }
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        owner = CombatOwnership.parse(tag.getString("Owner")); launcher = CombatOwnership.parse(tag.getString("Launcher"));
        origin = vector(tag, "Origin"); target = vector(tag, "Target");
        elapsed = Math.max(0, Math.min(CombatRules.MAX_FLIGHT_TICKS, tag.getInt("Elapsed")));
        duration = Math.max(45, Math.min(520, tag.getInt("Duration")));
        launchedAt = tag.getLong("LaunchedAt"); lastTick = tag.getLong("LastTick");
        configured = owner != null && Double.isFinite(origin.lengthSqr()) && Double.isFinite(target.lengthSqr())
                && origin.distanceToSqr(target) <= (double) (CombatRules.MAX_RANGE + 8) * (CombatRules.MAX_RANGE + 8);
    }
    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        if (owner != null) tag.putString("Owner", owner.toString()); if (launcher != null) tag.putString("Launcher", launcher.toString());
        vector(tag, "Origin", origin); vector(tag, "Target", target); tag.putInt("Elapsed", elapsed); tag.putInt("Duration", duration);
        tag.putLong("LaunchedAt", launchedAt); tag.putLong("LastTick", lastTick);
    }
    static Vec3 vector(CompoundTag tag, String key) { return new Vec3(tag.getDouble(key + "X"), tag.getDouble(key + "Y"), tag.getDouble(key + "Z")); }
    static void vector(CompoundTag tag, String key, Vec3 value) { tag.putDouble(key + "X", value.x); tag.putDouble(key + "Y", value.y); tag.putDouble(key + "Z", value.z); }
}
