package dev.frontier.arcade.smoke;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import dev.frontier.arcade.relay.RelayContent;
import dev.frontier.arcade.relay.RelayEntity;
import dev.frontier.arcade.relay.RelayNetwork;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.ForgeConfigSpec;
import ru.lavafrai.uncomplicatedfpv.config.ServerConfig;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;
import ru.lavafrai.uncomplicatedfpv.entity.DroneSignalModel;
import ru.lavafrai.uncomplicatedfpv.entity.DroneSignalRules;
import ru.lavafrai.uncomplicatedfpv.entity.RebSignalInterference;
import ru.lavafrai.uncomplicatedfpv.registry.ModEntityTypes;

/** Isolated native RF fixtures. These are deliberately separate from the real flight acceptance. */
public final class RelayRadioChecks {
    private RelayRadioChecks() {}

    public static void run(ServerPlayer player, BiConsumer<String, Boolean> check, Map<String, Object> details) {
        ServerLevel level = player.serverLevel();
        List<Entity> fixtures = new ArrayList<>();
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        Map<ForgeConfigSpec.ConfigValue<?>, Object> config = new LinkedHashMap<>();
        Vec3 origin = new Vec3(.5, Math.min(240, level.getMaxBuildHeight() - 40), .5);
        try {
            // Keep same-tick RF fixtures inside the observer's already accessible chunks.
            // A FULL chunk loaded by getChunkAt is not yet visible to entity queries until
            // the distance manager processes its ticket on a subsequent server tick.
            set(config, ServerConfig.FPV_MAX_DISTANCE, 64d);
            set(config, ServerConfig.SIGNAL_LINK_MARGIN_DB, 0d);
            set(config, ServerConfig.SIGNAL_OBSTRUCTIONS_ENABLED, true);
            set(config, ServerConfig.SIGNAL_DIFFRACTION_ENABLED, false);
            set(config, ServerConfig.REB_MINI_CUBE_SIZE, 20d);
            set(config, ServerConfig.REB_MINI_SIGNAL_LOSS_DB, 120d);

            // The direct control link must actually work beyond the previous256-block budget.
            var rangeProbe=RelayContent.TYPE.get().create(level);fixtures.add(rangeProbe);rangeProbe.setOwner(player.getUUID());rangeProbe.setEnergy(RelayEntity.BATTERY);
            for(int x=0;x<=448;x+=16)level.getChunkAt(BlockPos.containing(origin.add(x,0,0)));
            rangeProbe.setPos(origin.add(320,0,0));
            double extendedQuality=RelayNetwork.directSample(rangeProbe,origin).getQuality();
            check.accept("native relay radio budget is384 blocks",DroneSignalRules.INSTANCE.maxDistance(rangeProbe)==384d&&rangeProbe.signalRange()==384d);
            check.accept("native relay direct link reaches320 beyond old256 budget",extendedQuality>0);
            rangeProbe.setPos(origin.add(448,0,0));
            check.accept("extended relay link still has a finite range",RelayNetwork.directSample(rangeProbe,origin).getQuality()==0);
            details.put("relayExtendedRange",Map.of("budgetBlocks",rangeProbe.signalRange(),"qualityAt320",extendedQuality));

            AddonDroneEntity baseline = target(level, player.getUUID(), origin.add(96, 0, 0), fixtures);
            check.accept("native direct FPV cannot reach96 with64 RF budget", RelayNetwork.directSample(baseline, origin).getQuality() == 0);
            RelayEntity first = relay(level, player, origin.add(48, 0, 0), fixtures);
            AddonDroneEntity one = target(level, player.getUUID(), origin.add(96, 0, 0), fixtures);
            var oneSample = DroneSignalModel.INSTANCE.evaluate(level, one, origin);
            var oneLink = RelayNetwork.linkInfo(one, origin);
            check.accept("actual UF evaluate mixin routes native FPV through one airborne relay",
                oneSample.getQuality() > 0 && oneLink.connected() && oneLink.relayHops() == 1);
            details.put("nativeOneRelay", Map.of("directQuality", RelayNetwork.directSample(one, origin).getQuality(),
                "routedQuality", oneSample.getQuality(), "relayHops", oneLink.relayHops()));

            AddonDroneEntity persistedOwner = target(level,player.getUUID(),origin.add(96,0,0),fixtures);
            persistedOwner.getPersistentData().putUUID("frontierIndustryVehicleOwner",player.getUUID());
            persistedOwner.getEntityData().set(DroneEntity.CONTROLLER,"none");
            check.accept("native saved owner reconnect can evaluate relay RF before native controller repair",
                DroneSignalModel.INSTANCE.evaluate(level,persistedOwner,origin).getQuality()>0&&RelayNetwork.linkInfo(persistedOwner,origin).relayHops()==1);

            AddonDroneEntity foreign = target(level, UUID.randomUUID(), origin.add(96, 0, 0), fixtures);
            check.accept("active foreign-owned relay is ignored", first.isRelayActive()
                && !RelayNetwork.linkInfo(foreign, origin).connected());
            first.setEnergy(0);
            check.accept("zero relay energy invalidates an already cached path immediately",
                !RelayNetwork.linkInfo(one, origin).connected());
            quality(one, 1f);
            check.accept("native control predicate rejects removed route despite stale positive UF quality",
                DroneSignalRules.INSTANCE.isPastMaxDistance(one, origin)
                    && DroneSignalRules.INSTANCE.isPastMaxDistance(one, origin));
            first.setEnergy(RelayEntity.BATTERY);

            AddonDroneEntity directFallback = target(level, player.getUUID(), origin.add(60, 0, 0), fixtures);
            double originalDirectQuality = RelayNetwork.directSample(directFallback, origin).getQuality();
            var improved = RelayNetwork.linkInfo(directFallback, origin);
            check.accept("relay can improve a weak but positive native direct path", originalDirectQuality > 0
                && improved.connected() && improved.relayHops() == 1 && improved.quality() > originalDirectQuality);
            first.discard();
            var fallback = RelayNetwork.linkInfo(directFallback, origin);
            check.accept("relay destruction immediately falls back to the exact native direct quality",
                fallback.connected() && fallback.relayHops() == 0 && Math.abs(fallback.quality() - originalDirectQuality) < 1.0e-9);

            first = relay(level, player, origin.add(48, 0, 0), fixtures);
            RelayEntity second = relay(level, player, origin.add(96, 0, 0), fixtures);
            AddonDroneEntity two = target(level, player.getUUID(), origin.add(144, 0, 0), fixtures);
            var visible = level.getEntitiesOfClass(RelayEntity.class, new AABB(origin, origin).inflate(1024));
            check.accept("native two-relay fixtures are visible to the bounded world query", visible.contains(first) && visible.contains(second));
            var twoSample = DroneSignalModel.INSTANCE.evaluate(level, two, origin);
            var twoLink = RelayNetwork.linkInfo(two, origin);
            var probe = target(level, player.getUUID(), first.position(), fixtures);
            var probeNext = target(level, player.getUUID(), second.position(), fixtures);
            details.put("nativeTwoRelayVisibility", Map.of("firstId", first.getUUID().toString(), "secondId", second.getUUID().toString(),
                "firstActive", first.isRelayActive(), "secondActive", second.isRelayActive(),
                "visibleNodes", visible.stream().map(r -> r.getUUID() + "@" + r.position()).toList(),
                "firstNativeQuality", RelayNetwork.directSample(probe, origin).getQuality(),
                "middleNativeQuality", RelayNetwork.directSample(probeNext, first.position()).getQuality(),
                "lastNativeQuality", RelayNetwork.directSample(two, second.position()).getQuality()));
            check.accept("actual UF evaluate mixin routes native FPV through two separate relays",
                RelayNetwork.directSample(two, origin).getQuality() == 0 && twoSample.getQuality() > 0
                    && twoLink.connected() && twoLink.relayHops() == 2);
            details.put("nativeTwoRelay", Map.of("routedQuality", twoSample.getQuality(), "relayHops", twoLink.relayHops()));
            first.discard();
            check.accept("destroying the first hop breaks a two-relay chain in the same tick", !RelayNetwork.reachable(two, origin));
            quality(two, 1f);
            check.accept("known failed chain stays lost after disconnected direct cache replacement",
                DroneSignalRules.INSTANCE.isPastMaxDistance(two, origin));
            second.discard();

            first = relay(level, player, origin.add(48, 0, 0), fixtures);
            second = relay(level, player, origin.add(96, 0, 0), fixtures);
            RelayEntity third = relay(level, player, origin.add(144, 0, 0), fixtures);
            AddonDroneEntity threeRequired = target(level, player.getUUID(), origin.add(192, 0, 0), fixtures);
            check.accept("a third retransmission cannot bypass the two-relay route limit", !RelayNetwork.reachable(threeRequired, origin));
            second.discard(); third.discard();

            CompoundTag groundedTag = first.saveWithoutId(new CompoundTag()); groundedTag.putBoolean("RelayAirborne", false);
            first.readAdditionalSaveData(groundedTag);
            AddonDroneEntity grounded = target(level, player.getUUID(), origin.add(96, 0, 0), fixtures);
            check.accept("a powered launched airframe cannot relay before it is airborne", !first.isRelayActive() && !RelayNetwork.reachable(grounded, origin));
            groundedTag.putBoolean("RelayAirborne", true); first.readAdditionalSaveData(groundedTag);

            var jammer = ModEntityTypes.REB_MINI.get().create(level);
            if (jammer == null) throw new IllegalStateException("Native REB fixture construction failed");
            jammer.setPos(first.position()); jammer.setEnabled(true); level.addFreshEntity(jammer); fixtures.add(jammer);
            AddonDroneEntity firstJammed = target(level, player.getUUID(), origin.add(96, 0, 0), fixtures);
            check.accept("native REB fixture jams relay receiver while destination is outside its cube",
                RebSignalInterference.INSTANCE.evaluate(first).getLossDb() > 0
                    && RebSignalInterference.INSTANCE.evaluate(firstJammed).getLossDb() == 0);
            check.accept("native REB on an intermediate receiver breaks the routed RF path",
                DroneSignalModel.INSTANCE.evaluate(level, firstJammed, origin).getQuality() == 0);
            jammer.setPos(firstJammed.position());
            AddonDroneEntity finalJammed = target(level, player.getUUID(), origin.add(96, 0, 0), fixtures);
            check.accept("native REB on the final drone also breaks the routed RF path",
                RebSignalInterference.INSTANCE.evaluate(first).getLossDb() == 0
                    && RebSignalInterference.INSTANCE.evaluate(finalJammed).getLossDb() > 0
                    && DroneSignalModel.INSTANCE.evaluate(level, finalJammed, origin).getQuality() == 0);
            jammer.discard();

            for (BlockPos pos : BlockPos.betweenClosed(20, (int)origin.y - 8, -8, 28, (int)origin.y + 8, 8)) {
                BlockPos copy = pos.immutable(); blocks.put(copy, level.getBlockState(copy));
                level.setBlockAndUpdate(copy, Blocks.IRON_BLOCK.defaultBlockState());
            }
            AddonDroneEntity obstructed = target(level, player.getUUID(), origin.add(96, 0, 0), fixtures);
            var blockedNativeHop = RelayNetwork.directSample(first, origin);
            check.accept("native terrain sampler detects the intermediate iron obstruction", blockedNativeHop.getObstructionLossDb() > 0);
            check.accept("terrain loss on the operator-to-relay hop blocks the end-to-end path",
                DroneSignalModel.INSTANCE.evaluate(level, obstructed, origin).getQuality() == 0);
            details.put("blockedNativeRelayHop", Map.of("obstructionLossDb", blockedNativeHop.getObstructionLossDb(),
                "quality", blockedNativeHop.getQuality(), "visitedBlocks", blockedNativeHop.getVisitedBlocks()));
            blocks.forEach(level::setBlockAndUpdate); blocks.clear(); first.discard();

            set(config, ServerConfig.FPV_MAX_DISTANCE, 128d);
            RelayEntity pilotBridge = relay(level, player, origin.add(48,0,0), fixtures);
            RelayEntity pilotedRelay = relay(level, player, origin.add(96,0,0), fixtures);
            double directRelayQuality = RelayNetwork.directSample(pilotedRelay, origin).getQuality();
            var nativeRelaySample = DroneSignalModel.INSTANCE.evaluate(level,pilotedRelay,origin);
            var relayControlLink = RelayNetwork.linkInfo(pilotedRelay, origin);
            check.accept("native relay camera control also uses a real intermediate relay",relayControlLink.relayHops()==1
                && nativeRelaySample.getQuality()>directRelayQuality && Math.abs(nativeRelaySample.getQuality()-relayControlLink.quality())<1e-9);
            pilotBridge.discard();pilotedRelay.discard();

            set(config, ServerConfig.SIGNAL_LINK_MARGIN_DB, 12d);
            relay(level, player, origin.add(350, 0, 0), fixtures);
            relay(level, player, origin.add(700, 0, 0), fixtures);
            AddonDroneEntity beyondEnvelope = target(level, player.getUUID(), origin.add(1050, 0, 0), fixtures);
            AddonDroneEntity probeFirst = target(level, player.getUUID(), origin.add(350, 0, 0), fixtures);
            AddonDroneEntity probeSecond = target(level, player.getUUID(), origin.add(700, 0, 0), fixtures);
            check.accept("native viable hops do not bypass the1024-block network envelope",
                RelayNetwork.directSample(probeFirst, origin).getQuality() > 0
                    && RelayNetwork.directSample(probeSecond, probeFirst.position()).getQuality() > 0
                    && RelayNetwork.directSample(beyondEnvelope, probeSecond.position()).getQuality() > 0
                    && !RelayNetwork.reachable(beyondEnvelope, origin));
            check.accept("native hop evaluation guard is restored after all route queries", !RelayNetwork.evaluatingNativeHop());
        } catch (Throwable failure) {
            failure.printStackTrace(); details.put("nativeRadioFixtureFailure", failure.toString());
            check.accept("native radio fixtures complete without exception", false);
        } finally {
            blocks.forEach(level::setBlockAndUpdate);
            for (Entity fixture : fixtures) { if (!fixture.isRemoved()) fixture.discard(); fixture.invalidateCaps(); }
            config.forEach(RelayRadioChecks::restore);
        }
    }

    private static AddonDroneEntity target(ServerLevel level, UUID owner, Vec3 point, List<Entity> fixtures) {
        var drone = ModEntityTypes.FPV_DRONE.get().create(level);
        if (drone == null) throw new IllegalStateException("Native FPV fixture construction failed");
        drone.setPos(point); drone.setEnergy(32_000); drone.getEntityData().set(DroneEntity.CONTROLLER, owner.toString());
        fixtures.add(drone); return drone;
    }

    private static RelayEntity relay(ServerLevel level, ServerPlayer owner, Vec3 point, List<Entity> fixtures) {
        level.getChunkAt(BlockPos.containing(point));
        RelayEntity relay = RelayContent.TYPE.get().create(level);
        if (relay == null) throw new IllegalStateException("Relay fixture construction failed");
        relay.setPos(point); relay.setOwner(owner.getUUID()); relay.setEnergy(RelayEntity.BATTERY);
        CompoundTag tag = relay.saveWithoutId(new CompoundTag());
        tag.putBoolean("RelayAirborne", true); tag.putDouble("RelayLaunchY", point.y - 10);
        var flight = new CompoundTag(); flight.putDouble("CenterX", point.x); flight.putDouble("CenterY", point.y);
        flight.putDouble("CenterZ", point.z); flight.putBoolean("Launched", true); flight.putBoolean("Stopped", false);
        flight.putInt("Ticks", 100); flight.putFloat("Speed", .5f); tag.put("RelayFlight", flight);
        relay.readAdditionalSaveData(tag);
        if (!level.addFreshEntity(relay) || !relay.isRelayActive()) throw new IllegalStateException("Relay fixture is not a live powered airborne owner node");
        fixtures.add(relay); return relay;
    }

    @SuppressWarnings("unchecked") private static void quality(AddonDroneEntity drone, float quality) throws ReflectiveOperationException {
        var field = AddonDroneEntity.class.getDeclaredField("SIGNAL_QUALITY"); field.setAccessible(true);
        drone.getEntityData().set((EntityDataAccessor<Float>)field.get(null), quality);
    }

    private static <T> void set(Map<ForgeConfigSpec.ConfigValue<?>, Object> originals, ForgeConfigSpec.ConfigValue<T> value, T updated) {
        originals.putIfAbsent(value, value.get()); value.set(updated);
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) private static void restore(ForgeConfigSpec.ConfigValue value, Object original) { value.set(original); }
}
