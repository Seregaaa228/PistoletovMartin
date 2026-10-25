package dev.frontier.industry.acceptance;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.CustomExplosion;
import com.atsuishio.superbwarfare.tools.DamageHandler;
import com.atsuishio.superbwarfare.init.ModDamageTypes;
import dev.frontier.arcade.combat.WarheadRules;
import dev.frontier.arcade.combat.DroneSweep;
import dev.frontier.arcade.control.ControlSessions;
import dev.frontier.arcade.geranium.GeraniumContent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.registries.ForgeRegistries;
import ru.lavafrai.uncomplicatedfpv.entity.FpvDroneEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Opt-in runtime fixture. The caller must enforce a fresh acceptance world and two real clients. */
public final class DroneChecks {
    private static final int SHOT_TRIALS = 80; // Twenty of each native target type: ten stationary, ten moving.
    private static final Map<String, Boolean> RESULTS = new LinkedHashMap<>();
    private static final Map<UUID, Integer> BLASTS = new HashMap<>();
    private static final Map<UUID, Entity> ARMORED_DIRECT_TARGETS = new HashMap<>();
    private static final List<Entity> ENTITIES = new ArrayList<>();
    private static final List<BlockPos> FPV_BLOCKS = new ArrayList<>(), GERANIUM_BLOCKS = new ArrayList<>();
    private static ServerLevel world;
    private static ServerPlayer owner;
    private static Entity target;
    private static FpvDroneEntity active;
    private static float beforeHealth;
    private static int age, trial;
    private static boolean complete, registered;
    private DroneChecks() { }
    public static void begin(ServerLevel level, ServerPlayer player) {
        world = level; owner = player; age = trial = 0; complete = false;
        RESULTS.clear(); BLASTS.clear(); ARMORED_DIRECT_TARGETS.clear(); ENTITIES.clear(); FPV_BLOCKS.clear(); GERANIUM_BLOCKS.clear();
        if (!registered) { MinecraftForge.EVENT_BUS.register(DroneChecks.class); registered = true; }
        // Explicit temporary test tickets; no production chunk loader or player world is touched.
        for (int x = -5; x <= -2; x++) for (int z = -4; z <= 0; z++) level.setChunkForced(x, z, true);
    }
    public static boolean done() { return complete; }
    public static Map<String, Boolean> results() { return Map.copyOf(RESULTS); }
    public static void tick(ServerLevel level) {
        if (complete || level != world || owner == null) return;
        try {
            if (trial < SHOT_TRIALS) {
                if (age == 0) createTarget(level, trial);
                if ((trial / 4 & 1) != 0 && (age == 1 || age == 2)) target.move(MoverType.SELF, new Vec3(.3, 0, .1));
                if (age == 3) {
                    if ((trial / 4 & 1) != 0) {
                        target.move(MoverType.SELF, new Vec3(.65, 0, .2));
                        target.setDeltaMovement(.15, 0, .025);
                    }
                    if (target instanceof VehicleEntity vehicle) { vehicle.updateOBB(); vehicle.refreshBoundingBoxFromOBBs(); }
                    Vec3 center = target.getBoundingBox().getCenter();
                    if (target instanceof VehicleEntity vehicle && vehicle.getCollisionOBB() != null) {
                        var c = vehicle.getCollisionOBB().center; center = new Vec3(c.x, c.y, c.z);
                    }
                    active = drone(center.add(-12, 0, 0), true, owner.getUUID());
                    if (trial % 5 == 0) active.handleSignalLoss(owner);
                    Float nativeDirectHealth = null;
                    if (target instanceof VehicleEntity vehicle) {
                        ARMORED_DIRECT_TARGETS.put(active.getUUID(), vehicle);
                        Vec3 start = active.getBoundingBox().getCenter();
                        var predicted = DroneSweep.trace(active, start, start.add(24, 0, 0), entity -> entity == target);
                        if (predicted == null) throw new IllegalStateException("Armored fixture has no actual swept intersection");
                        nativeDirectHealth = nativeDirectHealth(vehicle, predicted.getLocation());
                    }
                    active.move(MoverType.SELF, new Vec3(24, 0, 0));
                    active.destroy(); // Same tick's native crash/destroy re-entry must not add another blast.
                    if (nativeDirectHealth != null) RESULTS.put("FPV armor matches one native direct hit " + (trial + 1) + " " + type(target),
                        Math.abs(health(target) - nativeDirectHealth) < .01f);
                }
                if (++age >= 10) {
                    RESULTS.put("FPV native swept hit " + (trial + 1) + " " + type(target), health(target) < beforeHealth || target.isRemoved());
                    RESULTS.put("FPV exactly one blast " + (trial + 1), blasts(active) == 1);
                    if (target instanceof Cow) RESULTS.put("FPV direct hit excludes second splash damage " + (trial + 1),
                        Math.abs(beforeHealth - health(target) - 340f) < .01f);
                    discardEntities(); trial++; age = 0;
                }
                return;
            }
            if (trial == SHOT_TRIALS) {
                if (age == 0) safetyCases();
                if (++age >= 8) { discardEntities(); trial++; age = 0; }
                return;
            }
            if (trial == SHOT_TRIALS + 1) {
                if (age == 0) {
                    fill(level, new BlockPos(-65, 124, -12), FPV_BLOCKS);
                    fill(level, new BlockPos(-35, 124, -12), GERANIUM_BLOCKS);
                    active = drone(new Vec3(-64.5, 124.5, -11.5), true, owner.getUUID()); active.destroy();
                    var geranium = GeraniumContent.TYPE.get().create(level);
                    if (geranium == null) throw new IllegalStateException("Geranium missing");
                    geranium.setOwner(owner.getUUID()); ((Entity)geranium).setPos(-34.5, 124.5, -11.5);
                    level.addFreshEntity(geranium); ENTITIES.add(geranium); geranium.destroy(); geranium.destroy();
                }
                if (++age >= 10) {
                    long fpv = FPV_BLOCKS.stream().filter(pos -> level.getBlockState(pos).isAir()).count();
                    long geranium = GERANIUM_BLOCKS.stream().filter(pos -> level.getBlockState(pos).isAir()).count();
                    RESULTS.put("FPV native limited blocks changed " + fpv, fpv > 0 && fpv <= WarheadRules.FPV_BLOCK_LIMIT);
                    RESULTS.put("Geranium native larger limited blocks changed " + geranium, geranium > fpv && geranium <= WarheadRules.GERANIUM_BLOCK_LIMIT);
                    cleanup(level); complete = true; CombatChecks.write("combat-drone-results.json", RESULTS);
                }
            }
        } catch (Throwable failure) {
            RESULTS.put("Drone fixture exception " + failure.getClass().getSimpleName() + ": " + failure.getMessage(), false);
            cleanup(level); complete = true; CombatChecks.write("combat-drone-results.json", RESULTS);
        }
    }
    private static void createTarget(ServerLevel level, int index) {
        if (index % 4 == 0) {
            Cow cow = EntityType.COW.create(level);
            if (cow == null) throw new IllegalStateException("Cow missing");
            cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); cow.setHealth(1000); cow.setNoAi(true);
            target = cow;
        } else {
            String id = switch (index % 4) {
                case 1 -> "superbwarfare:lav_150";
                case 2 -> "superbwarfare:t_90a";
                default -> "superbwarfare:bmp_2";
            };
            var entityType = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(id));
            target = entityType == null ? null : entityType.create(level);
            if (!(target instanceof VehicleEntity)) throw new IllegalStateException("Missing native armor " + id);
        }
        target.setNoGravity(true); target.setPos(-55, 120, -36); level.addFreshEntity(target); ENTITIES.add(target);
        beforeHealth = health(target);
    }
    private static void safetyCases() {
        var cow = EntityType.COW.create(world);
        if (cow == null) throw new IllegalStateException("Cow missing");
        cow.setNoGravity(true); cow.setNoAi(true); cow.setPos(-55, 122, -36); world.addFreshEntity(cow); ENTITIES.add(cow);
        float health = cow.getHealth();
        var miss = drone(new Vec3(-67, 122.5, -30), true, owner.getUUID());
        miss.move(MoverType.SELF, new Vec3(24, 0, 0));
        RESULTS.put("FPV near miss no blast and no damage", blasts(miss) == 0 && cow.getHealth() == health); miss.discard();
        var empty = drone(new Vec3(-55, 122, -25), false, owner.getUUID()); empty.destroy(); empty.destroy();
        RESULTS.put("FPV unarmed destroy no warhead", blasts(empty) == 0);
        var unknown = drone(new Vec3(-55, 122, -25), true, null); unknown.destroy();
        RESULTS.put("FPV unknown owner no free warhead", blasts(unknown) == 0);
        var offline = drone(new Vec3(-55, 122, -25), true, UUID.fromString("9179440a-cf32-4f62-8b13-210000000001"));
        offline.getEntityData().set(DroneEntity.CONTROLLER, "none"); offline.destroy(); offline.destroy();
        RESULTS.put("FPV stored offline owner one blast", blasts(offline) == 1);
    }
    /** Actual paired native hurt on an unspawned copy: armor and health clamps are not guessed. */
    private static float nativeDirectHealth(VehicleEntity original, Vec3 point) {
        Entity copy = original.getType().create(world);
        if (!(copy instanceof VehicleEntity reference)) throw new IllegalStateException("Native armored reference missing");
        reference.load(original.saveWithoutId(new CompoundTag()));
        Entity warhead = EntityType.byString("superbwarfare:rpg_rocket_standard").map(type -> type.create(world)).orElse(null);
        if (warhead == null) throw new IllegalStateException("Native RPG damage source missing");
        warhead.setPos(point);
        DamageHandler.doDamage(reference, ModDamageTypes.causeCustomExplosionDamage(world.registryAccess(), warhead, owner), 340f);
        float result = reference.getHealth();
        // Neither reference nor warhead is added to the world or receives a destruction tick.
        reference.discard(); warhead.discard();
        return result;
    }
    private static FpvDroneEntity drone(Vec3 center, boolean armed, UUID ownerId) {
        var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("uncomplicatedfpv:fpv_drone"));
        var entity = type == null ? null : type.create(world);
        if (!(entity instanceof FpvDroneEntity fpv)) throw new IllegalStateException("Native FPV missing");
        fpv.setPos(center.subtract(0, fpv.getBbHeight() * .5, 0)); fpv.setNoGravity(true); world.addFreshEntity(fpv); ENTITIES.add(fpv);
        if (armed) {
            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("superbwarfare:rpg_rocket_standard"));
            fpv.quickLoadAttachment(new ItemStack(item));
            if (fpv.getAmmo() <= 0 || fpv.getCurrentItem().isEmpty()) throw new IllegalStateException("Native FPV quickload failed");
        }
        if (ownerId != null) {
            fpv.getPersistentData().putUUID("frontierIndustryVehicleOwner", ownerId);
            fpv.getEntityData().set(DroneEntity.CONTROLLER, ownerId.toString()); ControlSessions.rememberNativeOwner(fpv);
        } else {
            fpv.getPersistentData().remove("frontierIndustryVehicleOwner"); fpv.getEntityData().set(DroneEntity.CONTROLLER, "none");
        }
        return fpv;
    }
    private static void fill(ServerLevel level, BlockPos center, List<BlockPos> result) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-6, -6, -6), center.offset(6, 6, 6))) {
            level.setBlock(pos, Blocks.WHITE_WOOL.defaultBlockState(), 2); result.add(pos.immutable());
        }
    }
    private static void cleanup(ServerLevel level) {
        discardEntities();
        FPV_BLOCKS.forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2));
        GERANIUM_BLOCKS.forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2));
        for (int x = -5; x <= -2; x++) for (int z = -4; z <= 0; z++) level.setChunkForced(x, z, false);
    }
    private static void discardEntities() { ENTITIES.forEach(Entity::discard); ENTITIES.clear(); }
    private static String type(Entity entity) { return String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType())); }
    private static float health(Entity entity) {
        return entity instanceof VehicleEntity vehicle ? vehicle.getHealth() : entity instanceof LivingEntity living ? living.getHealth() : Float.NaN;
    }
    private static int blasts(Entity entity) { return entity == null ? 0 : BLASTS.getOrDefault(entity.getUUID(), 0); }
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void beforeNativeVehicleSplash(ExplosionEvent.Detonate event) {
        if (world == null || event.getLevel() != world || !(event.getExplosion() instanceof CustomExplosion blast)) return;
        Entity source = blast.getDirectSourceEntity();
        Entity struck = source == null ? null : ARMORED_DIRECT_TARGETS.get(source.getUUID());
        if (struck != null) RESULTS.put("FPV armor excluded before SBW NORMAL adapter " + (trial + 1) + " " + type(struck),
            !event.getAffectedEntities().contains(struck));
    }
    @SubscribeEvent public static void explosion(ExplosionEvent.Detonate event) {
        if (world == null || event.getLevel() != world || !(event.getExplosion() instanceof CustomExplosion blast)) return;
        Entity source = blast.getDirectSourceEntity();
        if (source != null && ENTITIES.contains(source)) BLASTS.merge(source.getUUID(), 1, Integer::sum);
    }
}
