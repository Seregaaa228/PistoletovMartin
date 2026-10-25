package dev.frontier.industry.repair;

import dev.frontier.industry.WorkerEntity;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.tslat.smartbrainlib.api.core.sensor.ExtendedSensor;

/** Bounded lookup of the server assignment; never scans every world entity or creates a new order. */
public final class RepairTargetSensor extends ExtendedSensor<WorkerEntity> {
    public RepairTargetSensor() { setScanRate(entity -> 20 + Math.floorMod(entity.getId(), 20)); }
    @Override public List<MemoryModuleType<?>> memoriesUsed() { return List.of(RepairContent.REPAIR_TARGET.get()); }
    @Override public SensorType<? extends ExtendedSensor<?>> type() { return RepairContent.REPAIR_SENSOR.get(); }
    @Override protected void doTick(ServerLevel level, WorkerEntity entity) { RepairWork.sense(entity); }
}
