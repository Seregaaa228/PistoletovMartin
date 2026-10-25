package dev.frontier.industry.acceptance;

import com.atsuishio.superbwarfare.api.event.ProjectileHitEvent;
import com.atsuishio.superbwarfare.entity.projectile.SmallCannonShellEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Native SmallCannonShell auto-tick probe. Opt-in caller owns the fresh world and two real clients. */
public final class GroundProjectileChecks {
    private static final Map<String, Boolean> RESULTS = new LinkedHashMap<>();
    private static final Map<UUID, List<UUID>> HITS = new HashMap<>();
    private static final List<Entity> SPAWNED = new ArrayList<>();
    private static ServerLevel world;
    private static ServerPlayer shooter, guest;
    private static Entity target;
    private static SmallCannonShellEntity shell;
    private static GameType guestMode;
    private static Vec3 guestPosition;
    private static float guestYaw, guestPitch, guestHealth, healthBefore;
    private static double guestMaxHealth;
    private static boolean guestMayfly, guestFlying, guestNoGravity, complete, registered;
    private static int age, trial;
    private GroundProjectileChecks() { }
    public static void begin(ServerLevel level, ServerPlayer owner) {
        world = level; shooter = owner; age = trial = 0; complete = false;
        RESULTS.clear(); HITS.clear(); SPAWNED.clear();
        guest = level.getServer().getPlayerList().getPlayers().stream().filter(player -> player != owner).findFirst().orElse(null);
        if (guest == null) { RESULTS.put("ground native projectiles require second real player", false); complete = true; return; }
        guestMode = guest.gameMode.getGameModeForPlayer(); guestPosition = guest.position();
        guestYaw = guest.getYRot(); guestPitch = guest.getXRot(); guestHealth = guest.getHealth();
        guestMaxHealth = guest.getAttribute(Attributes.MAX_HEALTH).getBaseValue();
        guestMayfly = guest.getAbilities().mayfly; guestFlying = guest.getAbilities().flying; guestNoGravity = guest.isNoGravity();
        if (!registered) { MinecraftForge.EVENT_BUS.register(GroundProjectileChecks.class); registered = true; }
    }
    public static boolean done() { return complete; }
    public static Map<String, Boolean> results() { return Map.copyOf(RESULTS); }
    public static void tick(ServerLevel level) {
        if (complete || world != level || guest == null) return;
        try {
            if (age == 0) createTarget();
            // The native player hitboxes rewind to the shooter's observed
            // snapshot. Populate history after a fixture teleport, rather than
            // firing two ticks later at a position no remote client has seen.
            int fireAge = target instanceof ServerPlayer ? 12 : 2;
            boolean moving = (trial / 4 & 1) != 0;
            if (moving && (age == fireAge - 1 || age == fireAge))
                target.move(MoverType.SELF, new Vec3(0, 0, target instanceof ServerPlayer ? .1 : .3));
            if (age == fireAge) {
                if (target instanceof VehicleEntity vehicle) { vehicle.updateOBB(); vehicle.refreshBoundingBoxFromOBBs(); }
                Vec3 center = target.getBoundingBox().getCenter();
                if (target instanceof VehicleEntity vehicle && vehicle.getCollisionOBB() != null) {
                    var point = vehicle.getCollisionOBB().center; center = new Vec3(point.x, point.y, point.z);
                }
                if (target instanceof LivingEntity living) center = new Vec3(center.x, living.getY() + living.getBbHeight() * .45, center.z);
                var sentinel = net.minecraft.world.entity.EntityType.COW.create(level);
                if (sentinel == null) throw new IllegalStateException("Sentinel missing");
                sentinel.setNoAi(true); sentinel.setNoGravity(true); sentinel.setPos(center.add(8, -.4, 0));
                sentinel.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); sentinel.setHealth(1000);
                level.addFreshEntity(sentinel); SPAWNED.add(sentinel);
                shell = new SmallCannonShellEntity(com.atsuishio.superbwarfare.init.ModEntities.SMALL_CANNON_SHELL.get(), level);
                shell.setOwner(shooter); shell.setPos(center.add(-12, 0, 0)); shell.setDeltaMovement(24, 0, 0);
                // Start beyond native muzzle grace; retain native damage40/explosion80/radius5.
                shell.tickCount = shell.getNoHitTicks() + 1;
                healthBefore = health(target); HITS.put(shell.getUUID(), new ArrayList<>());
                level.addFreshEntity(shell); SPAWNED.add(shell); // Ordinary ServerLevel ticking performs the actual hit.
            }
            if (++age >= fireAge + 7) {
                String label = "SmallCannonShell24m " + (trial + 1) + " " + type(target) + (moving ? " moving" : " stationary");
                var hits = HITS.getOrDefault(shell.getUUID(), List.of());
                if(!(hits.size()==1&&hits.get(0).equals(target.getUUID())))
                    com.mojang.logging.LogUtils.getLogger().info("COMBAT_GROUND_DIAG {} expected={} position={} hits={}",label,target.getUUID(),target.position(),
                        hits.stream().map(id->{Entity entity=world.getEntity(id);return id+":"+(entity==null?"absent":entity.getClass().getName()+"@"+entity.position());}).toList());
                RESULTS.put(label + " nearest one native hit", hits.size() == 1 && hits.get(0).equals(target.getUUID()));
                RESULTS.put(label + " actual health damage", health(target) < healthBefore || target.isRemoved());
                RESULTS.put(label + " spent projectile removed", shell.isRemoved());
                discardSpawned(); age = 0;
                if (++trial >= 80) { restoreGuest(); complete = true; CombatChecks.write("combat-ground-projectile-results.json", RESULTS); }
            }
        } catch (Throwable failure) {
            RESULTS.put("ground fixture exception " + failure.getClass().getSimpleName() + ": " + failure.getMessage(), false);
            discardSpawned(); restoreGuest(); complete = true; CombatChecks.write("combat-ground-projectile-results.json", RESULTS);
        }
    }
    private static void createTarget() {
        if (trial % 4 == 3) {
            guest.setGameMode(GameType.SURVIVAL);
            guest.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); guest.setHealth(1000);
            guest.getAbilities().mayfly = true; guest.getAbilities().flying = true; guest.onUpdateAbilities();
            guest.setNoGravity(true); guest.setDeltaMovement(Vec3.ZERO);
            guest.teleportTo(world, 30, 160, -60, 90, 0); target = guest;
        } else {
            guest.teleportTo(world, guestPosition.x, guestPosition.y, guestPosition.z, guestYaw, guestPitch);
            guest.setDeltaMovement(Vec3.ZERO);
            String id = switch (trial % 4) {
                case 0 -> "superbwarfare:bmp_2";
                case 1 -> "superbwarfare:t_90a";
                default -> "recruits:recruit";
            };
            var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(id));
            target = type == null ? null : type.create(world);
            if (target == null) throw new IllegalStateException("Missing native target " + id);
            target.setPos(30, 160, -60); target.setNoGravity(true);
            if (target instanceof Mob mob) {
                mob.setNoAi(true); mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); mob.setHealth(1000);
            }
            world.addFreshEntity(target); SPAWNED.add(target);
        }
    }
    private static void restoreGuest() {
        if (guest == null || guestPosition == null) return;
        guest.getAttribute(Attributes.MAX_HEALTH).setBaseValue(guestMaxHealth); guest.setHealth(guestHealth);
        guest.setGameMode(guestMode); guest.setNoGravity(guestNoGravity);
        guest.getAbilities().mayfly = guestMayfly; guest.getAbilities().flying = guestFlying; guest.onUpdateAbilities();
        guest.teleportTo(world, guestPosition.x, guestPosition.y, guestPosition.z, guestYaw, guestPitch); guest.setDeltaMovement(Vec3.ZERO);
    }
    private static void discardSpawned() { SPAWNED.forEach(Entity::discard); SPAWNED.clear(); }
    private static String type(Entity entity) { return String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType())); }
    private static float health(Entity entity) {
        return entity instanceof VehicleEntity vehicle ? vehicle.getHealth() : entity instanceof LivingEntity living ? living.getHealth() : Float.NaN;
    }
    @SubscribeEvent public static void hit(ProjectileHitEvent.HitEntity event) {
        List<UUID> hits = HITS.get(event.getProjectile().getUUID());
        if (world != null && event.getProjectile().level() == world && hits != null) {
            // Native player hitboxes are multipart entities. Native onHitEntity
            // unwraps them after this event, before dealing damage to the player.
            Entity target = event.getTarget();
            if (target instanceof net.minecraftforge.entity.PartEntity<?> part) target = part.getParent();
            hits.add(target.getUUID());
        }
    }
}
