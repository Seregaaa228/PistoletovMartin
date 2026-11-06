package dev.frontier.arcade.smoke;

import com.atsuishio.superbwarfare.init.ModKeyMappings;
import com.atsuishio.superbwarfare.network.NetworkRegistry;
import com.atsuishio.superbwarfare.network.message.send.MouseMoveMessage;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.VehicleAccessPolicy;
import dev.frontier.arcade.control.ControlSessions;
import dev.frontier.arcade.geranium.GeraniumClient;
import dev.frontier.arcade.geranium.GeraniumContent;
import dev.frontier.arcade.geranium.GeraniumEntity;
import dev.frontier.arcade.shop.NativeAviationCatalog;
import dev.frontier.arcade.station.StationBlockEntity;
import dev.frontier.arcade.station.StationContent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.UUID;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Opt-in flight reproduction (-Dfrontier.geraniumRepro=true, built only with -PgeraniumRepro).
 * The server pre-generates a corridor along +z (like the pre-baked map) and then places, binds and
 * logs four aircraft in turn: the jet Geranium-2 and the propeller Shahed-136 with the same scripted
 * stick (a small flick, a long full swipe, a reversal, the F5 chase view and screenshots), a jet at
 * full throttle down the corridor with server tick time, chunk, ticket and loopback traffic numbers
 * (the host watches it pass as a spectator), and a Shahed released from the monitor towards terrain
 * that was never generated, which it must treat as a wall. Both sides log as "GREPRO S"/"GREPRO C";
 * the pilot also reports what creative tabs, JEI and the shop offer.
 * Over a network: the host adds -Dfrontier.geraniumReproLan=PORT -Dfrontier.geraniumReproPilot=NAME
 * (opens its fresh world to LAN without authentication), the pilot client joins it with
 * -Dfrontier.geraniumReproGuest=true; control uses the real ground-station connect path.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID)
public final class GeraniumFlightRepro {
    static final int PHASE_TICKS = 280, LONG_TICKS = 900, EDGE_TICKS = 600, PHASES = 4;
    static final String[] PHASE_NAMES = {"jet", "shahed", "jet-long", "shahed-edge"};
    /** Pre-generated corridor in chunks: x within +-CORRIDOR_HALF, z from -CORRIDOR_HALF to CORRIDOR_END. */
    static final int CORRIDOR_HALF = 13, CORRIDOR_END = 175, LONG_DISTANCE = 2400, PREGEN_PER_TICK = 120;
    private static final BlockPos PAD = new BlockPos(0, 80, 0), DESK = new BlockPos(2, 81, -5);
    private static boolean started, done, pregenerated;
    private static int ticks, phase = -1, phaseAge, pregenX = -CORRIDOR_HALF, pregenZ = -CORRIDOR_HALF, pregenCount;
    private static long pregenStart, loBytes, loTick;
    private static double tickSum, tickMax; private static int tickSamples, chunksMax, ticketsMax;
    private static int edgeCheck = -1; private static ChunkPos edgeLast;
    private static GeraniumEntity drone;
    private static UUID oldRelay;
    private static float lastRoll, lastYaw;
    private GeraniumFlightRepro() { }

    private static boolean enabled() { return Boolean.getBoolean("frontier.geraniumRepro"); }
    private static String v(Vec3 p) { return String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", p.x, p.y, p.z); }

    static String state(GeraniumEntity d) {
        return String.format(Locale.ROOT, "variant=%s pos=%s launched=%s speed=%.3f thr=%d lost=%s energy=%d roll=%.2f pitch=%.2f yaw=%.2f",
            d.variant(), v(d.position()), d.isLaunched(), d.flightSpeed(), d.throttlePercent(), d.isSignalLost(),
            d.getEnergy(), d.getRoll(1), d.getXRot(), d.getYRot());
    }

    /** Bytes through the loopback interface (both directions of the LAN connection), or -1. */
    static long loopbackBytes() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/net/dev"))) {
                String trimmed = line.trim();
                if (trimmed.startsWith("lo:")) return Long.parseLong(trimmed.substring(3).trim().split("\\s+")[0]);
            }
        } catch (IOException | RuntimeException ignored) { }
        return -1;
    }

    @SubscribeEvent public static void serverTick(TickEvent.ServerTickEvent event) {
        if (!enabled() || done || event.phase != TickEvent.Phase.END) return;
        var server = event.getServer();
        // A LAN guest (-Dfrontier.geraniumReproPilot=<name>) pilots over a real network connection.
        String pilot = System.getProperty("frontier.geraniumReproPilot");
        ServerPlayer player = pilot == null ? server.getPlayerList().getPlayers().stream().findFirst().orElse(null)
            : server.getPlayerList().getPlayerByName(pilot);
        if (player == null) return;
        ServerLevel level = player.serverLevel();
        if (!started) {
            if (++ticks < 100) return;
            started = true;
            for (ServerPlayer other : server.getPlayerList().getPlayers())
                if (other != player) { other.setGameMode(GameType.SPECTATOR); other.teleportTo(level, 600.5, 0, 600.5, 0, 0); }
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            level.setDayTime(6000);
            for (BlockPos pos : BlockPos.betweenClosed(-16, 80, -16, 16, 80, 16)) level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            player.setGameMode(GameType.CREATIVE);
            player.teleportTo(level, .5, 81, -5.5, 0, 0);
            level.setBlockAndUpdate(DESK, StationContent.GROUND_STATION.get().defaultBlockState());
            if (level.getBlockEntity(DESK) instanceof StationBlockEntity desk) desk.setOwner(player.getUUID());
            shopReport();
            // A relay left over in an old world must remove itself on its first tick.
            var relay = dev.frontier.arcade.relay.RelayContent.TYPE.get().create(level);
            if (relay != null) { relay.setPos(6.5, 81, 6.5); level.addFreshEntity(relay); oldRelay = relay.getUUID(); }
            System.out.println("GREPRO S old relay spawned=" + (oldRelay != null));
            ticks = 0; pregenStart = System.nanoTime();
            return;
        }
        if (edgeCheck >= 0 && --edgeCheck == 0) edgeReport(level);
        if (oldRelay != null) { System.out.println("GREPRO S old relay present after a tick=" + (level.getEntity(oldRelay) != null)); oldRelay = null; }
        if (!pregenerated) { pregenerate(level); return; }
        if (drone == null) {
            if (++ticks < 60 || edgeCheck > 0) return;
            ticks = 0;
            if (++phase >= PHASES) { System.out.println("GREPRO S finished"); done = true; return; }
            drone = place(player, level, phase % 2 == 1 ? GeraniumContent.SHAHED_ITEM.get() : GeraniumContent.ITEM.get(), phase == 3 ? -90 : 0);
            if (drone == null) { done = true; return; }
            phaseAge = -20;
            return;
        }
        phaseAge++;
        if (phaseAge == 0) {
            boolean connected = ControlSessions.connectStation(player, DESK, drone.getUUID());
            System.out.println("GREPRO S ph=" + PHASE_NAMES[phase] + " connected=" + connected + " activeMonitor=" + drone.activeMonitor() + " " + state(drone));
            if (!connected) { done = true; return; }
            lastRoll = drone.getRoll(1); lastYaw = drone.getYRot();
            if (phase == 2) for (ServerPlayer other : server.getPlayerList().getPlayers())
                if (other != player) other.teleportTo(level, 24.5, 100, 1200.5, 180, 10); // an observer beside the corridor
            return;
        }
        if (phaseAge < 0) return;
        if (drone.isRemoved()) {
            System.out.println("GREPRO S ph=" + PHASE_NAMES[phase] + " t=" + phaseAge + " removed lastChunk=" + edgeLast);
            if (phase == 3) edgeCheck = 60;
            if (phase == 2) longReport(server);
            drone = null; return;
        }
        edgeLast = drone.chunkPosition();
        float roll = drone.getRoll(1), yaw = drone.getYRot();
        double tickMs = server.tickTimes[(server.getTickCount() + 99) % 100] / 1e6;
        int chunks = level.getChunkSource().getLoadedChunksCount(), tickets = dev.frontier.arcade.geranium.FlightChunks.heldTickets(drone.getUUID());
        System.out.println(String.format(Locale.ROOT, "GREPRO S ph=%s t=%d mon=%s droll=%.3f dyaw=%.3f tickMs=%.2f chunks=%d tickets=%d %s", PHASE_NAMES[phase], phaseAge,
            drone.activeMonitor(), roll - lastRoll, net.minecraft.util.Mth.wrapDegrees(yaw - lastYaw), tickMs, chunks, tickets, state(drone)));
        lastRoll = roll; lastYaw = yaw;
        if (phase == 2) {
            if (phaseAge == 60) { loBytes = loopbackBytes(); loTick = phaseAge; }
            if (phaseAge > 60) { tickSum += tickMs; tickMax = Math.max(tickMax, tickMs); tickSamples++; chunksMax = Math.max(chunksMax, chunks); ticketsMax = Math.max(ticketsMax, tickets); }
        }
        if (phase == 3 && phaseAge == 60) {
            ControlSessions.disconnectStation(player); // off the monitor: no player view follows the aircraft any more
            System.out.println("GREPRO S ph=shahed-edge monitor off " + state(drone));
        }
        boolean end = phase < 2 ? phaseAge >= PHASE_TICKS
            : phase == 2 ? phaseAge >= LONG_TICKS || drone.getZ() > LONG_DISTANCE : phaseAge >= EDGE_TICKS;
        if (end) {
            System.out.println("GREPRO S ph=" + PHASE_NAMES[phase] + " end " + state(drone));
            if (phase == 2) longReport(server);
            ControlSessions.disconnectStation(player);
            ControlSessions.forgetPair(drone);
            drone.discard();
            drone = null;
        }
    }

    /** Generates the corridor in slices, as a pre-generated map would already be. */
    private static void pregenerate(ServerLevel level) {
        for (int i = 0; i < PREGEN_PER_TICK && !pregenerated; i++) {
            level.getChunk(pregenX, pregenZ);
            pregenCount++;
            if (++pregenZ > CORRIDOR_END) { pregenZ = -CORRIDOR_HALF; if (++pregenX > CORRIDOR_HALF) pregenerated = true; }
        }
        if (pregenerated) System.out.println(String.format(Locale.ROOT, "GREPRO S pregenerated %d chunks (x +-%d, z %d..%d) in %.1f s",
            pregenCount, CORRIDOR_HALF, -CORRIDOR_HALF, CORRIDOR_END, (System.nanoTime() - pregenStart) / 1e9));
    }

    private static void longReport(net.minecraft.server.MinecraftServer server) {
        long bytes = loopbackBytes();
        double seconds = Math.max(1, phaseAge - loTick) / 20.0;
        System.out.println(String.format(Locale.ROOT, "GREPRO S ph=jet-long summary seconds=%.1f tickAvgMs=%.2f tickMaxMs=%.2f chunksMax=%d ticketsMax=%d loopbackKBps=%.1f pilotPacketsPerS=%.1f",
            seconds, tickSum / Math.max(1, tickSamples), tickMax, chunksMax, ticketsMax,
            loBytes < 0 || bytes < 0 ? -1 : (bytes - loBytes) / 1024.0 / seconds,
            server.getPlayerList().getPlayers().stream().mapToDouble(p -> p.connection.connection.getAverageSentPackets()).max().orElse(-1)));
    }

    /** The wall: the chunk past the last one flown must still not exist on disk or in memory. */
    private static void edgeReport(ServerLevel level) {
        if (edgeLast == null) return;
        for (int dx = 0; dx <= 3; dx++) {
            int x = edgeLast.x + dx;
            System.out.println("GREPRO S ph=shahed-edge chunk x=" + x + " z=" + edgeLast.z + " loaded=" + (level.getChunkSource().getChunkNow(x, edgeLast.z) != null)
                + " generated=" + dev.frontier.arcade.geranium.FlightChunks.generated(level, x, edgeLast.z));
        }
    }

    /** The real deployer item path, on the cleared pad. */
    private static GeraniumEntity place(ServerPlayer player, ServerLevel level, Item item, float yaw) {
        player.teleportTo(level, .5, 81, -5.5, yaw, 0);
        ItemStack deployer = new ItemStack(item);
        player.setItemInHand(InteractionHand.MAIN_HAND, deployer);
        var hit = new BlockHitResult(Vec3.atCenterOf(PAD).add(0, .5, 0), Direction.UP, PAD, false);
        var placed = deployer.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        GeraniumEntity found = level.getEntitiesOfClass(GeraniumEntity.class, new AABB(PAD).inflate(8), e -> e.isAlive()).stream().findFirst().orElse(null);
        System.out.println("GREPRO S ph=" + PHASE_NAMES[phase] + " placed=" + placed + " item=" + ForgeRegistries.ITEMS.getKey(item)
            + " drone=" + (found == null ? "none" : found.getUUID() + " type=" + ForgeRegistries.ENTITY_TYPES.getKey(found.getType())));
        return found;
    }

    private static void shopReport() {
        var ids = NativeAviationCatalog.offers().stream().map(NativeAviationCatalog.Offer::id).toList();
        for (String id : List.of("uncomplicatedfpv:shahed136", "uncomplicatedfpv:radio", "frontier_arcade_vehicles:relay_drone",
            "frontier_arcade_vehicles:shahed_136_drone", "frontier_arcade_vehicles:geranium_2_drone")) {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
            System.out.println("GREPRO S shop " + id + " listed=" + ids.contains(id) + " lookup=" + NativeAviationCatalog.offer(id).isPresent()
                + " accessBlocked=" + VehicleAccessPolicy.isBlocked(new ItemStack(item)));
        }
    }

    /** Captures the JEI runtime for the pilot's search check. */
    @JeiPlugin
    public static final class Jei implements IModPlugin {
        static volatile IJeiRuntime runtime;
        @Override public ResourceLocation getPluginUid() { return new ResourceLocation(ArcadeVehicles.ID, "geranium_repro"); }
        @Override public void onRuntimeAvailable(IJeiRuntime available) { runtime = available; }
    }

    /** The pilot's real key mappings plus a scripted mouse; the server reads SBW's own C2S packets. */
    @Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT)
    public static final class Client {
        private static boolean opened, published, catalogued;
        private static int age = -1, after, phases = -1, idle, sinceExit = -1;
        private static UUID target;
        private Client() { }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void tick(TickEvent.ClientTickEvent event) {
            if (!enabled() || event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            boolean guest = Boolean.getBoolean("frontier.geraniumReproGuest");
            if (!opened && !guest && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                opened = true;
                mc.createWorldOpenFlows().createFreshLevel("Geranium-Repro",
                    new LevelSettings("Geranium Repro", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(20261106L, false, false),
                    registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions());
            }
            if (mc.player == null || mc.level == null) return;
            String port = System.getProperty("frontier.geraniumReproLan");
            if (!published && port != null && mc.getSingleplayerServer() != null) {
                published = true; mc.getSingleplayerServer().setUsesAuthentication(false);
                System.out.println("GREPRO C lan=" + mc.getSingleplayerServer().publishServer(GameType.CREATIVE, true, Integer.parseInt(port)));
            }
            if (port != null && done && ++after > 100) mc.stop();
            if (port != null) { observe(mc); return; } // the host serves the world and watches the long flight pass
            if (!catalogued && mc.player.tickCount > 200) { catalogued = true; catalogReport(mc); }
            GeraniumEntity drone = GeraniumClient.currentTarget();
            if (drone != null && !drone.getUUID().equals(target)) {
                target = drone.getUUID(); age = 0; phases++;
                System.out.println("GREPRO C ph=" + name() + " start variant=" + drone.variant() + " camera=" + mc.options.getCameraType());
            }
            if (drone == null) {
                if (age >= 0) {
                    System.out.println("GREPRO C ph=" + name() + " exit camera=" + mc.options.getCameraType());
                    age = -1; sinceExit = 0;
                }
                // The station session restores the pre-session perspective once its link leaves the hand.
                if (sinceExit >= 0 && ++sinceExit == 10) System.out.println("GREPRO C ph=" + name() + " after exit camera=" + mc.options.getCameraType() + " chase=" + GeraniumClient.chaseView());
                ModKeyMappings.MOVE_SPACE.setDown(false); ModKeyMappings.MOVE_FORWARD.setDown(false);
                // The released Shahed still needs its owner online until it reaches the wall.
                if (phases >= PHASES - 1 && ++idle > 600 && System.getProperty("frontier.geraniumReproKeep") == null) mc.stop();
                return;
            }
            age++;
            boolean space = age >= 20 && age < 26;
            ModKeyMappings.MOVE_SPACE.setDown(space);
            boolean scripted = phases < 2;
            ModKeyMappings.MOVE_FORWARD.setDown(phases == 2 && age >= 30); // full throttle down the corridor
            double mouse = !scripted ? 0 : age >= 60 && age < 80 ? 3 : age >= 110 && age < 170 ? 20 : age >= 200 && age < 230 ? -20 : 0;
            // After SBW's own MouseMoveMessage of this tick; the server keeps the last one.
            NetworkRegistry.PACKET_HANDLER.sendToServer(new MouseMoveMessage(mouse, 0));
            if (scripted ? age == 140 || age == 195 : phases == 2 && age == 150) KeyMapping.click(mc.options.keyTogglePerspective.getKey());
            if (scripted ? age == 100 || age == 135 || age == 160 || age == 185 : phases == 2 && (age == 140 || age == 200))
                shot(mc, name() + "-" + age + "-" + (GeraniumClient.chaseView() ? "chase" : "nose"));
            if (!scripted) {
                if (age % 10 == 0) System.out.println(String.format(Locale.ROOT, "GREPRO C ph=%s t=%d pos=%s speed=%.3f chase=%s clientChunks=%d fps=%d rxPackets=%.1f",
                    name(), age, v(drone.position()), drone.flightSpeed(), GeraniumClient.chaseView(), mc.level.getChunkSource().getLoadedChunksCount(),
                    mc.getFps(), mc.getConnection().getConnection().getAverageReceivedPackets()));
                return;
            }
            var camera = mc.gameRenderer.getMainCamera();
            Vec3 body = drone.getPosition(1);
            System.out.println(String.format(Locale.ROOT, "GREPRO C ph=%s t=%d mouse=%.0f space=%s chase=%s camType=%s camera=%s camYaw=%.1f camPitch=%.1f drone=%s camDist=%.2f sbwRoll=%.2f roll=%.2f yaw=%.2f pitch=%.2f speed=%.3f",
                name(), age, mouse, space, GeraniumClient.chaseView(), mc.options.getCameraType(), v(camera.getPosition()), camera.getYRot(), camera.getXRot(), v(body),
                camera.getPosition().distanceTo(body), com.atsuishio.superbwarfare.event.ClientEventHandler.cameraRoll, drone.getRoll(1), drone.getYRot(), drone.getXRot(), drone.flightSpeed()));
        }

        /** Host spectator beside the corridor: when does it see the passing jet, and how far off is its copy? */
        private static void observe(Minecraft mc) {
            if (phase != 2 || mc.level == null || mc.player == null || mc.player.tickCount % 5 != 0) return;
            for (var entity : mc.level.entitiesForRendering()) {
                if (!(entity instanceof GeraniumEntity seen)) continue;
                var server = mc.getSingleplayerServer();
                var real = server == null ? null : server.overworld().getEntity(seen.getUUID());
                double lag = real == null ? -1 : real.position().distanceTo(seen.position());
                double distance = mc.player.position().distanceTo(seen.position());
                System.out.println(String.format(Locale.ROOT, "GREPRO C observer seen dist=%.1f rendered=%s lagBlocks=%.2f pos=%s",
                    distance, seen.shouldRenderAtSqrDistance(distance * distance), lag, v(seen.position())));
            }
        }

        private static String name() { return phases < 0 ? "none" : PHASE_NAMES[Math.min(phases, PHASES - 1)]; }

        private static void shot(Minecraft mc, String name) {
            Screenshot.grab(mc.gameDirectory, "grepro-" + name + ".png", mc.getMainRenderTarget(), message -> System.out.println("GREPRO C shot " + message.getString()));
        }

        /** What a player can still obtain: creative tabs (incl. search), JEI ingredients and JEI search. */
        private static void catalogReport(Minecraft mc) {
            CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(), true, mc.level.registryAccess());
            TreeSet<String> creative = new TreeSet<>();
            for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
                for (ItemStack stack : tab.getDisplayItems()) collect(creative, stack);
                for (ItemStack stack : tab.getSearchTabDisplayItems()) collect(creative, stack);
            }
            System.out.println("GREPRO C creative " + creative);
            IJeiRuntime jei = Jei.runtime;
            if (jei == null) { System.out.println("GREPRO C jei unavailable"); return; }
            TreeSet<String> all = new TreeSet<>();
            for (ItemStack stack : jei.getIngredientManager().getAllIngredients(VanillaTypes.ITEM_STACK)) collect(all, stack);
            System.out.println("GREPRO C jei ingredients " + all);
            for (String query : List.of("shahed", "шахед", "radio", "радио", "@uncomplicatedfpv", "@frontier_arcade_vehicles")) {
                jei.getIngredientFilter().setFilterText(query);
                List<String> found = new ArrayList<>();
                for (ItemStack stack : jei.getIngredientFilter().getFilteredItemStacks()) found.add(String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem())));
                System.out.println("GREPRO C jei search '" + query + "' " + found);
            }
            jei.getIngredientFilter().setFilterText("");
        }

        /** Only the namespaces under test, so the line stays readable. */
        private static void collect(TreeSet<String> into, ItemStack stack) {
            var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id != null && (id.getNamespace().equals("uncomplicatedfpv") || id.getNamespace().equals(ArcadeVehicles.ID))) into.add(id.toString());
        }
    }
}
