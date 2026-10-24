package dev.frontier.arcade.smoke;

import com.google.gson.GsonBuilder;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import dev.frontier.arcade.ArcadeVehicles;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Test-only native NPC takeoff observation in the isolated NpcAir-Test save.
 * The harness never supplies vehicle inputs, starts its engine, or alters its
 * power/motion/position after initial spawn. The recruit's normal goals do that.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID)
public final class NpcAirSmoke {
    public static volatile boolean done;
    private static final String[] IDS = {"ah_6", "mi_28"};
    private static final int OBSERVE_TICKS = 600;
    private static final String DRIVE_GOAL = "com.logic.superbrecruits.entity.ai.DriveVehicleGoal";
    private static final Map<String, Boolean> checks = new LinkedHashMap<>();
    private static final Map<String, Object> details = new LinkedHashMap<>();
    private static final List<Map<String, Object>> samples = new ArrayList<>();
    private static final List<GoalSelector> selectors = new ArrayList<>();
    private static ServerLevel level;
    private static ServerPlayer owner;
    private static Entity helicopter;
    private static AbstractRecruitEntity pilot;
    private static Vec3 origin;
    private static BlockPos moveOrder;
    private static int ticks, phase, age, index, ground, initialEnergy, minimumEnergy;
    private static int nativeGoalRunningTicks, airborneTicks;
    private static double maxAltitude, maxHorizontalDistance, maxPower;
    private static boolean stayedMounted;

    private static void check(String name, boolean passed) {
        checks.put(name, passed);
        System.out.println("NPC_AIR_CHECK " + (passed ? "PASS " : "FAIL ") + name);
    }

    private static Object call(Object object, String method) throws Exception {
        return object.getClass().getMethod(method).invoke(object);
    }

    private static int energy() throws Exception {
        return ((Number) call(helicopter, "getEnergy")).intValue();
    }

    private static Entity create(String id) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(id));
        if (type == null) throw new IllegalStateException("Missing native entity " + id);
        return Objects.requireNonNull(type.create(level), "Unable to create " + id);
    }

    private static List<Double> position(Entity entity) {
        return List.of(entity.getX(), entity.getY(), entity.getZ());
    }

    private static void spawnGroundedVehicle() throws Exception {
        helicopter = create("superbwarfare:" + IDS[index]);
        // These are initial conditions, before the entity joins the level.
        helicopter.setPos(0.5, ground + 0.05, 0.5);
        helicopter.setYRot(0);
        int capacity = ((Number) call(helicopter, "getMaxEnergy")).intValue();
        helicopter.getClass().getMethod("setEnergy", int.class).invoke(helicopter, capacity);
        check(IDS[index] + " finite native fuel capacity", capacity > 0 && capacity < Integer.MAX_VALUE);
        check(IDS[index] + " native helicopter type", call(helicopter, "getVehicleType").toString().equalsIgnoreCase("helicopter"));
        check(IDS[index] + " vehicle enters world", level.addFreshEntity(helicopter));
        // Observe a real settling phase with no rider or harness motion writes.
        age = 0;
        phase = 1;
    }

    private static void mountNativePilot() throws Exception {
        String id = IDS[index];
        check(id + " starts on natural ground", Math.abs(helicopter.getY() - ground) < 1.25
            && !level.getBlockState(new BlockPos(0, ground - 1, 0)).isAir());
        check(id + " starts without flight momentum", helicopter.getDeltaMovement().length() < 0.2);
        details.put(id + "SettledPosition", position(helicopter));
        details.put(id + "SettledOnGround", helicopter.onGround());
        details.put(id + "InitialEngineStart", call(helicopter, "getEngineStart"));
        details.put(id + "InitialEngineStartOver", call(helicopter, "getEngineStartOver"));
        details.put(id + "InitialPower", call(helicopter, "getPower"));

        pilot = (AbstractRecruitEntity) create("recruits:bowman");
        pilot.initSpawn();
        pilot.setPersistenceRequired();
        pilot.setInvulnerable(true);
        pilot.setIsOwned(true);
        pilot.setOwnerUUID(Optional.of(owner.getUUID()));
        pilot.setAggroState(1);
        pilot.setFollowState(0);
        pilot.setShouldFollow(false);
        pilot.setShouldRest(false);
        pilot.setShouldProtect(false);
        pilot.setShouldHoldPos(false);
        pilot.setShouldMount(false);
        pilot.setPos(helicopter.getX(), ground + 0.05, helicopter.getZ());
        check(id + " real recruit enters world", level.addFreshEntity(pilot));
        check(id + " real recruit takes pilot seat", pilot.startRiding(helicopter, true)
            && helicopter.getFirstPassenger() == pilot);
        moveOrder = new BlockPos(30, ground + 32, 60);
        pilot.setMovePos(moveOrder);
        pilot.setShouldMovePos(true);

        selectors.clear();
        // Read native registered/running goals without invoking or replacing one.
        for (Class<?> type = pilot.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (GoalSelector.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Object value = field.get(pilot);
                    if (value instanceof GoalSelector selector) selectors.add(selector);
                }
            }
        }
        boolean registered = selectors.stream().anyMatch(selector -> selector.getAvailableGoals().stream()
            .anyMatch(wrapped -> wrapped.getGoal().getClass().getName().equals(DRIVE_GOAL)));
        check(id + " native DriveVehicleGoal registered", registered);
        check(id + " native move command retained", pilot.getShouldMovePos() && moveOrder.equals(pilot.getMovePos()));
        origin = helicopter.position();
        initialEnergy = energy();
        minimumEnergy = initialEnergy;
        check(id + " starts with full finite native fuel", initialEnergy == ((Number) call(helicopter, "getMaxEnergy")).intValue()
            && initialEnergy > 0 && initialEnergy < Integer.MAX_VALUE);
        details.put(id + "Target", List.of(moveOrder.getX(), moveOrder.getY(), moveOrder.getZ()));
        details.put(id + "Origin", position(helicopter));
        samples.clear();
        nativeGoalRunningTicks = 0;
        airborneTicks = 0;
        maxAltitude = 0;
        maxHorizontalDistance = 0;
        maxPower = 0;
        stayedMounted = true;
        age = 0;
        phase = 2;
        sample();
    }

    private static void sample() throws Exception {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("tick", age);
        sample.put("position", position(helicopter));
        sample.put("velocity", List.of(helicopter.getDeltaMovement().x, helicopter.getDeltaMovement().y, helicopter.getDeltaMovement().z));
        sample.put("altitudeAboveGround", helicopter.getY() - ground);
        sample.put("horizontalDistance", helicopter.position().subtract(origin).horizontalDistance());
        sample.put("energy", energy());
        sample.put("power", call(helicopter, "getPower"));
        sample.put("engineStart", call(helicopter, "getEngineStart"));
        sample.put("engineStartOver", call(helicopter, "getEngineStartOver"));
        sample.put("forwardInput", call(helicopter, "forwardInputDown"));
        sample.put("upInput", call(helicopter, "upInputDown"));
        sample.put("pilotMounted", pilot.getVehicle() == helicopter);
        sample.put("moveOrderActive", pilot.getShouldMovePos());
        sample.put("needsFood", pilot.needsToGetFood());
        samples.add(sample);
        System.out.println("NPC_AIR_DIAG " + IDS[index] + " " + sample);
    }

    private static void observe() throws Exception {
        age++;
        stayedMounted &= pilot.getVehicle() == helicopter && helicopter.getFirstPassenger() == pilot;
        minimumEnergy = Math.min(minimumEnergy, energy());
        maxAltitude = Math.max(maxAltitude, helicopter.getY() - ground);
        maxHorizontalDistance = Math.max(maxHorizontalDistance, helicopter.position().subtract(origin).horizontalDistance());
        maxPower = Math.max(maxPower, ((Number) call(helicopter, "getPower")).doubleValue());
        if (helicopter.getY() - ground > 4) airborneTicks++;
        if (selectors.stream().anyMatch(selector -> selector.getAvailableGoals().stream().anyMatch(wrapped -> wrapped.isRunning()
            && wrapped.getGoal().getClass().getName().equals(DRIVE_GOAL)))) nativeGoalRunningTicks++;
        if (age % 60 == 0) sample();
        if (age < OBSERVE_TICKS) return;

        String id = IDS[index];
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("observationTicks", age);
        result.put("initialEnergy", initialEnergy);
        result.put("finalEnergy", energy());
        result.put("minimumEnergy", minimumEnergy);
        result.put("fuelUsed", initialEnergy - energy());
        result.put("maximumAltitudeAboveGround", maxAltitude);
        result.put("finalAltitudeAboveGround", helicopter.getY() - ground);
        result.put("maximumHorizontalDistance", maxHorizontalDistance);
        result.put("finalHorizontalDistance", helicopter.position().subtract(origin).horizontalDistance());
        result.put("maximumPower", maxPower);
        result.put("nativeDriveGoalRunningTicks", nativeGoalRunningTicks);
        result.put("ticksAboveFourBlocks", airborneTicks);
        result.put("pilotStayedMounted", stayedMounted);
        result.put("vehicleAlive", helicopter.isAlive());
        result.put("finalPosition", position(helicopter));
        result.put("samples", new ArrayList<>(samples));
        details.put(id, result);
        check(id + " actual native drive goal runs", nativeGoalRunningTicks > 0);
        check(id + " takes off from ground through native NPC control", maxAltitude > 4 && airborneTicks >= 20);
        check(id + " moves horizontally through native NPC control", maxHorizontalDistance > 3);
        check(id + " consumes finite native fuel", energy() > 0 && energy() < initialEnergy && minimumEnergy > 0);
        check(id + " pilot remains mounted throughout", stayedMounted);
        check(id + " survives native flight observation", helicopter.isAlive() && pilot.isAlive());
        pilot.discard();
        helicopter.discard();
        pilot = null;
        helicopter = null;
        if (++index < IDS.length) spawnGroundedVehicle();
        else finish();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (!Boolean.getBoolean("frontier.npcAirSmoke") || done || event.phase != TickEvent.Phase.END
                || event.getServer().getPlayerCount() == 0) return;
        if (++ticks < 80) return;
        try {
            if (phase == 0) {
                if (!event.getServer().getWorldData().getLevelName().equals("NPC Air Test"))
                    throw new IllegalStateException("Refusing to mutate a world outside the isolated NPC Air Test");
                owner = event.getServer().getPlayerList().getPlayers().get(0);
                level = owner.serverLevel();
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, event.getServer());
                level.setDayTime(6000);
                level.setWeatherParameters(6000, 0, false, false);
                ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
                details.put("ground", ground);
                details.put("fixedWingStatus", "Not tested or claimed: installed native DriveVehicleGoal only has a distinct HELICOPTER branch; fixed-wing aircraft use its ground-navigation branch.");
                details.put("controlMethod", "Owned recruits:bowman, native move order only. Finite max energy filled before vehicle spawn; no vehicle input/power/start/velocity/position writes after spawn.");
                check("isolated natural flat ground exists", !level.getBlockState(new BlockPos(0, ground - 1, 0)).isAir());
                owner.teleportTo(level, -12, ground + 6, -12, -30, 10);
                spawnGroundedVehicle();
            } else if (phase == 1 && ++age >= 20) {
                mountNativePilot();
            } else if (phase == 2) {
                observe();
            }
            if (!done && ticks > 1600) {
                check("completes within server tick budget", false);
                finish();
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            check("runtime exception free", false);
            details.put("failure", failure.toString());
            finish();
        }
    }

    private static void finish() {
        try {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("allPassed", !checks.isEmpty() && checks.values().stream().allMatch(Boolean::booleanValue));
            report.put("checks", checks);
            report.put("details", details);
            Files.writeString(FMLPaths.GAMEDIR.get().resolve("npc-air-results.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
        } catch (Exception failure) {
            failure.printStackTrace();
        } finally {
            done = true;
        }
    }
}
