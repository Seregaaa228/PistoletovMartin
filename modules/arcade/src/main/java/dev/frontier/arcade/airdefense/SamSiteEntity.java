package dev.frontier.arcade.airdefense;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * A SAM complex: SBW vehicle (seats, health, wreck, driving, inventory) plus its own radar and
 * launcher. The radar looks every {@link #SCAN_INTERVAL} ticks at the level's short list of air
 * contacts ({@link AirPicture}), so an idle site costs next to nothing. In AUTO it engages only
 * contacts hostile to its owner by the pack's diplomacy; in MANUAL it waits for the crew. In both
 * modes a crew click on a contact engages that contact next.
 */
public final class SamSiteEntity extends VehicleEntity {
    public static final int SCAN_INTERVAL = 10;
    public static final byte AUTO = 0, MANUAL = 1;
    private static final EntityDataAccessor<Byte> MODE = SynchedEntityData.defineId(SamSiteEntity.class, EntityDataSerializers.BYTE);
    /** Ready rounds per rack, 8 bits each. */
    private static final EntityDataAccessor<Integer> READY = SynchedEntityData.defineId(SamSiteEntity.class, EntityDataSerializers.INT);
    /** 0..100 percent of the launch pack raised. */
    private static final EntityDataAccessor<Byte> DEPLOY = SynchedEntityData.defineId(SamSiteEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> AIM_YAW = SynchedEntityData.defineId(SamSiteEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> AIM_PITCH = SynchedEntityData.defineId(SamSiteEntity.class, EntityDataSerializers.FLOAT);
    /** Missiles in flight per target UUID, across every site: two sites do not shoot the same target at once. */
    private static final Map<UUID, Integer> ENGAGED = new HashMap<>();

    /** One radar track. */
    static final class Track {
        final Entity entity;
        final SamEnvelope.Kind kind;
        Vec3 position, velocity = Vec3.ZERO;
        double range, altitude;
        int heldTicks;
        byte relation; // 0 hostile, 1 unknown, 2 friendly
        long seen;

        Track(Entity entity, SamEnvelope.Kind kind) { this.entity = entity; this.kind = kind; }
    }

    private final SamSystem system;
    private UUID owner;
    private boolean supplied;
    private final int[] reload;
    private final Map<Integer, Track> tracks = new HashMap<>();
    private final List<SamMissileEntity> inFlight = new ArrayList<>();
    private int designated = -1;
    private long designatedAt;
    /** An unanswered crew designation lapses after this long, so AUTO resumes. */
    private static final int DESIGNATION_TICKS = 200;
    private long lastLaunch = Long.MIN_VALUE / 2;
    private int launches, kills;

    public SamSiteEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.system = AirDefense.system(type);
        this.reload = new int[system.racks.size()];
        noCulling = true;
    }

    public SamSystem system() { return system; }
    public UUID ownerId() { return owner; }
    public void setOwner(UUID owner) { this.owner = owner; }
    public byte mode() { return entityData.get(MODE); }
    public int deployPercent() { return entityData.get(DEPLOY); }
    public float aimYaw() { return entityData.get(AIM_YAW); }
    public float aimPitch() { return entityData.get(AIM_PITCH); }
    public int designated() { return designated; }
    public int inFlight() { return inFlight.size(); }
    public int reloadPercent(int rack) {
        return rack < reload.length ? Math.min(100, reload[rack] * 100 / Math.max(1, system.racks.get(rack).reloadTicks())) : 0;
    }

    public int ready(int rack) { return (entityData.get(READY) >>> (rack * 8)) & 0xFF; }

    private void setReady(int rack, int value) {
        int packed = entityData.get(READY) & ~(0xFF << (rack * 8));
        entityData.set(READY, packed | (Math.max(0, Math.min(255, value)) << (rack * 8)));
    }

    private int[] readyAll() {
        int[] out = new int[system.racks.size()];
        for (int i = 0; i < out.length; i++) out[i] = ready(i);
        return out;
    }

    public boolean deployed() { return deployPercent() >= 100; }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(MODE, AUTO);
        entityData.define(READY, 0);
        entityData.define(DEPLOY, (byte) 0);
        entityData.define(AIM_YAW, 0F);
        entityData.define(AIM_PITCH, 0F);
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void baseTick() {
        super.baseTick();
        if (!(level() instanceof ServerLevel level)) return;
        if (!supplied) supply();
        if (owner == null) claimByRider();
        inFlight.removeIf(missile -> !missile.isAlive());
        // Our missiles in loaded but not ticking terrain (far from players) are stepped from here.
        for (SamMissileEntity missile : List.copyOf(inFlight))
            if (!level.isPositionEntityTicking(missile.blockPosition())) missile.tick();
        if (isWreck() || getHealth() <= 0) return;
        // A designation lapses when the crew leaves or after a while unanswered.
        if (designated >= 0 && (crew().isEmpty() || level.getGameTime() - designatedAt > DESIGNATION_TICKS)) designated = -1;
        deploy();
        reload();
        if ((tickCount + getId()) % SCAN_INTERVAL == 0) {
            scan(level);
            SamNetwork.sendPicture(this, tracks.values());
        }
        engage(level);
        slew();
    }

    /** First tick: arrives fuelled and loaded, like the other deployable vehicles. */
    private void supply() {
        supplied = true;
        setEnergy(getMaxEnergy());
        for (int i = 0; i < system.racks.size(); i++) setReady(i, system.racks.get(i).capacity());
    }

    /**
     * Stocked from an older launcher's magazine instead of the factory fill: the first rack takes
     * up to its capacity, the rest is returned for the hold. Fuel is still topped up.
     */
    public int restock(int rounds) {
        supplied = true;
        setEnergy(getMaxEnergy());
        int capacity = system.racks.get(0).capacity(), loaded = Math.max(0, Math.min(capacity, rounds));
        setReady(0, loaded);
        for (int i = 1; i < system.racks.size(); i++) setReady(i, 0);
        return Math.max(0, rounds - loaded);
    }

    private void claimByRider() {
        for (Entity passenger : getPassengers()) if (passenger instanceof ServerPlayer player) { owner = player.getUUID(); return; }
    }

    /** Pack up while moving, raise when stopped; systems without deployment are always ready. */
    private void deploy() {
        int percent = deployPercent();
        if (system.deployTicks <= 0) {
            if (percent != 100) entityData.set(DEPLOY, (byte) 100);
            return;
        }
        boolean moving = getDeltaMovement().horizontalDistanceSqr() > 0.0025;
        int step = Math.max(1, 100 / system.deployTicks);
        int next = moving ? Math.max(0, percent - step * 3) : Math.min(100, percent + step);
        if (next != percent) entityData.set(DEPLOY, (byte) next);
    }

    private void reload() {
        for (int rack = 0; rack < system.racks.size(); rack++) {
            var spec = system.racks.get(rack);
            if (ready(rack) >= spec.capacity()) { reload[rack] = 0; continue; }
            Item ammo = spec.ammo().resolve();
            int slot = ammo == null ? -1 : findItem(ammo);
            if (slot < 0) { reload[rack] = 0; continue; }
            if (++reload[rack] >= spec.reloadTicks()) {
                reload[rack] = 0;
                getItems().get(slot).shrink(1);
                setChanged();
                setReady(rack, ready(rack) + 1);
            }
        }
    }

    private int findItem(Item item) {
        var items = getItems();
        for (int i = 0; i < items.size(); i++) if (items.get(i).is(item)) return i;
        return -1;
    }

    // ------------------------------------------------------------------ radar

    private Vec3 radarPoint() { return position().add(0, 4, 0); }

    private void scan(ServerLevel level) {
        Vec3 radar = radarPoint();
        long now = level.getGameTime();
        double reach = system.detectRange;
        for (Entity contact : AirPicture.contacts(level)) {
            if (contact.getRootVehicle() == getRootVehicle() || contact.distanceToSqr(radar) > reach * reach || downed(contact)) continue;
            var kind = AirPicture.cachedKind(level, contact);
            if (kind == null) continue;
            Vec3 at = contact.getBoundingBox().getCenter();
            double range = at.distanceTo(radar), altitude = altitude(level, at);
            if (!SamEnvelope.visible(system, kind, range, altitude) || !lineOfSight(level, radar, at)) continue;
            Track track = tracks.computeIfAbsent(contact.getId(), id -> new Track(contact, kind));
            if (track.seen > 0 && track.position != null) {
                long dt = Math.max(1, now - track.seen);
                track.velocity = at.subtract(track.position).scale(1.0 / dt);
                track.heldTicks += (int) dt;
            }
            track.position = at;
            track.range = range;
            track.altitude = altitude;
            track.seen = now;
            track.relation = SamIff.hostile(level, owner, contact) ? (byte) 0 : SamIff.friendly(level, owner, contact) ? (byte) 2 : (byte) 1;
        }
        // A track not refreshed this scan is lost (out of range, masked, gone).
        tracks.values().removeIf(track -> track.seen != now || !track.entity.isAlive());
        if (designated >= 0 && !tracks.containsKey(designated)) designated = -1;
    }

    /** Height above the ground under the contact; never loads a chunk. */
    private double altitude(ServerLevel level, Vec3 at) {
        BlockPos pos = BlockPos.containing(at);
        if (!level.hasChunkAt(pos)) return at.y - getY();
        return at.y - level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
    }

    /** Terrain and buildings mask a contact. Samples every two blocks, only in loaded chunks. */
    private static boolean lineOfSight(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        int steps = (int) (length / 2);
        if (steps <= 1) return true;
        Vec3 step = delta.scale(1.0 / steps);
        Vec3 p = from;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 1; i < steps - 1; i++) {
            p = p.add(step);
            pos.set(p.x, p.y, p.z);
            if (!level.hasChunkAt(pos)) continue;
            if (p.y > level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ())) continue;
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) return false;
        }
        return true;
    }

    /** A shot-down aircraft keeps falling as a live wreck; it is no longer a target. */
    private static boolean downed(Entity contact) {
        return contact instanceof VehicleEntity vehicle && (vehicle.isWreck() || vehicle.getHealth() <= 0);
    }

    // ------------------------------------------------------------------ fire control

    private void engage(ServerLevel level) {
        if (tracks.isEmpty() || !deployed() || inFlight.size() >= system.channels
                || level.getGameTime() - lastLaunch < system.launchInterval) return;
        if (!system.firesOnMove && getDeltaMovement().horizontalDistanceSqr() > 0.0025) return;
        // The crew's designation goes first in either mode, when it can be fired at now; otherwise
        // (out of the zone, rack empty, already engaged) AUTO keeps picking hostiles on its own.
        Track chosen = designated < 0 ? null : tracks.get(designated);
        if (chosen != null && (engaged(chosen.entity) || SamEnvelope.choose(system, readyAll(), chosen.kind, chosen.range, chosen.altitude) == null))
            chosen = null;
        if (chosen == null && mode() == AUTO) {
            chosen = tracks.values().stream()
                .filter(t -> t.relation == 0 && t.heldTicks >= system.lockTicks && !engaged(t.entity)
                    && SamEnvelope.choose(system, readyAll(), t.kind, t.range, t.altitude) != null)
                .max(Comparator.comparingDouble(t -> SamEnvelope.threat(t.kind, t.range, closing(t))))
                .orElse(null);
        }
        if (chosen == null) return;
        SamMissile missile = SamEnvelope.choose(system, readyAll(), chosen.kind, chosen.range, chosen.altitude);
        if (missile == null) return;
        if (fire(level, chosen, missile) && chosen.entity.getId() == designated) designated = -1;
    }

    private double closing(Track track) {
        Vec3 toSite = radarPoint().subtract(track.position);
        double distance = toSite.length();
        return distance < 1e-3 ? 0 : track.velocity.dot(toSite) / distance;
    }

    /** Missiles allowed in flight at one target: a salvo of two at a missile, one at anything else. */
    private static boolean engaged(Entity target) {
        int salvo = AirPicture.kind(target) == SamEnvelope.Kind.MISSILE ? 2 : 1;
        return ENGAGED.getOrDefault(target.getUUID(), 0) >= salvo;
    }

    static int engagedCount(Entity target) { return ENGAGED.getOrDefault(target.getUUID(), 0); }

    static void clearEngagements() { ENGAGED.clear(); }

    static void released(UUID target) {
        if (target != null) ENGAGED.computeIfPresent(target, (id, count) -> count > 1 ? count - 1 : null);
    }

    private boolean fire(ServerLevel level, Track track, SamMissile type) {
        int rack = -1;
        for (int i = 0; i < system.racks.size(); i++) if (system.racks.get(i).missile() == type && ready(i) > 0) { rack = i; break; }
        if (rack < 0) return false;
        int round = system.racks.get(rack).capacity() - ready(rack);
        Vec3 dir = launchDirection(track.position);
        Vec3 from = SamLayout.launchPoint(this, rack, round, dir);
        var missile = SamMissileEntity.launch(level, this, type, track.entity, from, dir);
        if (missile == null || !level.addFreshEntity(missile)) return false;
        setReady(rack, ready(rack) - 1);
        inFlight.add(missile);
        ENGAGED.merge(track.entity.getUUID(), 1, Integer::sum);
        lastLaunch = level.getGameTime();
        launches++;
        SamNetwork.warn(track.entity, this, missile);
        return true;
    }

    /** Vertical-launch packs fire straight up; rails fire along the launcher. */
    private Vec3 launchDirection(Vec3 target) {
        SamMissile any = system.racks.get(0).missile();
        if (any.verticalTicks > 0) return new Vec3(0, 1, 0);
        float yaw = getYRot() + aimYaw();
        float pitch = aimPitch();
        double cp = Math.cos(Math.toRadians(pitch));
        return new Vec3(-Math.sin(Math.toRadians(yaw)) * cp, Math.sin(Math.toRadians(pitch)), Math.cos(Math.toRadians(yaw)) * cp);
    }

    /** Rail launchers turn toward the designated or most threatening track; vertical packs only raise. */
    private void slew() {
        if (system.racks.get(0).missile().verticalTicks > 0) return;
        Track focus = designated >= 0 ? tracks.get(designated) : tracks.values().stream()
            .filter(t -> t.relation == 0).max(Comparator.comparingDouble(t -> SamEnvelope.threat(t.kind, t.range, closing(t)))).orElse(null);
        float yaw = aimYaw(), pitch = aimPitch();
        float wantYaw = 0, wantPitch = system == SamSystem.S125 ? 15 : 0;
        // The Patriot launcher trains toward the target but fires at one fixed elevation, raised on deployment.
        boolean fixedElevation = system == SamSystem.PATRIOT;
        if (focus != null) {
            Vec3 d = focus.position.subtract(position());
            wantYaw = net.minecraft.util.Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-d.x, d.z)) - getYRot());
            wantPitch = (float) Math.max(10, Math.min(75, Math.toDegrees(Math.atan2(d.y + 20, d.horizontalDistance()))));
        }
        if (fixedElevation) wantPitch = SamLayout.PATRIOT_ELEVATION * deployPercent() / 100f;
        float rate = system == SamSystem.PANTSIR ? 12 : 6;
        float nextYaw = yaw + net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.wrapDegrees(wantYaw - yaw), -rate, rate);
        float nextPitch = pitch + net.minecraft.util.Mth.clamp(wantPitch - pitch, -rate / 2, rate / 2);
        if (Math.abs(nextYaw - yaw) > 0.4f) entityData.set(AIM_YAW, net.minecraft.util.Mth.wrapDegrees(nextYaw));
        if (Math.abs(nextPitch - pitch) > 0.4f) entityData.set(AIM_PITCH, nextPitch);
    }

    void recordBurst(Entity target, boolean hit) {
        if (hit && (!target.isAlive() || target instanceof VehicleEntity vehicle && (vehicle.isWreck() || vehicle.getHealth() <= 0))) kills++;
    }

    // ------------------------------------------------------------------ crew commands

    /** Only a crew member aboard may command the site. */
    public boolean crew(ServerPlayer player) {
        return player.getVehicle() == this && player.isAlive() && player.level() == level();
    }

    /** Server-side mode switch (commands, fixtures); the crew uses {@link #toggleMode}. */
    public void setMode(byte mode) {
        entityData.set(MODE, mode == MANUAL ? MANUAL : AUTO);
        designated = -1;
    }

    public void toggleMode(ServerPlayer player) {
        if (!crew(player)) return;
        entityData.set(MODE, mode() == AUTO ? MANUAL : AUTO);
        designated = -1;
    }

    /**
     * The crew points at a contact the radar holds and clicks: it is engaged next, in either mode.
     * Like every human weapon in the pack, the crew may engage any track but its own vehicle.
     */
    public void designate(ServerPlayer player, int entityId) {
        if (!crew(player)) return;
        Track track = tracks.get(entityId);
        if (track == null || track.entity.getRootVehicle() == player.getRootVehicle() || downed(track.entity)) return;
        designated = entityId;
        designatedAt = level().getGameTime();
    }

    Map<Integer, Track> tracks() { return tracks; }

    // ------------------------------------------------------------------ persistence

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        CompoundTag own = tag.getCompound("FrontierSam");
        if (own.hasUUID("Owner")) owner = own.getUUID("Owner");
        supplied = own.getBoolean("Supplied");
        entityData.set(MODE, own.getByte("Mode") == MANUAL ? MANUAL : AUTO);
        int[] ready = own.getIntArray("Ready");
        for (int i = 0; i < system.racks.size(); i++)
            setReady(i, i < ready.length ? Math.min(system.racks.get(i).capacity(), Math.max(0, ready[i])) : 0);
        launches = own.getInt("Launches");
        kills = own.getInt("Kills");
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        CompoundTag own = new CompoundTag();
        if (owner != null) own.putUUID("Owner", owner);
        own.putBoolean("Supplied", supplied);
        own.putByte("Mode", mode());
        own.putIntArray("Ready", readyAll());
        own.putInt("Launches", launches);
        own.putInt("Kills", kills);
        tag.put("FrontierSam", own);
    }

    public int launches() { return launches; }
    public int kills() { return kills; }

    /** Players riding this site. */
    List<ServerPlayer> crew() {
        List<ServerPlayer> out = new ArrayList<>(2);
        for (Entity passenger : getPassengers()) if (passenger instanceof ServerPlayer player) out.add(player);
        return out;
    }

    public static boolean riding(Player player) { return player.getVehicle() instanceof SamSiteEntity; }
}
