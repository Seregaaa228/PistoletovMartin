package dev.frontier.aviation.flighttest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.control.ControlStatus;
import dev.frontier.aviation.control.server.FlightControlService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

/** Explicitly installed, opt-in helper; never part of the production mod.
 * Requires an active real client sending its normal neutral control frames; this
 * fixture does not submit commands or compete with the client sequence.
 */
@Mod("frontier_flight_tilt_test")
public final class FlightTiltFixture {
    private static final Logger LOG = LogUtils.getLogger();
    private UUID planeId, playerId;
    private ControlLease lease;
    private ServerLevel level;
    private int samples, grounded, groundedStreak;
    private long previousTick = Long.MIN_VALUE;
    private float previousGroundRoll, groundStartRoll;
    private boolean done;
    private float initialRoll;

    public FlightTiltFixture() {
        if (Boolean.getBoolean("frontier.flightTiltTest")) MinecraftForge.EVENT_BUS.addListener(this::tick);
    }

    private boolean authorized(MinecraftServer server) {
        try {
            String id = System.getProperty("frontier.flightTiltRunId", "");
            if (id.isBlank() || !UUID.fromString(id).toString().equals(id)) return false;
            Path profile = FMLPaths.GAMEDIR.get().toRealPath();
            Path manifest = profile.resolve("test-profile.json");
            if (!Files.isRegularFile(manifest) || Files.size(manifest) > 262144) return false;
            JsonObject data = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!data.has("runId") || !data.get("runId").isJsonPrimitive() || !data.get("runId").getAsJsonPrimitive().isString()
                || !id.equals(data.get("runId").getAsString()) || !data.has("worldsCopied")
                || !data.get("worldsCopied").isJsonPrimitive() || !data.get("worldsCopied").getAsJsonPrimitive().isBoolean()
                || data.get("worldsCopied").getAsBoolean()) return false;
            Path saves = profile.resolve("saves").toRealPath();
            Path world = server.getWorldPath(LevelResource.ROOT).toRealPath();
            return saves.startsWith(profile) && world.startsWith(saves) && !world.equals(saves)
                && world.getFileName().toString().equals("flight_tilt_" + id);
        } catch (Exception error) {
            LOG.error("FLIGHT_TILT INVALID guard: {}", error.toString());
            return false;
        }
    }

    private void tick(TickEvent.ServerTickEvent event) {
        if (done || event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (!server.isSameThread() || !authorized(server)) { finish("INVALID", "profile, world, or server thread guard"); return; }
        try {
            if (planeId == null) {
                if (server.getPlayerList().getPlayerCount() != 1) return;
                ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                if (player.isSpectator() || player.isPassenger()) { finish("INVALID", "player is spectator or already riding"); return; }
                level = player.serverLevel();
                BlockPos base = player.blockPosition().offset(6, -1, 0);
                for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                    BlockPos floor = base.offset(x, 0, z);
                    if (!level.hasChunkAt(floor) || !level.getBlockState(floor).isCollisionShapeFullBlock(level, floor)
                        || !level.getBlockState(floor.above()).isAir() || !level.getBlockState(floor.above(2)).isAir()) {
                        finish("INVALID", "flat loaded 7x7 ground and clearance required"); return;
                    }
                }
                VehicleEntity plane = AircraftProfile.F16.entity.get().create(level);
                if (plane == null) { finish("INVALID", "F16 creation returned null"); return; }
                plane.moveTo(base.getX() + .5, base.getY() + 1.01, base.getZ() + .5, player.getYRot(), 0);
                if (!level.noCollision(plane)) { finish("INVALID", "F16 spawn space obstructed"); return; }
                plane.setRoll(5f);
                initialRoll = plane.getRoll();
                if (Math.abs(initialRoll - 5f) > .001f || !level.addFreshEntity(plane)) { finish("INVALID", "F16 spawn or initial roll failed"); return; }
                planeId = plane.getUUID();
                if (!player.startRiding(plane, true)) {
                    finish("INVALID", "pilot seat failed"); return;
                }
                var acquired = FlightControlService.get(server).acquire(player, plane);
                if (acquired.isEmpty()) { finish("INVALID", "flight acquisition failed"); return; }
                lease = acquired.get();
                playerId = player.getUUID();
                LOG.info("FLIGHT_TILT START runId={} entity={} roll={}", System.getProperty("frontier.flightTiltRunId"), planeId, initialRoll);
                return;
            }
            if (!(level.getEntity(planeId) instanceof VehicleEntity plane) || plane.isRemoved()) { finish("INVALID", "test F16 disappeared"); return; }
            var service = FlightControlService.get(server);
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            var snapshot = service.snapshot(plane);
            if (player == null || snapshot.isEmpty() || !playerId.equals(snapshot.get().controllerId())
                || snapshot.get().epoch() != lease.epoch() || !lease.vehicleId().equals(planeId)
                || !lease.controllerId().equals(playerId) || !service.owns(player, plane)) {
                finish("INVALID", "pilot ownership or original lease epoch lost; active client required"); return;
            }
            var telemetry = service.read(plane);
            if (telemetry.isEmpty() || telemetry.get().status() != ControlStatus.OK
                || !telemetry.get().equals(snapshot.get().telemetry())) {
                finish("INVALID", "flight telemetry unavailable, inconsistent, or not OK"); return;
            }
            float actual = plane.getRoll(), state = telemetry.get().state().roll();
            long tick = level.getGameTime();
            long telemetryTick = telemetry.get().serverTick();
            if (telemetryTick != tick || (previousTick != Long.MIN_VALUE && tick != previousTick + 1)) {
                finish("INVALID", "telemetry ticks are stale, repeated, or nonconsecutive"); return;
            }
            previousTick = tick;
            samples++;
            if (plane.onGround()) {
                grounded++;
                float magnitude = Math.abs(actual);
                if (groundedStreak == 0) groundStartRoll = magnitude;
                else if (magnitude > previousGroundRoll + .01f) {
                    finish("INVALID", "grounded roll increased beyond tolerance"); return;
                }
                previousGroundRoll = magnitude;
                groundedStreak++;
            } else groundedStreak = 0;
            LOG.info("FLIGHT_TILT SAMPLE tick={} sample={} ground={} actual={} state={} telemetryTick={}", level.getGameTime(), samples, plane.onGround(), actual, state, telemetry.get().serverTick());
            if (!Float.isFinite(actual) || !Float.isFinite(state) || Math.abs(actual - state) > .001f) {
                finish("FAIL", "server telemetry roll differs from actual post-tick roll"); return;
            }
            if (samples >= 30) {
                if (groundedStreak < 20 || groundStartRoll - previousGroundRoll <= .001f)
                    finish("INVALID", "20 consecutive grounded samples with decreasing roll were not observed");
                else finish("PASS", "30 matching ticks and consecutive grounded alignment");
            }
        } catch (RuntimeException | LinkageError error) {
            LOG.error("FLIGHT_TILT INVALID exception", error);
            finish("INVALID", error.toString());
        }
    }

    private void finish(String result, String reason) {
        done = true;
        LOG.info("FLIGHT_TILT {} runId={} entity={} samples={} grounded={} reason={}", result,
            System.getProperty("frontier.flightTiltRunId", ""), planeId, samples, grounded, reason);
    }
}
