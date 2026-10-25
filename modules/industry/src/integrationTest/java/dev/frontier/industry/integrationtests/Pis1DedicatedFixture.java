package dev.frontier.industry.integrationtests;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.IndustryEngine;
import dev.frontier.industry.SiteType;
import dev.frontier.industry.engine.Blueprint;
import dev.frontier.industry.engine.IndustryData;
import dev.frontier.industry.engine.IndustryData.Order;
import dev.frontier.industry.engine.IndustryData.Site;
import dev.frontier.industry.integration.jade.IndustryJadeProvider;
import io.netty.buffer.Unpooled;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import snownee.jade.api.BlockAccessor;
import snownee.jade.impl.BlockAccessorImpl;

/** Test-only: requires two real loopback connections. Never accepts EULA or starts a server. */
public final class Pis1DedicatedFixture {
    private static final Map<String, Boolean> CHECKS = new LinkedHashMap<>();
    private static final JsonObject REPORT = new JsonObject();
    private static boolean initialized, begun, done;
    private static int ticks, phase;
    private static ServerPlayer owner, foreign;
    private static ServerLevel level;
    private static Site station, distribution, factory;
    private static BlockPos target, damaged;
    private static BlockState savedGrid;

    private Pis1DedicatedFixture() {}
    public static void init() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.addListener(Pis1DedicatedFixture::tick);
    }
    private static void tick(TickEvent.ServerTickEvent event) {
        if (done || event.phase != TickEvent.Phase.END || !Boolean.getBoolean("frontier.pis1DedicatedAcceptance")
            || !(event.getServer() instanceof DedicatedServer server)) return;
        try {
            if (!begun) {
                if (!authorized(server)) throw new IllegalStateException("Dedicated acceptance requires accepted EULA, matching fresh profile and literal loopback bind");
                String ownerName = System.getProperty("frontier.pis1OwnerName", "Pis1Owner");
                String foreignName = System.getProperty("frontier.pis1ForeignName", "Pis1Foreign");
                if (!ownerName.matches("[A-Za-z0-9_]{1,16}") || !foreignName.matches("[A-Za-z0-9_]{1,16}") || ownerName.equalsIgnoreCase(foreignName))
                    throw new IllegalStateException("Two distinct test player names are required");
                owner = server.getPlayerList().getPlayerByName(ownerName);
                foreign = server.getPlayerList().getPlayerByName(foreignName);
                if (owner == null || foreign == null) return;
                if (server.getPlayerList().getPlayerCount() != 2 || !loopback(owner) || !loopback(foreign))
                    throw new IllegalStateException("Only the two named loopback clients may participate");
                setup(server); begun = true; return;
            }
            if (!loopback(owner) || !loopback(foreign) || !owner.connection.connection.isConnected() || !foreign.connection.connection.isConnected())
                throw new IllegalStateException("Both real local clients must remain connected");
            if (++ticks % 20 != 0) return; // Jade's ten-tick per-player cache expires between cases.
            runCase(server, phase++);
        } catch (Exception | LinkageError error) {
            CHECKS.put("fixture completed without exception", false);
            REPORT.addProperty("error", error.toString());
            error.printStackTrace();
            finish();
        }
    }
    public static boolean authorized(DedicatedServer server) {
        if (!IntegrationChecksMod.authorized(server)) return false;
        String bind = server.getServerIp();
        if (!"127.0.0.1".equals(bind) && !"::1".equals(bind) && !"[::1]".equals(bind)) return false;
        try {
            var eula = FMLPaths.GAMEDIR.get().resolve("eula.txt");
            return Files.isRegularFile(eula) && Files.size(eula) < 65536
                && Files.readAllLines(eula, StandardCharsets.UTF_8).stream().anyMatch(line -> line.strip().equals("eula=true"));
        } catch (java.io.IOException error) { return false; }
    }
    private static boolean loopback(ServerPlayer player) {
        return player != null && player.connection.connection.getRemoteAddress() instanceof InetSocketAddress address
            && address.getAddress() != null && address.getAddress().isLoopbackAddress();
    }
    private static void setup(MinecraftServer server) {
        level = server.overworld();
        IndustryData data = IndustryData.get(server);
        if (!data.sites.isEmpty() || !data.workers.isEmpty()) throw new IllegalStateException("Dedicated fixture requires an empty fresh Industry ledger");
        owner.setGameMode(GameType.CREATIVE); foreign.setGameMode(GameType.CREATIVE);
        owner.setNoGravity(true); foreign.setNoGravity(true);
        station = site(data, SiteType.POWER_STATION, new BlockPos(8192, 180, 8192));
        distribution = site(data, SiteType.SUBSTATION, new BlockPos(8232, 180, 8192));
        factory = site(data, SiteType.EQUIPMENT_FACTORY, new BlockPos(8272, 180, 8192));
        station.energy = 10000; distribution.upstream = station.id; factory.upstream = distribution.id; factory.energy = 1234;
        Order order = new Order("minecraft:iron_sword", 3); order.reserved = true; order.progress = 123; factory.orders.add(order);
        target = Blueprint.controller(factory.type, factory.origin, factory.blueprintVersion);
        near(owner); near(foreign);
        data.setDirty();
        REPORT.addProperty("runId", System.getProperty("frontier.pis1RunId"));
        REPORT.addProperty("startedAt", Instant.now().toString());
        REPORT.addProperty("actualDistinctNetworkPlayers", !owner.getUUID().equals(foreign.getUUID()));
        REPORT.addProperty("ownerName", owner.getGameProfile().getName());
        REPORT.addProperty("foreignName", foreign.getGameProfile().getName());
        REPORT.addProperty("requestPath", "Jade11.13.3 toNetwork -> handleRequest -> full registered server provider stack");
        REPORT.addProperty("transportEvidence", "Two actual loopback clients connected; protocol probes are generated server-side");
        REPORT.addProperty("clientVisualEvidence", "pending: capture owner/foreign Jade overlays in both connected windows");
        System.out.println("PIS1_DEDICATED_READY owner=" + owner.getGameProfile().getName() + " foreign=" + foreign.getGameProfile().getName());
    }
    private static Site site(IndustryData data, SiteType type, BlockPos origin) {
        var bounds = Blueprint.bounds(type, 3);
        for (int x = (origin.getX() + bounds.minX()) >> 4; x <= (origin.getX() + bounds.maxX()) >> 4; x++)
            for (int z = (origin.getZ() + bounds.minZ()) >> 4; z <= (origin.getZ() + bounds.maxZ()) >> 4; z++) level.getChunk(x, z);
        var blocks = Blueprint.blocks(type, origin, 3);
        if (blocks.keySet().stream().anyMatch(pos -> !level.getBlockState(pos).isAir())) throw new IllegalStateException("Dedicated test arena is occupied");
        Site site = new Site(data, UUID.randomUUID(), owner.getUUID(), type, level.dimension().location(), origin);
        site.blueprintVersion = 3;
        blocks.forEach((pos, state) -> level.setBlock(pos, state, Block.UPDATE_CLIENTS));
        site.loaded = true; data.sites.put(site.id, site); IndustryEngine.poll(level, site);
        return site;
    }
    private static void near(ServerPlayer player) {
        player.teleportTo(level, target.getX() + .5, target.getY(), target.getZ() + 3.0, 180, 24);
    }
    private static void runCase(MinecraftServer server, int step) {
        switch (step) {
            case 0 -> {
                near(owner); near(foreign);
                CompoundTag own = wire(owner, target), other = wire(foreign, target);
                check("owner receives bounded current order energy and progress", own != null && own.contains(IndustryJadeProvider.DATA_KEY, 10)
                    && own.getCompound(IndustryJadeProvider.DATA_KEY).getInt("progress") == 123);
                check("foreign full Jade wire stack discloses no industrial or generic energy payload", privateEmpty(other));
                check("owner generic FE fallback is suppressed as well", own != null && !own.contains("JadeEnergyStorage") && !own.contains("JadeEnergyStorageUid"));
                check("foreign shared state is absent", IndustryEngine.stateAt(foreign, target).isEmpty());
                CompoundTag before = IndustryData.get(server).save(new CompoundTag());
                IndustryEngine.action(foreign, factory.id, "order", "minecraft:iron_sword", 1);
                check("foreign order action cannot mutate saved ledger", before.equals(IndustryData.get(server).save(new CompoundTag())));
            }
            case 1 -> {
                owner.teleportTo(level, target.getX() + 13.5, target.getY() + .5, target.getZ() + .5, 90, 0);
                check("owner outside twelve blocks cannot receive custom or generic energy data", privateEmpty(wire(owner, target)));
            }
            case 2 -> {
                owner.teleportTo(level, target.getX() + 12.5, target.getY() + .5, target.getZ() + .5, 90, 0);
                CompoundTag boundary = wire(owner, target);
                check("exact twelve block boundary is included", boundary != null && boundary.contains(IndustryJadeProvider.DATA_KEY, 10));
            }
            case 3 -> {
                near(owner); owner.setGameMode(GameType.SPECTATOR);
                check("spectator cannot receive industrial or generic FE fields", privateEmpty(wire(owner, target)));
                owner.setGameMode(GameType.CREATIVE);
            }
            case 4 -> {
                ServerLevel nether = server.getLevel(Level.NETHER);
                if (nether == null) throw new IllegalStateException("Nether dimension is unavailable for dimension guard");
                nether.getChunk(target.getX() >> 4, target.getZ() >> 4);
                owner.teleportTo(nether, target.getX() + .5, target.getY(), target.getZ() + 2.5, 180, 24);
                check("same coordinates in another dimension cannot read overworld ownership", IndustryEngine.stateAt(owner, target).isEmpty()
                    && privateEmpty(wire(owner, target)));
                near(owner);
            }
            case 5 -> {
                BlockPos absent = new BlockPos(1000000, 180, 1000000);
                boolean unloaded = !level.hasChunkAt(absent);
                CompoundTag response = wire(owner, absent);
                check("forged unloaded distant target neither replies nor loads its chunk", unloaded && response == null && !level.hasChunkAt(absent));
                damaged = Blueprint.criticalBlocks(distribution.type, distribution.origin, distribution.blueprintVersion).keySet().iterator().next();
                savedGrid = level.getBlockState(damaged);
                level.setBlock(damaged, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                IndustryEngine.poll(level, distribution);
            }
            case 6 -> {
                near(owner); near(foreign);
                CompoundTag own = wire(owner, target), other = wire(foreign, target);
                var shared = IndustryEngine.stateAt(owner, target).orElseThrow();
                check("Jade and PDA share destroyed grid status", own != null && own.getCompound(IndustryJadeProvider.DATA_KEY).getBoolean("powered") == shared.powered()
                    && own.getCompound(IndustryJadeProvider.DATA_KEY).getString("network").equals(shared.networkStatus()) && !shared.powered());
                check("foreign player still receives no payload after state transition", privateEmpty(other));
                check("outage preserved paid order progress", factory.orders.peek() != null && factory.orders.peek().reserved && factory.orders.peek().progress == 123);
                level.setBlock(damaged, savedGrid, Block.UPDATE_ALL); IndustryEngine.poll(level, distribution);
            }
            case 7 -> {
                near(owner); near(foreign);
                CompoundTag own = wire(owner, target);
                check("restored network state reaches owner Jade payload", own != null && own.getCompound(IndustryJadeProvider.DATA_KEY).getBoolean("powered"));
                check("foreign player remains protected after repair", privateEmpty(wire(foreign, target)));
                check("two network players retained distinct actual UUIDs", !owner.getUUID().equals(foreign.getUUID()));
                finish();
            }
            default -> throw new IllegalStateException("Unknown dedicated test phase");
        }
    }
    /** Exact Jade codec and request handler, including its distance/chunk guards and all registered providers. */
    private static CompoundTag wire(ServerPlayer viewer, BlockPos pos) {
        BlockAccessor accessor = new BlockAccessorImpl.Builder().level(viewer.serverLevel()).player(viewer)
            .serverData(new CompoundTag()).serverConnected(true).showDetails(true)
            .hit(new BlockHitResult(Vec3.atCenterOf(pos), Direction.SOUTH, pos, false))
            .blockState(level.getBlockState(target)).blockEntity(() -> level.getBlockEntity(target)).fakeBlock(ItemStack.EMPTY).build();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            accessor.toNetwork(buffer);
            AtomicReference<CompoundTag> result = new AtomicReference<>();
            BlockAccessorImpl.handleRequest(buffer, viewer, Runnable::run, tag -> result.set(tag.copy()));
            return result.get();
        } finally { buffer.release(); }
    }
    private static boolean privateEmpty(CompoundTag data) {
        return data == null || !data.contains(IndustryJadeProvider.DATA_KEY) && !data.contains("JadeEnergyStorage") && !data.contains("JadeEnergyStorageUid");
    }
    private static void check(String name, boolean passed) {
        CHECKS.put(name, passed);
        if (!passed) System.err.println("PIS1_DEDICATED_CHECK_FAIL " + name);
    }
    private static void finish() {
        if (done) return;
        done = true;
        REPORT.addProperty("runId", System.getProperty("frontier.pis1RunId"));
        REPORT.addProperty("completedAt", Instant.now().toString());
        REPORT.addProperty("serverChecksPassed", !CHECKS.isEmpty() && CHECKS.values().stream().allMatch(Boolean::booleanValue));
        REPORT.addProperty("acceptanceStatus", "pending client overlay screenshots and independent client observation");
        var gson = new GsonBuilder().setPrettyPrinting().create();
        REPORT.add("checks", gson.toJsonTree(CHECKS));
        try {
            var output = FMLPaths.GAMEDIR.get().resolve("pis1-dedicated-checks.json");
            Files.writeString(output, gson.toJson(REPORT) + "\n", StandardCharsets.UTF_8);
            System.out.println("PIS1_DEDICATED_DONE " + output);
        } catch (java.io.IOException error) { error.printStackTrace(); }
    }
}
