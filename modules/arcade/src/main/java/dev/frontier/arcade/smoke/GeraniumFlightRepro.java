package dev.frontier.arcade.smoke;

import com.atsuishio.superbwarfare.init.ModKeyMappings;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.control.ControlSessions;
import dev.frontier.arcade.station.StationBlockEntity;
import dev.frontier.arcade.station.StationContent;
import dev.frontier.arcade.geranium.GeraniumClient;
import dev.frontier.arcade.geranium.GeraniumContent;
import dev.frontier.arcade.geranium.GeraniumEntity;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
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

/**
 * Opt-in launch reproduction (-Dfrontier.geraniumRepro=true, built only with -PgeraniumRepro).
 * The server places and binds one Geranium on a fresh pad; the client holds the real SBW
 * Space/W key mappings (SBW sends its own VehicleMovementMessage) and both sides log the
 * airframe every tick as "GREPRO S"/"GREPRO C", so server and pilot views can be compared.
 * Over a network: the host adds -Dfrontier.geraniumReproLan=PORT -Dfrontier.geraniumReproPilot=NAME
 * (opens its fresh world to LAN without authentication), the pilot client joins it with
 * -Dfrontier.geraniumReproGuest=true; control uses the real ground-station connect path.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID)
public final class GeraniumFlightRepro {
    static final int LOG_TICKS = 160;
    private static final BlockPos PAD = new BlockPos(0, 80, 0), DESK = new BlockPos(2, 81, -5);
    private static boolean started, bound, done;
    private static int ticks, age;
    private static GeraniumEntity drone;
    private GeraniumFlightRepro() { }

    private static boolean enabled() { return Boolean.getBoolean("frontier.geraniumRepro"); }
    private static String v(Vec3 p) { return String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", p.x, p.y, p.z); }

    static String state(GeraniumEntity d) {
        return String.format(Locale.ROOT, "pos=%s og=%s dm=%s launched=%s speed=%.3f link=%.2f/%.0f sigQ=%.2f lost=%s energy=%d power=%.3f noGrav=%s pitch=%.1f yaw=%.1f",
            v(d.position()), d.onGround(), v(d.getDeltaMovement()), d.isLaunched(), d.flightSpeed(), d.flightRange(), d.signalRange(),
            d.getSignalQuality(), d.isSignalLost(), d.getEnergy(), d.getPower(), d.isNoGravity(), d.getXRot(), d.getYRot());
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
            ItemStack deployer = new ItemStack(GeraniumContent.ITEM.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, deployer);
            var hit = new BlockHitResult(Vec3.atCenterOf(PAD).add(0, .5, 0), Direction.UP, PAD, false);
            var placed = deployer.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            drone = level.getEntitiesOfClass(GeraniumEntity.class, new AABB(PAD).inflate(8), e -> e.isAlive()).stream().findFirst().orElse(null);
            System.out.println("GREPRO S placed=" + placed + " drone=" + (drone == null ? "none" : drone.getUUID()));
            if (drone == null) { done = true; return; }
            // The real ground-station path ("Подключиться"), as the owner's terminal button calls it.
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            level.setBlockAndUpdate(DESK, StationContent.GROUND_STATION.get().defaultBlockState());
            if (level.getBlockEntity(DESK) instanceof StationBlockEntity desk) desk.setOwner(player.getUUID());
            return;
        }
        if (!bound) {
            if (++ticks < 120) return;
            bound = true;
            boolean connected = ControlSessions.connectStation(player, DESK, drone.getUUID());
            System.out.println("GREPRO S connected=" + connected + " activeMonitor=" + drone.activeMonitor() + " " + state(drone));
            if (!connected) done = true;
            return;
        }
        age++;
        if (drone.isRemoved()) { System.out.println("GREPRO S t=" + age + " removed"); done = true; return; }
        System.out.println("GREPRO S t=" + age + " mon=" + drone.activeMonitor() + " " + state(drone));
        if (age >= LOG_TICKS) { System.out.println("GREPRO S finished"); done = true; }
    }

    /** Only the pilot's real key mappings; the server reads them from SBW's own C2S packet. */
    @Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT)
    public static final class Client {
        private static boolean opened, published;
        private static int age = -1, after;
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
            GeraniumEntity drone = GeraniumClient.currentTarget();
            if (age < 0) {
                if (drone == null) return;
                age = 0; target = drone.getUUID();
            }
            age++;
            // Space for 6 ticks after a short settle, then W (throttle) for 40 ticks, then hands off.
            boolean space = age >= 20 && age < 26, forward = age >= 30 && age < 70;
            ModKeyMappings.MOVE_SPACE.setDown(space); ModKeyMappings.MOVE_FORWARD.setDown(forward);
            GeraniumEntity seen = null;
            for (var entity : mc.level.entitiesForRendering())
                if (entity instanceof GeraniumEntity g && g.getUUID().equals(target)) { seen = g; break; }
            var camera = mc.gameRenderer.getMainCamera().getPosition();
            System.out.println("GREPRO C t=" + age + " keys=" + (space ? 16 : forward ? 4 : 0) + " pilotView=" + (drone != null)
                + " camera=" + v(camera) + (seen == null ? " unseen" : " " + state(seen)));
            if (age > GeraniumFlightRepro.LOG_TICKS + 40 && ++after > 5) {
                ModKeyMappings.MOVE_SPACE.setDown(false); ModKeyMappings.MOVE_FORWARD.setDown(false);
                if (System.getProperty("frontier.geraniumReproKeep") == null) mc.stop();
            }
        }
    }
}
