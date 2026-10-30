package dev.frontier.industry.combat;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.UUID;

/** A visible homing interceptor. Only a Patriot-issued interceptor may hit our ballistic entity. */
public final class InterceptorEntity extends Entity {
    private UUID owner;
    private UUID station;
    private UUID target;
    private LauncherEntity.Kind kind = LauncherEntity.Kind.S300;
    private long launchedAt;
    private long lastTick;
    private Vec3 origin = Vec3.ZERO;
    private int elapsed;
    public InterceptorEntity(EntityType<? extends InterceptorEntity> type, Level level) { super(type, level); setNoGravity(true); }
    @Override protected void defineSynchedData() { }
    public void configure(LauncherEntity source, Entity target) {
        owner = source.ownerUUID(); station = source.getUUID(); this.target = target.getUUID(); kind = source.kind();
        launchedAt = level().getGameTime(); lastTick = launchedAt; origin = source.muzzle(); setPos(origin);
    }
    @Nullable public UUID ownerUUID() { return owner; }
    @Nullable public UUID targetUUID() { return target; }
    public LauncherEntity.Kind launcherKind() { return kind; }
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket() { return NetworkHooks.getEntitySpawningPacket(this); }
    // Vanilla's size-based distance cull (~28 blocks for this hitbox) would hide an
    // interceptor well inside its own 256-block flight radius; tracking range is the only limit.
    @Override public boolean shouldRenderAtSqrDistance(double distance) { return true; }
    @Override public void tick() {
        super.tick();
        if (level().isClientSide) { level().addParticle(ParticleTypes.END_ROD, getX(), getY(), getZ(), 0, 0, 0); return; }
        if (!(level() instanceof ServerLevel server)) return;
        long now = server.getGameTime();
        if (++elapsed > CombatRules.INTERCEPTOR_LIFETIME || owner == null || target == null || now - launchedAt > CombatRules.INTERCEPTOR_LIFETIME
                || now - lastTick > 5 || now < lastTick || position().distanceToSqr(origin) > 256D * 256D) { discard(); return; }
        lastTick = now;
        Entity victim = server.getEntity(target);
        if (victim == null || !victim.isAlive() || !server.hasChunkAt(victim.blockPosition()) || !CombatOwnership.hostile(server, owner, victim)
                || (victim instanceof BallisticMissileEntity && kind != LauncherEntity.Kind.PATRIOT)) { discard(); return; }
        Vec3 aim = victim.getBoundingBox().getCenter();
        Vec3 movement = aim.subtract(position());
        double speed = kind == LauncherEntity.Kind.PATRIOT ? 7.5 : 6.0;
        if (movement.lengthSqr() > speed * speed) movement = movement.normalize().scale(speed);
        Vec3 next = position().add(movement);
        if (!CombatOperations.loadedSegment(server, position(), next) || !server.getWorldBorder().isWithinBounds(net.minecraft.core.BlockPos.containing(next))) { discard(); return; }
        HitResult obstruction = server.clip(new ClipContext(position(), next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        if (obstruction.getType() != HitResult.Type.MISS) { discard(); return; }
        if (victim.getBoundingBox().inflate(1.6).contains(position()) || victim.getBoundingBox().inflate(1.6).clip(position(), next).isPresent()) {
            if (victim instanceof BallisticMissileEntity missile) missile.intercepted();
            else victim.hurt(server.damageSources().explosion(this, server.getServer().getPlayerList().getPlayer(owner)), 48.0F);
            Entity source = station == null ? null : server.getEntity(station);
            if (source instanceof LauncherEntity launcher && owner.equals(launcher.ownerUUID())) launcher.recordInterception();
            server.sendParticles(ParticleTypes.EXPLOSION, victim.getX(), victim.getY(), victim.getZ(), 1, 0, 0, 0, 0);
            discard(); return;
        }
        setDeltaMovement(movement); setYRot((float) Math.toDegrees(Math.atan2(movement.x, movement.z)));
        setXRot((float) -Math.toDegrees(Math.atan2(movement.y, movement.horizontalDistance()))); setPos(next);
        if (elapsed % 2 == 0) server.sendParticles(ParticleTypes.SMOKE, getX(), getY(), getZ(), 2, 0.1, 0.1, 0.1, 0.01);
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        owner = CombatOwnership.parse(tag.getString("Owner")); station = CombatOwnership.parse(tag.getString("Station")); target = CombatOwnership.parse(tag.getString("Target"));
        kind = LauncherEntity.Kind.values()[Math.max(0, Math.min(3, tag.getInt("Kind")))];
        launchedAt = tag.getLong("LaunchedAt"); lastTick = tag.getLong("LastTick");
        elapsed = Math.max(0, Math.min(CombatRules.INTERCEPTOR_LIFETIME, tag.getInt("Elapsed"))); origin = BallisticMissileEntity.vector(tag, "Origin");
    }
    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        if (owner != null) tag.putString("Owner", owner.toString()); if (station != null) tag.putString("Station", station.toString()); if (target != null) tag.putString("Target", target.toString());
        tag.putInt("Kind", kind.ordinal()); tag.putLong("LaunchedAt", launchedAt); tag.putLong("LastTick", lastTick); tag.putInt("Elapsed", elapsed);
        BallisticMissileEntity.vector(tag, "Origin", origin);
    }
}
