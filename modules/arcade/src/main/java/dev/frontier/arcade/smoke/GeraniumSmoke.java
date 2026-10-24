package dev.frontier.arcade.smoke;

import com.atsuishio.superbwarfare.tools.CustomExplosion;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.PdaShop;
import dev.frontier.arcade.geranium.GeraniumContent;
import dev.frontier.arcade.geranium.GeraniumEntity;
import dev.frontier.arcade.geranium.GeraniumNet;
import dev.frontier.arcade.shop.NativeAviationCatalog;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;

/** Opt-in isolated-world acceptance. Flight is driven only by real client SBW C2S packets. */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID)
public final class GeraniumSmoke {
    public static volatile boolean done, flying;
    public static volatile int phase, age;
    public static volatile UUID activeUuid;
    public static volatile short scriptedInput;
    public static volatile double mouseX, mouseY;
    public static volatile boolean stabilizeRequest, audioChecked, shahedAudioCorrect, originalAudioUnchanged;
    public static volatile int movementPackets, mousePackets, noseSamples, releasedCameraSamples, renderedSamples;
    public static volatile int fireRequest, firePackets;
    public static volatile double bestNoseError = Double.MAX_VALUE;
    private static final BlockPos PAD = new BlockPos(0, 80, 0);
    private static final Map<String, Boolean> CHECKS = new LinkedHashMap<>();
    private static final Map<String, Object> DETAILS = new LinkedHashMap<>();
    private static final List<BlockPos> WALL = new ArrayList<>();
    private static final Map<UUID, Integer> EXPLOSIONS = new LinkedHashMap<>();
    private static ServerPlayer player;
    private static ServerLevel level;
    private static GeraniumEntity drone;
    private static net.minecraft.world.entity.monster.Zombie contactTarget;
    private static AABB contactBox;
    private static float contactInitialHealth;
    private static Vec3 fireNose;
    private static UUID rangeFixtureUuid;
    private static ItemStack monitor = ItemStack.EMPTY;
    private static ListTag originalInventory;
    private static GameType originalMode;
    private static Vec3 originalPlayer, flightStart, releaseStart, dryStart;
    private static float originalYaw, originalPitch, initialYaw;
    private static Vec3 lastImpact;
    private static double maxAltitude, maxDistance, maxTurn, maxOperatorDistance, dryPeak;
    private static int ticks, initialEnergy, flightEnergy, releaseEnergy, originalSelected;
    private static boolean started;
    private GeraniumSmoke() { }

    public static void observeClient(double noseError, double bodyDistance, boolean linked) {
        renderedSamples++;
        if (phase == 3 && age > 15 && linked && noseError < .6 && bodyDistance > 1) {
            noseSamples++; bestNoseError = Math.min(bestNoseError, noseError);
        }
        if (phase == 4 && age > 2 && !linked && bodyDistance < 1) releasedCameraSamples++;
    }
    public static void packetsSent() { movementPackets++; mousePackets++; }
    public static void timeout() {
        if (level != null && !level.getServer().isSameThread()) { level.getServer().execute(GeraniumSmoke::timeout); return; }
        if (!done) { check("completed before timeout", false); DETAILS.put("timeoutPhase", phase); finish(); }
    }
    private static void check(String name, boolean passed) {
        CHECKS.put(name, passed); System.out.println("GERANIUM_CHECK " + (passed ? "PASS " : "FAIL ") + name);
    }
    private static ItemStack item(String id) {
        var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }
    private static void initialize() {
        check("dedicated Geranium-Test isolated save", "Geranium Test".equals(level.getServer().getWorldData().getLevelName()));
        originalInventory = player.getInventory().save(new ListTag()); originalMode = player.gameMode.getGameModeForPlayer();
        originalSelected = player.getInventory().selected;
        originalPlayer = player.position(); originalYaw = player.getYRot(); originalPitch = player.getXRot();
        player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent(); player.getInventory().selected = 0;
        player.connection.send(new ClientboundSetCarriedItemPacket(0));
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.setDayTime(6000); level.setWeatherParameters(6000, 0, false, false);
        for (BlockPos pos : BlockPos.betweenClosed(-24, 80, -24, 24, 80, 80)) level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        player.teleportTo(level, .5, 81, -5.5, 0, 0);
        catalogChecks(); originalDroneChecks(); rangeChecks();
        place(new ItemStack(GeraniumContent.ITEM.get())); phase = 1; age = 0;
    }
    private static void catalogChecks() {
        final String id = "frontier_arcade_vehicles:geranium_2_drone";
        check("Drone Warfare items and entities are absent from actual registries",
            ForgeRegistries.ITEMS.getKeys().stream().noneMatch(k -> k.getNamespace().equals("sbwdroneconfig"))
                && ForgeRegistries.ENTITY_TYPES.getKeys().stream().noneMatch(k -> k.getNamespace().equals("sbwdroneconfig")));
        check("native aviation offers contain no Drone Warfare products",
            NativeAviationCatalog.offers().stream().noneMatch(o -> o.id().startsWith("sbwdroneconfig:")));
        var offer = NativeAviationCatalog.offer(id).orElse(null);
        check("new native Geranium offer is one deployer for exactly160 emeralds",
            offer != null && offer.price() == 160 && offer.count() == 1 && offer.kind() == NativeAviationCatalog.Kind.EQUIPMENT);
        var output = NativeAviationCatalog.createStack(level, id);
        check("native shop creates the real Geranium deployer", output.is(GeraniumContent.ITEM.get()) && output.getCount() == 1);
        var rows = PdaShop.snapshot(player).getAsJsonArray("items");
        int newRows = 0; boolean exactPrice = true, removedAbsent = true;
        for (var value : rows) {
            var row = value.getAsJsonObject(); String rowId = row.get("id").getAsString();
            removedAbsent &= !rowId.startsWith("sbwdroneconfig:");
            if (id.equals(rowId)) { newRows++; exactPrice &= row.get("cost").getAsInt() == 160 && row.get("count").getAsInt() == 1 && id.equals(row.get("item").getAsString()); }
        }
        check("real unified PDA lists Geranium once at160 and no removed addon offers", newRows == 1 && exactPrice && removedAbsent);
        try {
            Class<?> catalog = Class.forName("dev.frontier.industry.IndustryCatalog");
            catalog.getMethod("invalidate").invoke(null);
            Object recipes = catalog.getMethod("recipes").invoke(null);
            int matches = 0; boolean droneFactory = true, removedRecipesAbsent = true;
            if (!(recipes instanceof List<?> list)) throw new IllegalStateException("IndustryCatalog.recipes is not a list");
            for (Object recipe : list) {
                Class<?> type = recipe.getClass(); String recipeId = (String)type.getMethod("id").invoke(recipe);
                removedRecipesAbsent &= !recipeId.startsWith("sbwdroneconfig:");
                if (id.equals(recipeId)) {
                    matches++; Object factory = type.getMethod("factory").invoke(recipe);
                    ItemStack stack = (ItemStack)type.getMethod("output").invoke(recipe);
                    droneFactory &= "DRONE_FACTORY".equals(String.valueOf(factory)) && stack.is(GeraniumContent.ITEM.get()) && stack.getCount() == 1;
                }
            }
            check("real IndustryCatalog produces Geranium in DroneFactory", matches == 1 && droneFactory);
            check("real IndustryCatalog excludes removed Drone Warfare products", removedRecipesAbsent);
        } catch (ReflectiveOperationException | LinkageError failure) {
            DETAILS.put("industryCatalogFailure", failure.toString()); check("real IndustryCatalog produces Geranium in DroneFactory", false);
        }
    }
    private static void originalDroneChecks() {
        Map<String,String> originals = new LinkedHashMap<>();
        originals.put("fpv_drone", "FpvDroneEntity"); originals.put("mavic_drone_no_drop", "MavicDroneNoDropEntity");
        originals.put("mavic_drone_with_drop", "MavicDroneWithDropEntity"); originals.put("shahed136", "Shahed136Entity");
        for (var original : originals.entrySet()) {
            var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("uncomplicatedfpv", original.getKey()));
            Entity entity = type == null ? null : type.create(level);
            try {
                check("original UF " + original.getKey() + " retains exact native class", entity != null
                    && entity.getClass().getName().equals("ru.lavafrai.uncomplicatedfpv.entity." + original.getValue()));
                if (original.getKey().equals("fpv_drone") && entity instanceof AddonDroneEntity fpv) originalMonitorChecks(fpv);
            } finally { if (entity != null) entity.invalidateCaps(); }
        }
    }
    private static void originalMonitorChecks(AddonDroneEntity fpv) {
        // An unspawned native body exercises binding without ticking, flying, or altering any original entity.
        var owner = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "OriginalFpvOwner"));
        ItemStack before = owner.getMainHandItem(); boolean wasShift = owner.isShiftKeyDown(); var previousPose = owner.getPose();
        try {
            owner.setPos(player.position()); fpv.setPos(player.position().add(0, 0, 3));
            ItemStack held = item("superbwarfare:monitor"); owner.setItemInHand(InteractionHand.MAIN_HAND, held); owner.setShiftKeyDown(false);
            owner.setPose(net.minecraft.world.entity.Pose.STANDING);
            var linked = fpv.interact(owner, InteractionHand.MAIN_HAND);
            check("original native FPV still binds an owner's actual SBW monitor", linked.consumesAction()
                && held.getOrCreateTag().getBoolean("Linked") && fpv.getStringUUID().equals(held.getTag().getString("LinkedDrone"))
                && owner.getStringUUID().equals(fpv.getEntityData().get(com.atsuishio.superbwarfare.entity.vehicle.DroneEntity.CONTROLLER))
                && fpv.getOperatorPosition() != null);
            owner.setShiftKeyDown(true); owner.setPose(net.minecraft.world.entity.Pose.CROUCHING); fpv.interact(owner, InteractionHand.MAIN_HAND);
            DETAILS.put("originalFpvUnlink", Map.of("shiftKey", owner.isShiftKeyDown(), "crouchingPose", owner.isCrouching(),
                "monitorLinked", held.getOrCreateTag().getBoolean("Linked"),
                "nativeLinked", fpv.getEntityData().get(com.atsuishio.superbwarfare.entity.vehicle.DroneEntity.LINKED),
                "controller", fpv.getEntityData().get(com.atsuishio.superbwarfare.entity.vehicle.DroneEntity.CONTROLLER),
                "operatorPosition", String.valueOf(fpv.getOperatorPosition())));
            check("original native FPV still unlinks through native Shift interaction", !held.getOrCreateTag().getBoolean("Linked")
                && !fpv.getEntityData().get(com.atsuishio.superbwarfare.entity.vehicle.DroneEntity.LINKED) && fpv.getOperatorPosition() == null);
        } finally { owner.setItemInHand(InteractionHand.MAIN_HAND, before); owner.setShiftKeyDown(wasShift); owner.setPose(previousPose); }
    }
    private static void rangeChecks() {
        ItemStack before = player.getMainHandItem(); Vec3 observer = player.position(); float yaw = player.getYRot(), pitch = player.getXRot();
        GeraniumEntity fixture = GeraniumContent.TYPE.get().create(level);
        if (fixture == null) throw new IllegalStateException("Range fixture construction failed");
        try {
            fixture.setOwner(player.getUUID()); fixture.setEnergy(GeraniumEntity.BATTERY); fixture.setPos(.5, 81, .5);
            rangeFixtureUuid = fixture.getUUID();
            if (!level.addFreshEntity(fixture)) throw new IllegalStateException("Range fixture spawn rejected");
            double range = fixture.signalRange();
            DETAILS.put("rangeFixture", Map.of("clientChunks", GeraniumNet.viewDistance(player.getUUID()), "serverChunks", level.getServer().getPlayerList().getViewDistance(), "effectiveBlocks", range));
            check("physical radio range is independent of client view6", GeraniumNet.viewDistance(player.getUUID()) == 6 && range == 256);
            ItemStack held = item("superbwarfare:monitor"); player.setItemInHand(InteractionHand.MAIN_HAND, held);
            fixture.interact(player, InteractionHand.MAIN_HAND); held.getItem().use(level, player, InteractionHand.MAIN_HAND);
            check("isolated range fixture starts with a genuinely active native monitor", fixture.activeMonitor() && held.getOrCreateTag().getBoolean("Using"));
            // Only this explicit boundary fixture is moved; every main flight uses real C2S input and physics.
            Vec3 origin = fixture.getOperatorPosition(); if (origin == null) throw new IllegalStateException("No native operator origin");
            fixture.setPos(origin.add(range * 2, 0, 0)); fixture.setPower(.2f); fixture.setNoGravity(true);
            fixture.getPersistentData().putBoolean("up", true); fixture.baseTick();
            check("actual RF loss pauses the view preserves pairing and removes stale thrust", fixture.isSignalLost()
                && !fixture.activeMonitor() && held.getOrCreateTag().getBoolean("Linked") && fixture.getStringUUID().equals(held.getTag().getString("LinkedDrone")) && !held.getTag().getBoolean("Using")
                && fixture.getPower() == 0 && !fixture.isNoGravity() && !fixture.getPersistentData().getBoolean("up") && fixture.isAlive());
        } finally {
            fixture.handleSignalLoss(player); fixture.discard();
            rangeFixtureUuid = null;
            player.setItemInHand(InteractionHand.MAIN_HAND, before); player.teleportTo(level, observer.x, observer.y, observer.z, yaw, pitch);
            player.inventoryMenu.broadcastChanges();
        }
    }
    private static void place(ItemStack stack) {
        activeUuid = null; flying = false; scriptedInput = 0; mouseX = mouseY = 0;
        player.setShiftKeyDown(false); player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var hit = new BlockHitResult(Vec3.atCenterOf(PAD).add(0, .5, 0), Direction.UP, PAD, false);
        var result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        drone = level.getEntitiesOfClass(GeraniumEntity.class, new AABB(PAD).inflate(8), e -> e.isAlive()).stream().findFirst().orElse(null);
        check("real Geranium deployer accepted phase " + phase, result.consumesAction() && drone != null);
        if (drone == null) throw new IllegalStateException("Geranium deployer did not create native entity");
        activeUuid = drone.getUUID(); player.inventoryMenu.broadcastChanges();
    }
    private static void initialChecks() {
        check("ground placement remains alive without explosion", drone.isAlive() && EXPLOSIONS.isEmpty() && drone.onGround());
        check("Geranium is separate registry identity with real owner", "frontier_arcade_vehicles:geranium_2_drone".equals(String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(drone.getType()))) && drone.ownedBy(player));
        initialEnergy = drone.getEnergy();
        check("new Geranium has honest full finite native battery", initialEnergy > 0 && initialEnergy == GeraniumEntity.BATTERY && drone.getMaxEnergy() == GeraniumEntity.BATTERY);
        var visitor = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "GeraniumVisitor"));
        visitor.setItemInHand(InteractionHand.MAIN_HAND, item("superbwarfare:monitor"));
        var rejected = drone.interact(visitor, InteractionHand.MAIN_HAND);
        check("foreign player cannot bind or take over owned Geranium", rejected == net.minecraft.world.InteractionResult.FAIL && drone.ownedBy(player) && !visitor.getMainHandItem().getOrCreateTag().getBoolean("Linked"));
        drone.setEnergy(28731);
        CompoundTag saved = drone.saveWithoutId(new CompoundTag());
        GeraniumEntity restored = GeraniumContent.TYPE.get().create(level);
        if (restored == null) throw new IllegalStateException("NBT fixture construction failed");
        try { restored.load(saved.copy()); check("owner and remaining finite battery survive native NBT reload", restored.ownedBy(player) && restored.getEnergy() == 28731); }
        finally { restored.invalidateCaps(); }
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); player.setShiftKeyDown(true);
        var recovered = drone.interact(player, InteractionHand.MAIN_HAND); player.setShiftKeyDown(false);
        var packs = player.getInventory().items.stream().filter(s -> s.is(GeraniumContent.ITEM.get())).toList();
        check("ground recovery returns one deployer with remaining energy", recovered.consumesAction() && drone.isRemoved() && packs.size() == 1 && packs.get(0).getOrCreateTag().getInt("GeraniumEnergy") == 28731 && EXPLOSIONS.isEmpty());
        ItemStack pack = packs.isEmpty() ? new ItemStack(GeraniumContent.ITEM.get()) : packs.get(0).copy();
        player.getInventory().clearContent(); player.teleportTo(level, .5, 81, -5.5, 0, 0); place(pack);
        check("recovered deployer keeps partial battery without refill", drone.getEnergy() == 28731);
        // Only the observer moves for the model photograph; the placed aircraft is never moved artificially.
        player.teleportTo(level, .5, 82, 6.5, 180, 22); phase = 2; age = 0;
    }
    private static void bind() {
        player.teleportTo(level, .5, 81, -5.5, 0, 0);
        monitor = item("superbwarfare:monitor"); player.setItemInHand(InteractionHand.MAIN_HAND, monitor);
        drone.interact(player, InteractionHand.MAIN_HAND);
        check("actual SBW monitor binds native Geranium UUID", monitor.hasTag() && monitor.getTag().getBoolean("Linked") && drone.getStringUUID().equals(monitor.getTag().getString("LinkedDrone")));
        monitor.getItem().use(level, player, InteractionHand.MAIN_HAND); player.inventoryMenu.broadcastChanges();
        check("actual SBW monitor Item.use enables owner control", drone.activeMonitor() && monitor.getOrCreateTag().getBoolean("Using") && drone.getController() == player);
        flightStart = drone.position(); initialYaw = drone.getYRot(); maxAltitude = flightStart.y; maxDistance = maxTurn = maxOperatorDistance = 0;
        flightEnergy = drone.getEnergy(); phase = 3; age = 0; flying = true;
    }
    private static void flight() {
        if (!drone.isAlive()) throw new IllegalStateException("Geranium removed during normal takeoff before release");
        scriptedInput = (short) (age < 11 ? 16 : age < 31 ? 4 : age < 41 ? 1 : 0);
        mouseX = age >= 25 && age < 37 ? 1 : 0; mouseY = age >= 26 && age < 30 ? -.5 : 0;
        if (age == 42) stabilizeRequest = true;
        maxAltitude = Math.max(maxAltitude, drone.getY()); maxDistance = Math.max(maxDistance, drone.position().distanceTo(flightStart));
        Vec3 operator = drone.getOperatorPosition(); if (operator != null) maxOperatorDistance = Math.max(maxOperatorDistance, drone.position().distanceTo(operator));
        maxTurn = Math.max(maxTurn, Math.abs(net.minecraft.util.Mth.wrapDegrees(drone.getYRot() - initialYaw)));
        if (age == 60) {
            DETAILS.put("flightStart", flightStart.toString()); DETAILS.put("flightEnd", drone.position().toString());
            DETAILS.put("altitudeGain", maxAltitude - flightStart.y); DETAILS.put("movement", maxDistance); DETAILS.put("turnDegrees", maxTurn);
            DETAILS.put("movementPackets", movementPackets); DETAILS.put("mousePackets", mousePackets);
            DETAILS.put("peakOperatorDistance", maxOperatorDistance); DETAILS.put("flightSignalRange", drone.signalRange());
            check("real client SBW keyboard and mouse packets drive flight", movementPackets > 35 && mousePackets > 35 && maxDistance > 4 && maxAltitude > flightStart.y + 1.5);
            check("native mouse turn changes body heading", maxTurn > 2);
            check("manual flight consumes finite native energy", drone.getEnergy() > 0 && drone.getEnergy() < flightEnergy);
            check("client FPV camera is at actual nose lens", noseSamples > 5 && bestNoseError < .6);
            check("aircraft retains forward velocity when movement keys are released", drone.isLaunched() && drone.flightSpeed() > .4 && drone.getDeltaMovement().dot(Vec3.directionFromRotation(drone.getXRot(),drone.getYRot())) > .4);
            check("stabilization levels the original Shahed pitch without stopping", stabilizeRequest && Math.abs(drone.getXRot()) < .2 && drone.flightSpeed() > .4);
            check("propeller engine remains active at normal cruise", drone.engineRunning());
            check("native control remains linked without unintended blast", drone.activeMonitor() && EXPLOSIONS.isEmpty());
            flying = false; scriptedInput = 0; mouseX = mouseY = 0;
            monitor.getItem().use(level, player, InteractionHand.MAIN_HAND); player.inventoryMenu.broadcastChanges();
            releaseStart = drone.position(); releaseEnergy = drone.getEnergy(); phase = 4; age = 0;
        }
    }
    private static void released() {
        if (age < 6) return;
        boolean anyInput = List.of("left", "right", "forward", "backward", "up", "down").stream().anyMatch(k -> drone.getPersistentData().getBoolean(k));
        check("monitor exit clears inputs and retains original Shahed forward cruise", !drone.activeMonitor() && !monitor.getOrCreateTag().getBoolean("Using") && !anyInput && drone.getPower() > .05 && drone.position().distanceTo(releaseStart) > 1 && drone.getEnergy() < releaseEnergy);
        check("client camera returns to physical player after monitor release", releasedCameraSamples > 0);
        if (!drone.isAlive()) throw new IllegalStateException("Geranium crashed before dry-battery observation");
        DETAILS.put("releaseDrift", drone.position().subtract(releaseStart).toString());
        DETAILS.put("releaseEnergy", releaseEnergy);
        drone.setEnergy(0); dryStart = drone.position(); dryPeak = dryStart.y;
        // Attempt ordinary client thrust again against a dry native battery.
        monitor.getItem().use(level, player, InteractionHand.MAIN_HAND); player.inventoryMenu.broadcastChanges();
        phase = 5; age = 0; scriptedInput = 20; flying = true;
    }
    private static void dry() {
        dryPeak = Math.max(dryPeak, drone.getY());
        if (age < 3 || (age < 30 && drone.getDeltaMovement().y >= 0)) return;
        check("dry battery falls without thrust despite native input packets", drone.getEnergy() == 0 && drone.getPower() == 0 && !drone.isNoGravity() && drone.getY() < dryPeak && drone.getDeltaMovement().y < 0);
        DETAILS.put("dryDisplacement", drone.position().subtract(dryStart).toString());
        DETAILS.put("dryCoastTicks", age); DETAILS.put("dryPeakY", dryPeak);
        flying = false; scriptedInput = 0; drone.discard();
        for (BlockPos pos : BlockPos.betweenClosed(-7, 81, 32, 7, 140, 33)) { WALL.add(pos.immutable()); level.setBlockAndUpdate(pos, Blocks.CLAY.defaultBlockState()); }
        player.teleportTo(level, .5, 81, -5.5, 0, 0); place(new ItemStack(GeraniumContent.ITEM.get()));
        phase = 6; age = 0;
    }
    private static void crashBind() {
        monitor = item("superbwarfare:monitor"); player.setItemInHand(InteractionHand.MAIN_HAND, monitor);
        drone.interact(player, InteractionHand.MAIN_HAND); monitor.getItem().use(level, player, InteractionHand.MAIN_HAND); player.inventoryMenu.broadcastChanges();
        check("fresh crash aircraft uses real native monitor control", drone.activeMonitor());
        phase = 7; age = 0; flying = true; scriptedInput = 16;
    }
    private static void crash() {
        scriptedInput = (short) (age < 25 ? 16 : 4); mouseX = mouseY = 0;
        if (drone.isRemoved()) {
            flying = false; scriptedInput = 0;
            // Repeated teardown must never issue a second warhead explosion.
            drone.destroy(); drone.destroy(); phase = 8; age = 0;
        } else if (age > 240) throw new IllegalStateException("Native keyboard flight never reached impact wall: " + drone.position());
    }
    private static void crashChecks() {
        if (age < 40) return;
        int blasts = EXPLOSIONS.getOrDefault(drone.getUUID(), 0);
        long destroyed = WALL.stream().filter(pos -> level.getBlockState(pos).isAir()).count();
        DETAILS.put("crashBlasts", blasts); DETAILS.put("wallBlocksDestroyed", destroyed); DETAILS.put("noseCameraSamples", noseSamples); DETAILS.put("bestNoseCameraError", bestNoseError);
        check("physical impact triggers exactly one native SBW CustomExplosion", blasts == 1);
        check("actual native keyboard flight impacts the fixed wall", lastImpact != null && lastImpact.z >= 28 && lastImpact.z <= 37);
        check("strong native impact physically destroys terrain", destroyed > 5);
        check("impact removes aircraft and disconnects linked monitor", drone.isRemoved() && !monitor.getOrCreateTag().getBoolean("Using") && !monitor.getOrCreateTag().getBoolean("Linked"));
        check("native Geranium model was actually rendered by client", renderedSamples > 10);
        startExtraFixture(9);
    }
    private static void startExtraFixture(int settlePhase) {
        // Restore only the launch pad damaged by earlier real explosions in this isolated world.
        for (BlockPos pos : BlockPos.betweenClosed(-4, 80, -4, 4, 80, 4)) level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        player.teleportTo(level, .5, 81, -5.5, 0, 0); place(new ItemStack(GeraniumContent.ITEM.get()));
        phase = settlePhase; age = 0;
    }
    private static void bindExtraFixture(int flightPhase) {
        monitor = item("superbwarfare:monitor"); player.setItemInHand(InteractionHand.MAIN_HAND, monitor);
        drone.interact(player, InteractionHand.MAIN_HAND); monitor.getItem().use(level, player, InteractionHand.MAIN_HAND);
        player.inventoryMenu.broadcastChanges(); check("additional fixture binds real monitor phase " + flightPhase, drone.activeMonitor());
        // Stand the observer on a real safe pad outside the blast radius, never above the void.
        for (BlockPos pos : BlockPos.betweenClosed(-2, 80, -31, 2, 80, -26)) level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        player.fallDistance = 0;
        player.teleportTo(level, .5, 81, -28.5, 0, 0);
        phase = flightPhase; age = 0; flying = true; scriptedInput = 16; mouseX = mouseY = 0;
    }
    private static void entityContactFlight() {
        scriptedInput = (short)(age < 11 ? 16 : 4); mouseX = mouseY = 0;
        if (drone.isRemoved()) {
            flying = false; scriptedInput = 0; phase = 11; age = 0; return;
        }
        if (contactTarget == null && age >= 35) {
            Vec3 nose = drone.nosePosition(1), velocity = drone.getDeltaMovement();
            // Place only a stationary target two ordinary physics steps ahead of the armed moving nose.
            Vec3 target = nose.add(velocity.scale(2));
            contactTarget = net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
            if (contactTarget == null) throw new IllegalStateException("Contact target construction failed");
            contactTarget.setPos(target.x, target.y - .9, target.z); contactTarget.setNoAi(true);
            contactTarget.setNoGravity(true); contactTarget.setSilent(true);
            contactTarget.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(net.minecraft.world.item.Items.IRON_HELMET));
            contactInitialHealth = contactTarget.getHealth();
            contactBox = contactTarget.getBoundingBox();
            if (!level.addFreshEntity(contactTarget)) throw new IllegalStateException("Contact target spawn rejected");
            DETAILS.put("entityContactFixture", Map.of("dronePosition", drone.position().toString(), "nose", nose.toString(),
                "velocity", velocity.toString(), "targetBox", contactBox.toString(), "targetUUID", contactTarget.getStringUUID()));
        }
        if (age > 65) throw new IllegalStateException("Native moving nose never contacted stationary target: " + drone.position());
    }
    private static void entityContactChecks() {
        if (age < 8) return;
        int blasts = EXPLOSIONS.getOrDefault(drone.getUUID(), 0);
        check("real native flight into a stationary entity triggers one SBW blast", contactTarget != null && blasts == 1
            && lastImpact != null && contactBox.inflate(1).contains(lastImpact));
        boolean damaged = contactTarget != null && (contactTarget.getHealth() < contactInitialHealth || contactTarget.isDeadOrDying() || contactTarget.isRemoved());
        check("native entity impact blast deals actual damage to its target", damaged);
        if (contactTarget != null) DETAILS.put("entityContactDamage", Map.of("initialHealth", contactInitialHealth,
            "healthAfterBlast", contactTarget.getHealth(), "deadOrDying", contactTarget.isDeadOrDying(), "removed", contactTarget.isRemoved(),
            "invulnerable", contactTarget.isInvulnerable(), "fireTicks", contactTarget.getRemainingFireTicks()));
        check("entity contact removes aircraft and disconnects its real monitor", drone.isRemoved()
            && !monitor.getOrCreateTag().getBoolean("Linked") && !monitor.getTag().getBoolean("Using"));
        if (contactTarget != null) { contactTarget.discard(); contactTarget = null; }
        startExtraFixture(12);
    }
    private static void manualFireFlight() {
        scriptedInput = (short)(age < 11 ? 16 : 0); mouseX = mouseY = 0;
        if (age == 30 && !drone.isRemoved()) {
            check("manual fire fixture is armed and controlled before native C2S request", drone.activeMonitor() && !drone.onGround() && drone.getEnergy() > 0);
            fireNose = drone.nosePosition(1); fireRequest++;
        }
        if (drone.isRemoved()) { flying = false; scriptedInput = 0; phase = 14; age = 0; }
        else if (age > 55) throw new IllegalStateException("Real client DroneFireMessage did not trigger native manual blast");
    }
    private static void manualFireChecks() {
        if (age < 10) return;
        check("one actual client DroneFireMessage causes exactly one native blast", firePackets == 1 && fireRequest == 1
            && EXPLOSIONS.getOrDefault(drone.getUUID(), 0) == 1 && fireNose != null && lastImpact != null && lastImpact.distanceTo(fireNose) < 3);
        check("native manual fire removes aircraft and disconnects the real monitor", drone.isRemoved()
            && !monitor.getOrCreateTag().getBoolean("Linked") && !monitor.getTag().getBoolean("Using"));
        check("native monitor sound uses original Shahed engine for Geranium", audioChecked && shahedAudioCorrect);
        check("original FPV monitor engine sound is unchanged", audioChecked && originalAudioUnchanged);
        DETAILS.put("manualFirePackets", firePackets); DETAILS.put("manualFireNose", fireNose == null ? "missing" : fireNose.toString());
        finish();
    }
    @SubscribeEvent public static void explosion(ExplosionEvent.Detonate event) {
        if (!Boolean.getBoolean("frontier.geraniumSmoke") || event.getLevel().isClientSide || !(event.getExplosion() instanceof CustomExplosion explosion)) return;
        Entity source = explosion.getDirectSourceEntity();
        if (source instanceof GeraniumEntity) {
            if (source.getUUID().equals(rangeFixtureUuid)) { DETAILS.put("rangeFixtureUnexpectedExplosion", true); return; }
            EXPLOSIONS.merge(source.getUUID(), 1, Integer::sum); lastImpact = explosion.getPosition();
            DETAILS.put("lastNativeExplosion", Map.of("class", explosion.getClass().getName(), "position", explosion.getPosition().toString(), "radius", explosion.getRadius(), "damage", explosion.getDamage()));
        }
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (!Boolean.getBoolean("frontier.geraniumSmoke") || done || event.phase != TickEvent.Phase.END) return;
        var server = event.getServer(); if (server.getPlayerList().getPlayers().isEmpty()) return;
        try {
            if (!started && ++ticks >= 80) { started = true; player = server.getPlayerList().getPlayers().get(0); level = player.serverLevel(); initialize(); }
            else if (started) {
                if (++ticks > 1200) { timeout(); return; } age++;
                switch (phase) {
                    case 1 -> { if (age >= 20) initialChecks(); }
                    case 2 -> { if (age >= 25) bind(); }
                    case 3 -> flight(); case 4 -> released(); case 5 -> dry();
                    case 6 -> { if (age >= 15) crashBind(); }
                    case 7 -> crash(); case 8 -> crashChecks();
                    case 9 -> { if (age >= 15) bindExtraFixture(10); }
                    case 10 -> entityContactFlight(); case 11 -> entityContactChecks();
                    case 12 -> { if (age >= 15) bindExtraFixture(13); }
                    case 13 -> manualFireFlight(); case 14 -> manualFireChecks();
                    default -> { }
                }
            }
        } catch (Throwable failure) { failure.printStackTrace(); check("runtime without exception", false); DETAILS.put("failure", failure.toString()); finish(); }
    }
    private static void finish() {
        if (done) return; done = true; flying = false; scriptedInput = 0; mouseX = mouseY = 0;
        if (drone != null && !drone.isRemoved()) drone.discard();
        if (contactTarget != null && !contactTarget.isRemoved()) contactTarget.discard();
        if (player != null && originalInventory != null) {
            player.setShiftKeyDown(false); player.setGameMode(originalMode); player.getInventory().load(originalInventory);
            player.getInventory().selected = originalSelected; player.connection.send(new ClientboundSetCarriedItemPacket(originalSelected));
            player.teleportTo(level, originalPlayer.x, originalPlayer.y, originalPlayer.z, originalYaw, originalPitch); player.inventoryMenu.broadcastChanges();
        }
        try {
            var report = new LinkedHashMap<String, Object>(); report.put("allPassed", CHECKS.values().stream().allMatch(Boolean::booleanValue));
            report.put("checks", CHECKS); report.put("details", DETAILS); report.put("serverTicks", ticks);
            Files.writeString(FMLPaths.GAMEDIR.get().resolve("geranium-results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
        } catch (Exception failure) { failure.printStackTrace(); }
    }
}
