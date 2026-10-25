package dev.frontier.industry.integrationtests;

import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.SiteType;
import dev.frontier.industry.energy.ControllerBlockEntity;
import dev.frontier.industry.energy.EnergyRole;
import dev.frontier.industry.energy.GridConnection;
import dev.frontier.industry.energy.IndustryEnergy;
import dev.frontier.industry.engine.IndustryData;
import dev.frontier.industry.engine.Blueprint;
import dev.frontier.industry.engine.IndustryData.Order;
import dev.frontier.industry.engine.IndustryData.Site;
import dev.frontier.industry.engine.IndustryRules;
import dev.frontier.industry.engine.IndustryState;
import dev.frontier.industry.integration.jade.IndustryJadeProvider;
import dev.frontier.industry.integration.jade.PrivateEnergyProvider;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

/** Native checks run only after Forge registries have loaded in an isolated test server. */
public final class EnergyRuntimeChecks {
    private EnergyRuntimeChecks() {}

    public static Map<String, Boolean> run() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        criticalBlueprintChecks(result);
        IndustryData data = new IndustryData();
        UUID owner = UUID.randomUUID();
        Site station = site(data, owner, SiteType.POWER_STATION, 0);
        Site distribution = site(data, owner, SiteType.SUBSTATION, 16);
        Site consumer = site(data, owner, SiteType.EQUIPMENT_FACTORY, 32);
        distribution.upstream = station.id;
        consumer.upstream = distribution.id;
        check(result, "native valid grid connects", IndustryEnergy.network(data, consumer).connected());
        station.loaded = false;
        check(result, "unloaded native station pauses connected consumer", IndustryEnergy.network(data, consumer) == GridConnection.STATION_UNLOADED);
        station.loaded = true;
        distribution.machineryIntact = false;
        check(result, "destroyed critical substation pauses loaded grid", IndustryEnergy.network(data, consumer) == GridConnection.SUBSTATION_DAMAGED);
        distribution.machineryIntact = true;
        consumer.machineryIntact = false;
        check(result, "repaired network cannot repair consumer", IndustryEnergy.network(data, consumer).connected() && !consumer.functional());
        consumer.machineryIntact = true;
        Site foreign = site(data, UUID.randomUUID(), SiteType.POWER_STATION, 0);
        distribution.upstream = foreign.id;
        check(result, "native owner mismatch cannot power grid", IndustryEnergy.network(data, consumer) == GridConnection.NO_STATION);
        distribution.upstream = station.id;
        Site wrongDimension = new Site(data, UUID.randomUUID(), owner, SiteType.POWER_STATION, new ResourceLocation("minecraft:the_nether"), BlockPos.ZERO);
        data.sites.put(wrongDimension.id, wrongDimension);
        distribution.upstream = wrongDimension.id;
        check(result, "native dimension mismatch cannot power grid", IndustryEnergy.network(data, consumer) == GridConnection.NO_STATION);
        distribution.upstream = consumer.id;
        check(result, "wrong role cannot power distribution", IndustryEnergy.network(data, consumer) == GridConnection.NO_STATION);
        distribution.upstream = station.id;
        station.energy = 128;
        CompoundTag before = data.save(new CompoundTag()).copy();
        check(result, "simulated native extraction keeps entire ledger NBT", IndustryEnergy.extract(data, station, 128, true) == 128 && before.equals(data.save(new CompoundTag())));
        check(result, "simulated native reception keeps entire ledger NBT", IndustryEnergy.receive(data, consumer, 128, true) == 128 && before.equals(data.save(new CompoundTag())));
        Order paid = new Order("minecraft:iron_ingot", 3);
        paid.reserved = true;
        paid.progress = 45;
        consumer.orders.add(paid);
        IndustryEnergy.transfer(data, station, distribution, 128);
        IndustryEnergy.transfer(data, distribution, consumer, 64);
        check(result, "single FE ledger conserves native balances", station.energy == 0 && distribution.energy == 64 && consumer.energy == 64);
        IndustryData restored = IndustryData.load(data.save(new CompoundTag()));
        Site resumed = restored.sites.get(consumer.id);
        check(result, "native FE and paid progress survive restart", resumed.energy == 64 && resumed.orders.peek().reserved && resumed.orders.peek().progress == 45 && resumed.orders.peek().quantity == 3);
        check(result, "restart never treats absent chunks as running", !resumed.loaded && !resumed.powered && !resumed.functional());
        check(result, "generator never accepts FE", !EnergyRole.GENERATOR.receives(Direction.UP) && EnergyRole.GENERATOR.extracts(Direction.UP));
        check(result, "distribution separates top input and side output", EnergyRole.DISTRIBUTOR.receives(Direction.UP) && !EnergyRole.DISTRIBUTOR.extracts(Direction.UP)
            && !EnergyRole.DISTRIBUTOR.receives(Direction.NORTH) && EnergyRole.DISTRIBUTOR.extracts(Direction.NORTH));
        check(result, "consumer cannot export reserved FE", EnergyRole.CONSUMER.receives(Direction.NORTH) && !EnergyRole.CONSUMER.extracts(Direction.NORTH));
        var bridge = new ControllerBlockEntity(BlockPos.ZERO, IndustryContent.controller(SiteType.POWER_STATION).defaultBlockState());
        int oldGeneration = generation(bridge);
        var oldCapability = bridge.getCapability(ForgeCapabilities.ENERGY);
        boolean[] invalidated = {false};
        oldCapability.addListener(ignored -> invalidated[0] = true);
        check(result, "controller exposes Forge standard FE interface", oldCapability.isPresent());
        bridge.invalidateCaps();
        check(result, "capability unload invalidates listener and old generation", invalidated[0] && !bridge.getCapability(ForgeCapabilities.ENERGY).isPresent() && !validGeneration(bridge, oldGeneration));
        bridge.reviveCaps();
        check(result, "capability revive creates fresh generation", bridge.getCapability(ForgeCapabilities.ENERGY).isPresent() && !validGeneration(bridge, oldGeneration));
        bridge.onChunkUnloaded();
        bridge.clearRemoved();
        bridge.onLoad();
        check(result, "chunk reuse restores capability after ordinary onLoad", bridge.getCapability(ForgeCapabilities.ENERGY).isPresent());
        CompoundTag bridgeTag = bridge.saveWithoutMetadata();
        check(result, "bridge stores no second energy queue or stock", !bridgeTag.contains("Energy") && !bridgeTag.contains("Orders") && !bridgeTag.contains("Inventory"));
        String oversized = "🚧".repeat(1000);
        IndustryState view = new IndustryState(consumer.id, consumer.type, oversized, 100, true, true, true, 64, IndustryRules.BUFFER,
            true, oversized, 2, 1, 1799, IndustryRules.REPAIR_TICKS, true, oversized, 3, 45, 90);
        CompoundTag payload = IndustryJadeProvider.encode(view);
        check(result, "Jade sends no ownership coordinates full orders or stock", !payload.hasUUID("id") && !payload.contains("owner") && !payload.contains("Orders") && !payload.contains("Inventory") && !payload.contains("Upstream"));
        try {
            var bytes = new ByteArrayOutputStream();
            NbtIo.write(payload, new DataOutputStream(bytes));
            check(result, "Jade payload bounded below 4KiB with worst Unicode", bytes.size() < 4096);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot encode in-memory Jade payload", error);
        }
        CompoundTag wire = new CompoundTag();
        wire.put("JadeEnergyStorage", new net.minecraft.nbt.ListTag());
        wire.putString("JadeEnergyStorageUid", "jade:energy_storage");
        wire.put(IndustryJadeProvider.DATA_KEY, payload.copy());
        PrivateEnergyProvider.scrub(wire);
        check(result, "Jade universal FE cannot leak alongside filtered view", !wire.contains("JadeEnergyStorage") && !wire.contains("JadeEnergyStorageUid") && wire.getCompound(IndustryJadeProvider.DATA_KEY).equals(payload));
        check(result, "empty extension terminates fallback for every player", PrivateEnergyProvider.INSTANCE.getGroups(null, null, bridge, false).isEmpty());
        var providers = snownee.jade.impl.WailaCommonRegistration.INSTANCE.getBlockNBTProviders(bridge);
        int universalIndex = -1, guardIndex = -1;
        for (int index = 0; index < providers.size(); index++) {
            var provider = providers.get(index);
            if (provider == PrivateEnergyProvider.INSTANCE) guardIndex = index;
            else if (provider.getUid().equals(snownee.jade.api.Identifiers.UNIVERSAL_ENERGY_STORAGE)) universalIndex = index;
        }
        check(result, "actual Jade registration scrubs after inherited universal FE provider", universalIndex >= 0 && guardIndex > universalIndex);
        return result;
    }

    private static Site site(IndustryData data, UUID owner, SiteType type, int x) {
        Site site = new Site(data, UUID.randomUUID(), owner, type, new ResourceLocation("minecraft:overworld"), new BlockPos(x, 80, 0));
        site.loaded = site.controller = true;
        site.matching = site.total = 100;
        site.blueprintVersion = 3;
        data.sites.put(site.id, site);
        return site;
    }

    private static void check(Map<String, Boolean> result, String label, boolean passed) { result.put("energy/jade: " + label, passed); }

    private static void criticalBlueprintChecks(Map<String, Boolean> result) {
        for (int version : new int[]{1, 2, 3}) for (SiteType type : SiteType.values()) {
            if (version == Blueprint.LEGACY_VERSION || type.isConsumer()) {
                check(result, "critical map remains empty for " + type + " v" + version,
                    Blueprint.criticalBlocks(type, new BlockPos(-31, 97, 22), version).isEmpty());
                continue;
            }
            for (BlockPos origin : new BlockPos[]{BlockPos.ZERO, new BlockPos(-31, 97, 22), new BlockPos(115, -20, -87)}) {
                // Independent reference to the pre-cache algorithm: the same exact
                // coordinates select states from the full physical blueprint.
                Map<BlockPos, BlockState> full = Blueprint.blocks(type, origin, version);
                Map<BlockPos, BlockState> expected = new LinkedHashMap<>();
                BlockPos[] positions = type == SiteType.POWER_STATION
                    ? new BlockPos[]{new BlockPos(-6, 2, -1), new BlockPos(-2, 2, -1)}
                    : new BlockPos[]{new BlockPos(-2, 2, 0), new BlockPos(2, 2, 0)};
                for (BlockPos relative : positions) expected.put(origin.offset(relative), full.get(origin.offset(relative)));
                Map<BlockPos, BlockState> actual = Blueprint.criticalBlocks(type, origin, version);
                check(result, "critical positions/states equal original algorithm for " + type + " v" + version + " at " + origin,
                    expected.values().stream().allMatch(java.util.Objects::nonNull) && expected.equals(actual));
                actual.clear();
                check(result, "critical result mutations do not corrupt cached relative states for " + type + " v" + version + " at " + origin,
                    expected.equals(Blueprint.criticalBlocks(type, origin, version)));
            }
        }
    }

    private static int generation(ControllerBlockEntity bridge) {
        try {
            var method = ControllerBlockEntity.class.getDeclaredMethod("capabilityGeneration");
            method.setAccessible(true);
            return (Integer) method.invoke(bridge);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot inspect the test controller capability generation", failure);
        }
    }

    private static boolean validGeneration(ControllerBlockEntity bridge, int generation) {
        try {
            var method = ControllerBlockEntity.class.getDeclaredMethod("validGeneration", int.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(bridge, generation);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot inspect the test controller capability lifecycle", failure);
        }
    }
}
