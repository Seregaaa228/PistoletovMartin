package dev.frontier.arcade.airdefense;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * A surface-to-air missile in flight. The server flies it and decides the fuse; the client flies
 * the same {@link SamFlight} model toward the same target between sparse position updates, so
 * only the spawn, a few corrections and one burst message cross the network.
 *
 * <p>It never loads chunks: in unloaded terrain it is gone; through loaded terrain that is not
 * ticking (between a site and an aircraft far apart) its launching site steps it. Missiles are not
 * saved: a missile in a chunk that unloads is simply gone.</p>
 */
public final class SamMissileEntity extends Entity implements IEntityAdditionalSpawnData {
    private static final EntityDataAccessor<Integer> TARGET = SynchedEntityData.defineId(SamMissileEntity.class, EntityDataSerializers.INT);
    private static final ResourceLocation FLARE = new ResourceLocation("superbwarfare", "flare_decoy");

    private SamMissile type = SamMissile.V601;
    private SamFlight.State state;
    private UUID targetId, siteId, ownerId;
    /** The target this missile counts against in the shared engagement table (kept through decoys). */
    private UUID engagedId;
    private Entity target;
    private Vec3 targetPos, targetVel = Vec3.ZERO, endGameOffset;
    private double targetAccel, lastDistance = Double.MAX_VALUE;
    private int lostTicks;
    /** Game tick of the last server step: the launching site may also step a missile it sees frozen. */
    private long lastStep = Long.MIN_VALUE;
    private final Set<Integer> seenDecoys = new HashSet<>();
    // Client: last server position, blended into the local flight.
    private Vec3 correction;
    private boolean launchShown;

    public SamMissileEntity(EntityType<? extends SamMissileEntity> entityType, Level level) {
        super(entityType, level);
        noPhysics = true;
        noCulling = true;
    }

    /** A new missile leaving {@code site}'s rail at {@code from} along {@code dir}. */
    public static SamMissileEntity launch(ServerLevel level, SamSiteEntity site, SamMissile type, Entity target, Vec3 from, Vec3 dir) {
        SamMissileEntity missile = AirDefense.MISSILE.get().create(level);
        if (missile == null) return null;
        missile.type = type;
        missile.state = new SamFlight.State(from, dir, 0.6);
        missile.targetId = target.getUUID();
        missile.engagedId = target.getUUID();
        missile.target = target;
        missile.siteId = site.getUUID();
        missile.ownerId = site.ownerId();
        missile.entityData.set(TARGET, target.getId());
        missile.setPos(from);
        missile.orient();
        return missile;
    }

    public SamMissile missileType() { return type; }
    public UUID siteId() { return siteId; }
    public UUID targetId() { return targetId; }
    public int age() { return state == null ? 0 : state.age; }
    public Vec3 direction() { return state == null ? Vec3.ZERO : state.dir; }
    public boolean burning() { return state != null && type.burning(state.age); }

    @Override protected void defineSynchedData() { entityData.define(TARGET, -1); }

    @Override public boolean isPickable() { return false; }
    @Override public boolean isAttackable() { return false; }
    @Override public boolean shouldBeSaved() { return false; }
    @Override public boolean shouldRenderAtSqrDistance(double distance) { return distance < 640 * 640; }

    @Override
    public void tick() {
        if (state == null) { discard(); return; }
        if (!level().isClientSide) {
            long now = level().getGameTime();
            if (now == lastStep) return;
            lastStep = now;
        }
        xo = getX(); yo = getY(); zo = getZ();
        if (level().isClientSide) { clientTick(); return; }
        ServerLevel level = (ServerLevel) level();
        Entity victim = resolveTarget(level);
        Vec3 start = state.pos, previousTarget = targetPos;
        if (victim != null) {
            Vec3 now = victim.getBoundingBox().getCenter();
            if (targetPos != null) {
                Vec3 velocity = now.subtract(targetPos);
                Vec3 accel = velocity.subtract(targetVel);
                // The part of the acceleration across the flight path is the manoeuvre.
                double along = velocity.lengthSqr() > 1e-6 ? accel.dot(velocity.normalize()) : 0;
                double lateral = Math.sqrt(Math.max(0, accel.lengthSqr() - along * along));
                targetAccel = targetAccel * 0.6 + lateral * 0.4;
                targetVel = velocity;
            }
            targetPos = now;
            if (state.age % 2 == 0 && now.distanceTo(state.pos) < 200) decoys(level, victim);
            if (endGameOffset == null && now.distanceTo(state.pos) < Math.max(state.speed, 1) * SamFlight.END_GAME_TICKS) {
                double miss = SamFlight.terminalError(type, random.nextGaussian(), targetAccel);
                Vec3 los = now.subtract(state.pos).normalize();
                Vec3 a = los.cross(Math.abs(los.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize(), b = a.cross(los);
                double angle = random.nextDouble() * Math.PI * 2;
                endGameOffset = a.scale(Math.cos(angle) * miss).add(b.scale(Math.sin(angle) * miss));
            }
            lostTicks = 0;
        } else if (++lostTicks > 20) {
            burst(level, state.pos, null, 0);
            return;
        }
        SamFlight.step(state, type, victim == null ? null : targetPos, targetVel, endGameOffset);
        if (victim != null && previousTarget != null) {
            double[] pass = SamFlight.closestApproach(start, state.pos, previousTarget, targetPos);
            double radius = 0.5 * Math.min(victim.getBbWidth(), victim.getBbHeight());
            double miss = Math.max(0, pass[0] - radius);
            if (miss <= type.fuseRadius) {
                burst(level, start.add(state.pos.subtract(start).scale(pass[1])), victim, miss);
                return;
            }
            // Passed it outside the fuse: the end-game error is spent, a re-attack aims true again.
            if (pass[0] > lastDistance && lastDistance < type.fuseRadius * 4) endGameOffset = null;
            lastDistance = pass[0];
        }
        if (!terrainClear(level, start, state.pos) || SamFlight.spent(type, state)) {
            burst(level, state.pos, null, 0);
            return;
        }
        if (!inLoadedTerrain(level)) { discard(); return; }
        setPos(state.pos);
        setDeltaMovement(state.velocity());
        orient();
    }

    /** Any removal, chunk unload included, frees the target in the shared engagement table. */
    @Override
    public void onRemovedFromWorld() {
        if (!level().isClientSide && engagedId != null) {
            SamSiteEntity.released(engagedId);
            engagedId = null;
        }
        super.onRemovedFromWorld();
    }

    private Entity resolveTarget(ServerLevel level) {
        if (target == null || target.isRemoved()) target = targetId == null ? null : level.getEntity(targetId);
        return target != null && target.isAlive() ? target : null;
    }

    /** Fresh flares near the target can each seduce the missile once. */
    private void decoys(ServerLevel level, Entity victim) {
        for (Entity decoy : level.getEntities(victim, victim.getBoundingBox().inflate(24),
                entity -> FLARE.equals(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType())))) {
            if (!seenDecoys.add(decoy.getId())) continue;
            if (random.nextDouble() < type.decoyChance) {
                target = decoy;
                targetId = decoy.getUUID();
                entityData.set(TARGET, decoy.getId());
                // The aircraft's track says nothing about the flare's: start the flare's afresh.
                endGameOffset = null;
                targetPos = null;
                targetVel = Vec3.ZERO;
                targetAccel = 0;
                lastDistance = Double.MAX_VALUE;
                return;
            }
        }
    }

    /** Ground and buildings stop a missile; only loaded chunks are read. */
    private boolean terrainClear(ServerLevel level, Vec3 from, Vec3 to) {
        BlockPos end = BlockPos.containing(to);
        if (!level.hasChunkAt(end) || !level.hasChunkAt(BlockPos.containing(from))) return true;
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, end.getX(), end.getZ());
        if (to.y > ground + 2 && from.y > ground + 2) return true;
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
    }

    /**
     * A missile flies only through loaded terrain and never asks for chunks: entering an unloaded
     * chunk ends it. Where the terrain is loaded but not ticking (between a site and an aircraft far
     * apart), the launching site steps it ({@link SamSiteEntity}).
     */
    private boolean inLoadedTerrain(ServerLevel level) {
        Vec3 ahead = state.pos.add(state.dir.scale(state.speed));
        return level.hasChunkAt(BlockPos.containing(state.pos)) && level.hasChunkAt(BlockPos.containing(ahead));
    }

    private void burst(ServerLevel level, Vec3 at, Entity victim, double miss) {
        Entity owner = ownerId == null ? null : level.getPlayerByUUID(ownerId);
        var source = level.damageSources().explosion(this, owner);
        if (victim != null) {
            SamDamage.apply(victim, SamFlight.damageFraction(type, miss), source);
            // Fragments reach other air contacts close by (a drone swarm, a wingman).
            for (Entity near : AirPicture.contacts(level)) {
                if (near == victim || SamIff.friendly(level, ownerId, near)) continue;
                double d = near.getBoundingBox().getCenter().distanceTo(at);
                if (d <= type.fuseRadius * 2) SamDamage.apply(near, SamFlight.damageFraction(type, type.fuseRadius * 0.5 + d * 0.5) * 0.5, source);
            }
            if (siteId != null && level.getEntity(siteId) instanceof SamSiteEntity site) site.recordBurst(victim, miss <= type.fuseRadius);
        }
        SamNetwork.burst(level, at, type, victim != null);
        discard();
    }

    private void orient() {
        Vec3 d = state.dir;
        setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
        setXRot((float) -Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, d.y)))));
        yRotO = getYRot();
        xRotO = getXRot();
    }

    // ------------------------------------------------------------------ client

    private void clientTick() {
        if (!launchShown) {
            launchShown = true;
            AirDefense.clientEffects().launch(this);
        }
        Entity aim = level().getEntity(entityData.get(TARGET));
        Vec3 tPos = aim == null ? null : aim.getBoundingBox().getCenter();
        Vec3 tVel = aim == null ? Vec3.ZERO : aim.position().subtract(aim.xo, aim.yo, aim.zo);
        SamFlight.step(state, type, tPos, tVel);
        if (correction != null) {
            // Pull the local flight toward the server's, without snapping.
            state.pos = state.pos.add(correction.subtract(state.pos).scale(0.5));
            correction = correction.add(state.velocity());
            if (correction.distanceToSqr(state.pos) < 0.01) correction = null;
        }
        setPos(state.pos);
        orient();
        AirDefense.clientEffects().trail(this);
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        if (state == null) { setPos(x, y, z); return; }
        // The packet is a little old by now: carry it forward one tick.
        correction = new Vec3(x, y, z).add(state.velocity());
    }

    // ------------------------------------------------------------------ spawn and save

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() { return NetworkHooks.getEntitySpawningPacket(this); }

    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        // A summoned missile has no flight until launched: send a harmless one (it discards itself).
        if (state == null) state = new SamFlight.State(position(), new Vec3(0, 1, 0), 0);
        buffer.writeByte(type.ordinal());
        buffer.writeDouble(state.pos.x); buffer.writeDouble(state.pos.y); buffer.writeDouble(state.pos.z);
        buffer.writeFloat((float) state.dir.x); buffer.writeFloat((float) state.dir.y); buffer.writeFloat((float) state.dir.z);
        buffer.writeFloat((float) state.speed);
        buffer.writeVarInt(state.age);
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        type = SamMissile.of(buffer.readByte());
        Vec3 pos = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        Vec3 dir = new Vec3(buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
        state = new SamFlight.State(pos, dir.lengthSqr() > 1e-6 ? dir : new Vec3(0, 1, 0), buffer.readFloat());
        state.age = buffer.readVarInt();
        setPos(pos);
        orient();
    }

    @Override protected void readAdditionalSaveData(CompoundTag tag) { }
    @Override protected void addAdditionalSaveData(CompoundTag tag) { }
}
