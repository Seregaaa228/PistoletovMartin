package dev.frontier.industry.repair;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.IndustryEngine;
import dev.frontier.industry.WorkerEntity;
import dev.frontier.industry.engine.Blueprint;
import dev.frontier.industry.engine.ClaimPermissions;
import dev.frontier.industry.engine.IndustryData;
import dev.frontier.industry.engine.IndustryData.Site;
import dev.frontier.industry.engine.IndustryData.Worker;
import dev.frontier.industry.engine.IndustryRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;

/** Brain adapter to the existing site ledger. No entity task can debit stock or finish a repair. */
public final class RepairWork {
    private static final String LAST_PATH = "frontierRepairLastPath", PATH_STARTED = "frontierRepairPathStarted";
    private static final String PATH_SITE = "frontierRepairPathSite";
    private RepairWork() {}

    public static boolean repairWorker(WorkerEntity entity) {
        return RepairRules.isRepairRole(entity.getPersistentData().getString("frontierIndustryRole"));
    }
    public static String requiredRole(Site site) {
        return site.constructing ? "builder" : site.type.isConsumer() ? "factory_repair" : "grid_repair";
    }
    private static Worker worker(WorkerEntity entity) {
        return entity.level() instanceof ServerLevel level ? IndustryData.get(level.getServer()).workers.get(entity.getUUID()) : null;
    }
    private static Site assigned(WorkerEntity entity) {
        Worker worker = worker(entity);
        return worker == null || worker.site == null ? null : IndustryData.get(entity.getServer()).sites.get(worker.site);
    }
    private static boolean authorized(WorkerEntity entity, Site site) {
        Worker worker = worker(entity);
        return site != null && worker != null && worker.active() && site.id.equals(worker.site)
            && worker.owner.equals(site.owner) && IndustryEngine.owned(entity, site.owner)
            && worker.dimension.equals(site.dimension) && entity.level().dimension().location().equals(site.dimension)
            && !site.constructing && requiredRole(site).equals(worker.role)
            && worker.role.equals(entity.getPersistentData().getString("frontierIndustryRole"))
            && entity.level() instanceof ServerLevel level
            && ClaimPermissions.allows(level, site.owner, site.team, Blueprint.entrance(site.type, site.origin, site.blueprintVersion));
    }
    private static boolean available(WorkerEntity entity) {
        return entity.isAlive() && !entity.isRemoved() && !entity.isPassenger() && !IndustryEngine.busy(entity);
    }
    public static boolean ready(WorkerEntity entity, Site site) {
        return RepairRules.mayWork(authorized(entity, site), site != null && entity.level() instanceof ServerLevel level
                && IndustryEngine.loaded(level, site), site != null && site.repairRequested,
            site != null && site.total > 0 && site.matching < site.total, available(entity), site == null ? Double.NaN : approachDistance(entity, site));
    }
    private static double approachDistance(WorkerEntity entity, Site site) {
        BlockPos target = Blueprint.entrance(site.type, site.origin, site.blueprintVersion);
        return entity.distanceToSqr(target.getX() + .5, target.getY(), target.getZ() + .5);
    }
    private static boolean actionable(WorkerEntity entity, Site site) {
        return authorized(entity, site) && available(entity) && site.repairRequested && site.total > 0 && site.matching < site.total
            && entity.level() instanceof ServerLevel level && IndustryEngine.loaded(level, site);
    }
    public static void sense(WorkerEntity entity) {
        if (!repairWorker(entity)) {
            // Production, construction and military/vehicle movement retain their original owner.
            entity.getBrain().eraseMemory(RepairContent.REPAIR_TARGET.get());
            entity.setRepairStatus(RepairStatus.IDLE);
            return;
        }
        Worker worker = worker(entity);
        Site site = assigned(entity);
        if (!available(entity)) { halt(entity, entity.isAlive() ? RepairStatus.OCCUPIED : RepairStatus.DEAD); return; }
        if (worker == null || worker.site == null) { halt(entity, RepairStatus.IDLE); return; }
        if (site == null) { halt(entity, RepairStatus.TARGET_MISSING); return; }
        if (!authorized(entity, site)) { halt(entity, RepairStatus.UNAUTHORIZED); return; }
        if (!IndustryEngine.loaded((ServerLevel) entity.level(), site)) { halt(entity, RepairStatus.UNLOADED); return; }
        if (!site.repairRequested) { halt(entity, site.total > 0 && site.matching >= site.total ? RepairStatus.COMPLETE : RepairStatus.CANCELLED); return; }
        if (site.total > 0 && site.matching >= site.total) { halt(entity, RepairStatus.COMPLETE); return; }
        entity.getBrain().setMemory(RepairContent.REPAIR_TARGET.get(), site.id);
    }
    public static boolean needsApproach(WorkerEntity entity) {
        Site site = assigned(entity);
        return actionable(entity, site) && !ready(entity, site);
    }
    public static boolean atAssignedApproach(WorkerEntity entity) { return ready(entity, assigned(entity)); }
    /** Cheap validity check each tick; target scans/path creation keep their separate cooldowns. */
    public static void refreshState(WorkerEntity entity) {
        Site site = assigned(entity);
        if (!actionable(entity, site)) sense(entity);
        else if (ready(entity, site)) work(entity);
        else if (entity.getRepairStatus() == RepairStatus.REPAIRING) entity.setRepairStatus(RepairStatus.IDLE);
    }

    public static void approach(WorkerEntity entity) {
        Site site = assigned(entity);
        if (!actionable(entity, site)) { sense(entity); return; }
        if (ready(entity, site)) { work(entity); return; }
        long now = entity.level().getGameTime();
        var tag = entity.getPersistentData();
        if (!tag.hasUUID(PATH_SITE) || !site.id.equals(tag.getUUID(PATH_SITE))) {
            entity.getNavigation().stop(); tag.putUUID(PATH_SITE, site.id); tag.remove(LAST_PATH); tag.remove(PATH_STARTED);
        }
        PathNavigation navigation = entity.getNavigation();
        if (!navigation.isDone()) {
            long started = tag.getLong(PATH_STARTED);
            if (now >= started && now - started < RepairRules.PATH_TIMEOUT_TICKS) { entity.setRepairStatus(RepairStatus.MOVING); return; }
            navigation.stop(); tag.putLong(LAST_PATH, now); entity.setRepairStatus(RepairStatus.NO_PATH); return;
        }
        long previous = tag.contains(LAST_PATH) ? tag.getLong(LAST_PATH) : Long.MIN_VALUE;
        if (!RepairRules.pathAttemptDue(previous, now)) { entity.setRepairStatus(RepairStatus.NO_PATH); return; }
        tag.putLong(LAST_PATH, now);
        BlockPos destination = Blueprint.entrance(site.type, site.origin, site.blueprintVersion);
        Path path = navigation.createPath(destination, 0);
        if (path == null || !path.canReach() || !navigation.moveTo(path, 1.0)) { entity.setRepairStatus(RepairStatus.NO_PATH); return; }
        tag.putLong(PATH_STARTED, now); entity.setRepairStatus(RepairStatus.MOVING);
    }
    public static void work(WorkerEntity entity) {
        Site site = assigned(entity);
        if (!ready(entity, site)) { sense(entity); return; }
        entity.getNavigation().stop();
        entity.setRepairStatus(hasKit(site) ? RepairStatus.REPAIRING : RepairStatus.NO_MATERIALS);
    }
    /** Stops only a repair movement, so other civilian roles keep their navigation. */
    public static void halt(WorkerEntity entity, RepairStatus status) {
        if (repairWorker(entity) || entity.getRepairStatus() == RepairStatus.MOVING || entity.getRepairStatus() == RepairStatus.REPAIRING)
            entity.getNavigation().stop();
        entity.getBrain().eraseMemory(RepairContent.REPAIR_TARGET.get());
        entity.setRepairStatus(status);
    }
    private static boolean hasKit(Site site) {
        if (site.repairKit) return true;
        ItemStack required = new ItemStack(IndustryContent.REPAIR_KIT.get());
        for (int slot = 0; slot < IndustryRules.INPUTS; slot++) {
            ItemStack stack = site.inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameTags(stack, required)) return true;
        }
        return false;
    }
    public static String cancel(MinecraftServer server, Site site) {
        RepairTransactions.Cancellation outcome = RepairTransactions.cancel(IndustryData.get(server), site);
        if (outcome == RepairTransactions.Cancellation.NO_ORDER) return "Нет активного приказа ремонта";
        if (outcome == RepairTransactions.Cancellation.NO_SPACE) return "Для возврата ремонтного комплекта освободи входные слоты; приказ сохранён";
        for (Worker worker : IndustryData.get(server).workers.values())
            if (site.id.equals(worker.site) && IndustryEngine.find(server, worker.id) instanceof WorkerEntity entity && repairWorker(entity)) halt(entity, RepairStatus.CANCELLED);
        return "Ремонт отменён; зарезервированный комплект возвращён";
    }
    /** Pure projection: no polling, entity creation, stock changes or chunk loading. */
    public static JsonObject snapshot(MinecraftServer server, Site site) {
        JsonObject result = new JsonObject();
        result.addProperty("requested", site.repairRequested); result.addProperty("progressTicks", site.repairProgress);
        result.addProperty("totalTicks", IndustryRules.REPAIR_TICKS); result.addProperty("kitReserved", site.repairKit);
        result.addProperty("requiredRole", requiredRole(site));
        JsonArray workers = new JsonArray(); int ready = 0, assigned = 0; String reason = "Нужна назначенная ремонтная бригада";
        for (Worker worker : IndustryData.get(server).workers.values()) {
            if (!worker.active() || !site.id.equals(worker.site) || !requiredRole(site).equals(worker.role) || !worker.owner.equals(site.owner)) continue;
            assigned++;
            JsonObject row = new JsonObject(); row.addProperty("id", worker.id.toString());
            if (IndustryEngine.find(server, worker.id) instanceof WorkerEntity entity) {
                RepairStatus status = entity.getRepairStatus();
                if (ready(entity, site)) ready++;
                row.addProperty("state", status.key); row.addProperty("reason", status.reason); reason = status.reason;
            } else { row.addProperty("state", "unloaded"); row.addProperty("reason", RepairStatus.UNLOADED.reason); reason = RepairStatus.UNLOADED.reason; }
            workers.add(row);
        }
        if (!site.repairRequested) reason = "Нет активного приказа ремонта";
        else if (!site.loaded) reason = RepairStatus.UNLOADED.reason;
        else if (ready > 0) reason = hasKit(site) ? RepairStatus.REPAIRING.reason : RepairStatus.NO_MATERIALS.reason;
        result.addProperty("workers", ready); result.addProperty("assignedWorkers", assigned); result.addProperty("reason", reason); result.add("crew", workers);
        return result;
    }
}
