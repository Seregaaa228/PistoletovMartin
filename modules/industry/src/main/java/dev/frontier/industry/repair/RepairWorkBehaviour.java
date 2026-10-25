package dev.frontier.industry.repair;

import com.mojang.datafixers.util.Pair;
import dev.frontier.industry.WorkerEntity;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour;

/** Presents server readiness. Only IndustryEngine advances/settles the shared site transaction. */
public final class RepairWorkBehaviour extends ExtendedBehaviour<WorkerEntity> {
    public RepairWorkBehaviour() { noTimeout(); }
    @Override protected List<Pair<MemoryModuleType<?>, MemoryStatus>> getMemoryRequirements() {
        return List.of(Pair.of(RepairContent.REPAIR_TARGET.get(), MemoryStatus.VALUE_PRESENT));
    }
    @Override protected boolean checkExtraStartConditions(ServerLevel level, WorkerEntity entity) { return RepairWork.atAssignedApproach(entity); }
    @Override protected boolean shouldKeepRunning(WorkerEntity entity) { return RepairWork.atAssignedApproach(entity); }
    @Override protected void start(WorkerEntity entity) { RepairWork.work(entity); }
    @Override protected void tick(WorkerEntity entity) { RepairWork.work(entity); }
}
