package dev.frontier.industry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import dev.frontier.industry.engine.Blueprint;
import dev.frontier.industry.construction.Construction;
import dev.frontier.industry.research.Research;
import dev.frontier.industry.research.ResearchPolicy;
import dev.frontier.industry.engine.BlueprintBounds;
import dev.frontier.industry.engine.ClaimPermissions;
import dev.frontier.industry.engine.IndustryData;
import dev.frontier.industry.engine.IndustryData.Order;
import dev.frontier.industry.engine.IndustryData.Site;
import dev.frontier.industry.engine.IndustryData.Worker;
import dev.frontier.industry.engine.IndustryRules;
import dev.frontier.industry.engine.StockTransactions;
import dev.frontier.industry.engine.NativeRecruitCustody;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.items.ItemStackHandler;

/** Server-authoritative industry simulation. No chunk tickets, timers based on wall time, or client stock. */
public final class IndustryEngine {
    private static final String OWNER = "frontierIndustryOwner", ROLE = "frontierIndustryRole", SITE = "frontierIndustrySite";
    private static final String SOURCE = "frontierIndustrySource", TRANSFERRED = "frontierIndustryTransferredTo", RETURNED = "frontierIndustryReturnedFrom";
    private static final Map<UUID, RepairSession> REPAIRS = new HashMap<>();
    private IndustryEngine() {}

    public static void tick(MinecraftServer server) {
        IndustryData data = IndustryData.get(server);
        Construction.tick(server);
        if (server.getTickCount() % 20 == 0) reconcileWorkers(server, data);
        for (Site site : data.sites.values()) {
            ServerLevel level = level(server, site);
            boolean wasLoaded = site.loaded;
            site.loaded = level != null && loaded(level, site);
            site.powered = false;
            site.liveProduction = 0;
            if (!site.loaded) { site.status = "Чанки выгружены — пауза"; continue; }
            if(site.constructing) continue;
            site.controller = level.getBlockState(Blueprint.controller(site.type, site.origin, site.blueprintVersion)).is(IndustryContent.controller(site.type));
            if (!wasLoaded || server.getTickCount() % 20 == 0) poll(level, site);
            site.liveProduction = crew(server, data, site, "production").size();
            if (!site.functional()) site.status = !site.controller ? "Контроллер разрушен" : !site.machineryIntact ? "Повреждено силовое оборудование — нужен ремонт" : "Повреждено: требуется 75% целостности";
            else site.status = "Ожидание";
        }
        for (Site site : data.sites.values()) if (site.type == SiteType.POWER_STATION && site.functional()) {
            if (site.burn == 0 && site.energy <= IndustryRules.BUFFER - IndustryRules.GENERATION && takeFuel(site.inventory)) {
                site.burn = 1600; data.setDirty();
            }
            if (site.burn > 0 && site.energy <= IndustryRules.BUFFER - IndustryRules.GENERATION) {
                site.energy += IndustryRules.GENERATION; site.burn--; data.setDirty();
            }
            site.powered = site.energy > 0;
            site.status = site.burn > 0 ? "Генерация 128 FE/т" : site.energy > 0 ? "Резерв энергии" : "Нет угля / древесного угля";
        }
        // Each debit is applied immediately. Multiple downstream consumers cannot spend the same FE.
        for (Site site : data.sites.values()) if (site.type == SiteType.SUBSTATION && site.functional()) {
            Site source = upstream(data, site, SiteType.POWER_STATION);
            if (source != null && source.functional()) moveEnergy(data, source, site, IndustryRules.GENERATION);
            site.powered = source != null && source.functional() && site.energy > 0;
            site.status = site.powered ? "Распределение энергии" : "Нет действующей электростанции";
        }
        for (Site site : data.sites.values()) if (site.type.isConsumer() && site.functional()) {
            Site substation = upstream(data, site, SiteType.SUBSTATION);
            Site plant = substation == null ? null : upstream(data, substation, SiteType.POWER_STATION);
            boolean path = substation != null && substation.functional() && plant != null && plant.functional();
            if(site.type==SiteType.RESEARCH_CENTER){
                if(path)moveEnergy(data,substation,site,Math.max(0,16-site.energy));
                site.powered=path&&site.energy>=16;site.status=site.powered?"Центр готов к исследованиям":"Центру нужно электричество";continue;
            }
            Order current = site.orders.peek();
            IndustryCatalog.Recipe currentRecipe = current == null ? null : exactRecipe(current.recipe, site.type);
            // An idle or jammed factory must not hoard a whole battery before its neighbours get a turn.
            if (path && currentRecipe != null && site.liveProduction > 0 && current.progress < currentRecipe.ticks()
                && StockTransactions.outputFits(site.inventory, currentRecipe.output()))
                moveEnergy(data, substation, site, Math.max(0, currentRecipe.fePerTick() - site.energy));
            site.powered = path && (site.energy > 0 || substation.energy > 0 || plant.energy > 0 || plant.burn > 0);
            if (!path) { site.status = "Нет линии станция → подстанция → завод"; continue; }
            production(server, data, site);
        }
        Research.tick(server);
        if(server.getTickCount()%20==0)for(Site site:data.sites.values())if(site.loaded&&!site.constructing){
            var l=level(server,site);var pos=Blueprint.controller(site.type,site.origin,site.blueprintVersion);var state=l.getBlockState(pos);
            var lit=net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT;
            if(state.is(IndustryContent.controller(site.type))&&state.hasProperty(lit)&&state.getValue(lit)!=(site.powered&&site.functional()))l.setBlock(pos,state.setValue(lit,site.powered&&site.functional()),Block.UPDATE_CLIENTS);
        }
        for (Site site : data.sites.values()) if (site.loaded && site.repairRequested) repairCrew(server, data, site);
        REPAIRS.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            RepairSession session = entry.getValue();
            boolean cancelled = session.server != server || player == null || !player.isUsingItem() || player.getUseItem() != session.kit
                || server.getTickCount() - session.lastTick > 2;
            if (cancelled) clearRepairTarget(session.kit);
            return cancelled;
        });
    }

    public static UUID place(ServerPlayer player, SiteType type, BlockPos origin) {
        if (player == null || type == null || origin == null || !player.isAlive() || player.isSpectator()) return null;
        ServerLevel level = player.serverLevel();
        IndustryData data = IndustryData.get(level.getServer());
        if (data.sites.size() >= 2048 || data.sites.values().stream().filter(s -> s.owner.equals(player.getUUID())).count() >= 64) {
            tell(player, "Достигнут предел промышленных объектов"); return null;
        }
        Site site = new Site(data, UUID.randomUUID(), player.getUUID(), type, level.dimension().location(), origin);
        site.blueprintVersion = Blueprint.CURRENT_VERSION;
        site.team = player.getTeam() == null ? "" : player.getTeam().getName();
        if (distance(site, player.getX(), player.getY(), player.getZ()) > 64 || !loaded(level, site)) {
            tell(player, "Нужна свободная загруженная площадка рядом с игроком"); return null;
        }
        BlueprintBounds footprint = Blueprint.bounds(type, site.blueprintVersion);
        if (data.sites.values().stream().anyMatch(existing -> existing.dimension.equals(site.dimension) && overlaps(existing, site))) {
            tell(player, "Площадка пересекается с другим промышленным объектом"); return null;
        }
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) for (int y = footprint.minY(); y <= footprint.maxY(); y++) {
            BlockPos pos = origin.offset(x, y, z);
            if (!level.getBlockState(pos).isAir() || !ClaimPermissions.allows(level, site.owner, site.team, pos)) {
                tell(player, "Площадка занята, вне границы мира или принадлежит другой стороне"); return null;
            }
        }
        if(!player.isCreative() && !player.getMainHandItem().is(IndustryContent.deployer(type)) && !player.getOffhandItem().is(IndustryContent.deployer(type))) {
            tell(player,"Нужен строительный комплект в руке");return null;
        }
        return Construction.start(player,site);
    }

    public static UUID siteAt(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return null;
        return IndustryData.get(level.getServer()).sites.values().stream()
            .filter(site -> site.dimension.equals(level.dimension().location()) && contains(site, pos)).map(site -> site.id).findFirst().orElse(null);
    }
    public static UUID nearestOwned(ServerPlayer player, int radius) {
        if (player == null || radius < 0) return null;
        double limit = Math.min(radius, 128);
        return IndustryData.get(player.server).sites.values().stream()
            .filter(site -> site.owner.equals(player.getUUID()) && site.dimension.equals(player.level().dimension().location()))
            .filter(site -> distance(site, player.getX(), player.getY(), player.getZ()) <= limit * limit)
            .min(Comparator.comparingDouble(site -> distance(site, player.getX(), player.getY(), player.getZ())))
            .map(site -> site.id).orElse(null);
    }
    public static boolean owns(ServerPlayer player, UUID id) {
        if (player == null || id == null) return false;
        Site site = IndustryData.get(player.server).sites.get(id);
        return site != null && site.owner.equals(player.getUUID()) && site.dimension.equals(player.level().dimension().location());
    }
    public static ItemStackHandler inventory(ServerLevel level, UUID id) {
        if (level == null || id == null) return null;
        Site site = IndustryData.get(level.getServer()).sites.get(id);
        return site != null && site.dimension.equals(level.dimension().location()) ? site.inventory : null;
    }
    public static JsonObject snapshot(ServerPlayer player, UUID id) {
        JsonObject result = new JsonObject();
        Site site = player == null || id == null ? null : IndustryData.get(player.server).sites.get(id);
        if (site == null || !owns(player, id)) { result.addProperty("message", "Объект недоступен"); return result; }
        result.addProperty("id", site.id.toString()); result.addProperty("type", site.type.name()); result.addProperty("title", site.type.title());
        result.addProperty("status", site.status); result.addProperty("health", site.total == 0 ? 0 : Math.round(100f * site.matching / site.total));
        result.addProperty("powered", site.powered); result.addProperty("workers", site.liveProduction);
        result.addProperty("energy", site.energy); result.addProperty("loaded", site.loaded);
        result.addProperty("blueprintVersion", site.blueprintVersion); result.addProperty("machineryIntact", site.machineryIntact);
        result.addProperty("repairWorkers", crew(player.server, IndustryData.get(player.server), site, site.constructing?"builder":site.type.isConsumer()?"factory_repair":"grid_repair").size());
        result.addProperty("fuelTicks", site.burn);
        result.add("construction",Construction.snapshot(site));
        if(site.type==SiteType.RESEARCH_CENTER)result.add("research",Research.snapshot(player,site));
        int fuel = 0;
        for (int slot = 0; slot < IndustryRules.INPUTS; slot++) {
            ItemStack stack = site.inventory.getStackInSlot(slot);
            if (stack.is(Items.COAL) || stack.is(Items.CHARCOAL)) fuel += stack.getCount();
        }
        result.addProperty("fuel", fuel);
        Order order = site.orders.peek();
        IndustryCatalog.Recipe recipe = order == null ? null : exactRecipe(order.recipe, site.type);
        result.addProperty("progress", order == null ? 0 : order.progress); result.addProperty("total", recipe == null ? 0 : recipe.ticks());
        result.addProperty("repairRemaining", site.repairRequested ? Math.max(0, IndustryRules.REPAIR_TICKS - site.repairProgress) : 0);
        result.addProperty("x", site.origin.getX()); result.addProperty("y", site.origin.getY()); result.addProperty("z", site.origin.getZ());
        JsonArray orders = new JsonArray();
        for (Order queued : site.orders) {
            JsonObject o = new JsonObject(); o.addProperty("id", queued.recipe); o.addProperty("quantity", queued.quantity); o.addProperty("progress", queued.progress); o.addProperty("reserved", queued.reserved); orders.add(o);
        }
        result.add("orders", orders);
        JsonArray workers = new JsonArray();
        for (Worker worker : IndustryData.get(player.server).workers.values()) if (worker.owner.equals(player.getUUID()) && worker.active()) {
            JsonObject w = new JsonObject(); w.addProperty("id", worker.id.toString()); w.addProperty("role", worker.role);
            w.addProperty("site", worker.site == null ? "" : worker.site.toString()); w.addProperty("transferred", worker.transferred());
            Entity live = find(player.server, worker.id); w.addProperty("loaded", live instanceof WorkerEntity && live.isAlive());
            w.addProperty("name", live == null ? "Рабочий " + worker.id.toString().substring(0, 8) : live.getName().getString()); workers.add(w);
        }
        result.add("workersAvailable", workers);
        if(site.constructing){result.addProperty("progress",site.constructionTicks);result.addProperty("total",site.constructionDuration);}
        else if(site.type==SiteType.RESEARCH_CENTER && result.has("research")){
            var research=result.getAsJsonObject("research");if(research.has("total")){result.add("progress",research.get("progress"));result.add("total",research.get("total"));}
        }
        return result;
    }

    public static boolean canAccess(ServerPlayer player, UUID id) {
        return player != null && accessible(player, id, IndustryRules.INTERACT_RANGE) != null;
    }
    public static void action(ServerPlayer player, UUID id, String action, String arg, int value) {
        if (player == null || action == null || arg == null || action.length() > 64 || arg.length() > 128) return;
        Site site = accessible(player, id, IndustryRules.INTERACT_RANGE);
        if (site == null) { tell(player, "Нужен свой загруженный объект в радиусе 12 блоков"); return; }
        IndustryData data = IndustryData.get(player.server);
        if(action.equals("construction_staff")){Construction.staff(player,site);return;}
        if(action.equals("construction_cancel")){Construction.cancel(player,site);return;}
        if(action.equals("research_start")||action.equals("research_staff")||action.equals("research_supply")){Research.action(player,site,action,arg);return;}
        if(site.constructing && !java.util.Set.of("staff","repair","repair_staff","fuel","collect","assign","unassign","dismiss").contains(action)){tell(player,"Сначала заверши строительство");return;}
        switch (action) {
            case "order" -> {
                if (!site.type.isFactory() || value < 1 || value > 64 || site.orders.size() >= IndustryRules.MAX_QUEUE) { tell(player, "Недопустимый заказ или очередь заполнена"); return; }
                IndustryCatalog.Recipe recipe = IndustryCatalog.resolve(arg, site.type);
                if (recipe == null || recipe.output().isEmpty() || recipe.ticks() < 1 || recipe.ticks() > 72000 || recipe.fePerTick() < 1 || recipe.fePerTick() > 128 || recipe.cost().isEmpty()) {
                    tell(player, "Предмет не найден, неоднозначен или не производится этим заводом"); return;
                }
                String denial=ResearchPolicy.recipeDenial(player.server,site.owner,recipe);
                if(!denial.isEmpty()&&!Research.admin(player)){tell(player,denial);return;}
                site.orders.add(new Order(recipe.id(), value)); data.setDirty(); tell(player, "Заказ: " + recipe.title() + " × " + value + ". Материалы списываются за каждую единицу перед работой.");
            }
            case "hire" -> hire(player, data, site, arg);
            case "staff" -> {if(site.constructing)Construction.staff(player,site);else staff(player,data,site,site.type==SiteType.RESEARCH_CENTER?"scientist":site.type.isFactory()?"production":"grid_repair");}
            case "repair_staff" -> {if(site.constructing)Construction.staff(player,site);else staff(player,data,site,site.type.isConsumer()?"factory_repair":"grid_repair");}
            case "assign" -> assign(player, data, site, parse(arg), role(value));
            case "unassign" -> unassign(player, data, parse(arg));
            case "dismiss" -> dismiss(player, data, parse(arg));
            case "repair" -> {
                poll(player.serverLevel(), site);
                if (site.matching == site.total) { tell(player, "Объект цел"); return; }
                if (!site.repairKit) supplyFromPlayer(player, site, List.of(new ItemStack(IndustryContent.REPAIR_KIT.get())), 1);
                site.repairRequested = true; data.setDirty(); tell(player, "Ремонт заказан: нужен ремонтный рабочий нужного типа и комплект во входных слотах. Работа — 90 секунд.");
            }
            case "supply" -> {
                IndustryCatalog.Recipe recipe = site.type.isFactory() ? exactRecipe(arg, site.type) : null;
                if (recipe == null || value < 1 || value > 64) { tell(player, "Выбери допустимый рецепт этого завода и количество от 1 до 64"); return; }
                int moved = supplyFromPlayer(player, site, recipe.cost(), value);
                tell(player, "На склад перенесено предметов: " + moved + ". Загружаются только недостающие материалы выбранного рецепта из твоего инвентаря.");
            }
            case "fuel" -> fuel(player, site);
            case "collect" -> {
                for (int slot = IndustryRules.INPUTS; slot < IndustryRules.SLOTS; slot++) {
                    ItemStack remaining = site.inventory.getStackInSlot(slot).copy();
                    if (!remaining.isEmpty()) { player.getInventory().add(remaining); site.inventory.setStackInSlot(slot, remaining); }
                }
            }
            case "cancel" -> {
                Order order = site.orders.peek();
                if (order != null && (!order.reserved || StockTransactions.refundInputs(site.inventory, order.paid))) {
                    site.orders.remove(); data.setDirty(); tell(player, "Первый заказ отменён; зарезервированные материалы возвращены");
                } else if (order != null) tell(player, "Для возврата материалов освободи входные слоты");
            }
            default -> tell(player, "Неизвестное действие");
        }
    }

    public static boolean link(ServerPlayer player, UUID sourceId, UUID targetId) {
        if (player == null || sourceId == null || targetId == null || sourceId.equals(targetId)) return false;
        IndustryData data = IndustryData.get(player.server);
        Site source = data.sites.get(sourceId), target = accessible(player, targetId, IndustryRules.INTERACT_RANGE);
        if (source == null || target == null || !source.owner.equals(player.getUUID())
            || !IndustryRules.linkAllowed(source.owner.equals(target.owner), source.dimension.equals(target.dimension), source.type.ordinal(), target.type.ordinal(), source.origin.distSqr(target.origin))
            || !loaded(player.serverLevel(), source)) {
            tell(player, "Линия: только свои станция → подстанция → завод, не дальше 128 блоков и в одном измерении"); return false;
        }
        target.upstream = source.id; target.energy = 0; data.setDirty(); return true;
    }

    public static boolean selectWorker(ServerPlayer player, LivingEntity selected, ItemStack tablet) {
        if (player == null || selected == null || tablet == null || tablet.isEmpty() || !IndustryContent.isTablet(tablet)
            || !selected.isAlive() || selected.level() != player.level() || selected.distanceToSqr(player) > 64) return false;
        IndustryData data = IndustryData.get(player.server);
        if (selected instanceof WorkerEntity civilian) {
            Worker worker = data.workers.get(civilian.getUUID());
            if (worker == null || !worker.active() || !worker.owner.equals(player.getUUID()) || !owned(civilian, player.getUUID())) return false;
            tablet.getOrCreateTag().putUUID("SelectedWorker", civilian.getUUID()); tell(player, "Рабочий выбран: " + civilian.getName().getString()); return true;
        }
        if (!(selected instanceof AbstractRecruitEntity recruit) || !recruit.getIsOwned() || !player.getUUID().equals(recruit.getOwnerUUID())) {
            tell(player, "Можно выбрать своего гражданского рабочего или собственного рекрута"); return false;
        }
        if (!NativeRecruitCustody.ready(recruit)) { tell(player, "Перевод остановлен: рекрут должен быть учтён в готовом армейском реестре Recruits"); return false; }
        long active = data.workers.values().stream().filter(w -> w.owner.equals(player.getUUID()) && w.active()).count();
        long transferred = data.workers.values().stream().filter(w -> w.owner.equals(player.getUUID()) && w.active() && w.transferred()).count();
        if (!IndustryRules.canTransfer(active, transferred) || recruit.isPassenger() || recruit.isVehicle() || busy(recruit)) {
            tell(player, "Перевод запрещён: максимум 7 действующих армейских переводов, 64 рабочих; рекрут должен быть свободен"); return false;
        }
        // Never overwrite an earlier journal for the same original recruit UUID.
        if (data.workers.values().stream().anyMatch(w -> recruit.getUUID().equals(w.sourceId) && !w.returned)) {
            tell(player, "Для этого рекрута уже существует запись перевода"); return false;
        }
        CompoundTag source = new CompoundTag();
        if (!recruit.save(source)) { tell(player, "Не удалось сохранить рекрута: перевод отменён"); return false; }
        WorkerEntity civilian = IndustryContent.WORKER.get().create(player.serverLevel());
        if (civilian == null) return false;
        Worker worker = new Worker(civilian.getUUID(), player.getUUID(), "production", player.level().dimension().location());
        worker.sourceId = recruit.getUUID(); worker.source = source.copy();
        initialize(civilian, worker); civilian.moveTo(recruit.getX(), recruit.getY(), recruit.getZ(), recruit.getYRot(), recruit.getXRot());
        civilian.setCustomName(Component.literal("Рабочий (" + recruit.getName().getString() + ")"));
        civilian.getPersistentData().putUUID(SOURCE, recruit.getUUID());
        data.workers.put(worker.id, worker); data.setDirty();
        recruit.getPersistentData().putUUID(TRANSFERRED, worker.id);
        if (!player.serverLevel().addFreshEntity(civilian)) {
            data.workers.remove(worker.id); data.setDirty(); recruit.getPersistentData().remove(TRANSFERRED); civilian.discard();
            tell(player, "Не удалось создать рабочего; рекрут и его снаряжение сохранены"); return false;
        }
        if (!NativeRecruitCustody.debit(data, worker, recruit)) {
            data.workers.remove(worker.id); civilian.discard(); recruit.getPersistentData().remove(TRANSFERRED); data.setDirty();
            tell(player, "Армейский реестр изменился: перевод отменён, рекрут сохранён"); return false;
        }
        // DISCARDED does not invoke death/loot/inventory drops. The only equipment copy stays in custody NBT.
        recruit.discard();
        tablet.getOrCreateTag().putUUID("SelectedWorker", worker.id);
        tell(player, "Рекрут переведён. Снаряжение хранится до увольнения; действующих переводов: " + (transferred + 1) + "/7");
        return true;
    }

    public static boolean beginRepair(ServerPlayer player, ItemStack kit) {
        if (player == null || kit == null || kit.isEmpty() || !kit.is(IndustryContent.REPAIR_KIT.get()) || !player.isAlive() || player.isSpectator()) return false;
        RepairSession existing = REPAIRS.get(player.getUUID());
        // Duplicate use packets do not restart vanilla's remaining item-use duration. Preserve its matching session too.
        if (existing != null && player.isUsingItem() && player.getUseItem() == kit && repairSessionValid(player, kit, existing)) return true;
        cancelRepair(player, kit);
        IndustryData data = IndustryData.get(player.server);
        Site chosen = null;
        HitResult hit = player.pick(8, 0, false);
        if (hit instanceof BlockHitResult blockHit) {
            UUID id = siteAt(player.serverLevel(), blockHit.getBlockPos());
            Site pointed = id == null ? null : accessible(player, id, IndustryRules.REPAIR_RANGE);
            if (pointed != null) { poll(player.serverLevel(), pointed); if (pointed.matching < pointed.total) chosen = pointed; }
        }
        if (chosen == null) chosen = data.sites.values().stream()
            .filter(site -> site.owner.equals(player.getUUID()) && site.dimension.equals(player.level().dimension().location()) && loaded(player.serverLevel(), site))
            .filter(site -> distance(site, player.getX(), player.getY(), player.getZ()) <= 64)
            .peek(site -> poll(player.serverLevel(), site)).filter(site -> site.matching < site.total)
            .min(Comparator.comparingDouble(site -> distance(site, player.getX(), player.getY(), player.getZ()))).orElse(null);
        if (chosen == null) { tell(player, "Рядом нет повреждённого своего объекта (8 блоков)"); return false; }
        kit.getOrCreateTag().putUUID("IndustryRepairSite", chosen.id);
        REPAIRS.put(player.getUUID(), new RepairSession(player.server, chosen.id, kit, player.server.getTickCount()));
        tell(player, "Удерживай комплект 90 секунд рядом с объектом"); return true;
    }
    public static void repairTick(ServerPlayer player, ItemStack kit, int remainingTicks) {
        RepairSession session = player == null ? null : REPAIRS.get(player.getUUID());
        if (session == null) { clearRepairTarget(kit); return; }
        int now = player.server.getTickCount();
        if (!repairSessionValid(player, kit, session) || remainingTicks < 0 || remainingTicks > IndustryRules.REPAIR_TICKS || now - session.lastTick > 2) {
            cancelRepair(player, kit); player.stopUsingItem(); tell(player, "Ремонт прерван: удерживай тот же комплект рядом со своим объектом"); return;
        }
        session.lastTick = now;
        if (session.countTick != now) { session.heldTicks++; session.countTick = now; }
        if (session.heldTicks % 20 == 0)
            player.displayClientMessage(Component.literal("Ремонт: " + (session.heldTicks / 20) + " / 90 с"), true);
    }
    public static void finishRepair(ServerPlayer player, ItemStack kit) {
        RepairSession session = player == null ? null : REPAIRS.remove(player.getUUID());
        if (session == null || !repairSessionValid(player, kit, session) || session.heldTicks < IndustryRules.REPAIR_TICKS
            || player.server.getTickCount() - session.lastTick > 1) {
            if (session != null) clearRepairTarget(session.kit);
            clearRepairTarget(kit); return;
        }
        Site site = IndustryData.get(player.server).sites.get(session.site);
        int restored = restore(player.serverLevel(), site);
        if (restored > 0) {
            kit.shrink(1); // Exactly one even in creative: the completion transaction always pays for restoration.
            poll(player.serverLevel(), site); IndustryData.get(player.server).setDirty();
            tell(player, "Восстановлено блоков: " + restored + ". Посторонние блоки и контейнеры сохранены.");
        } else tell(player, "Ремонт завершён без замены: посторонние блоки не перезаписываются. Комплект сохранён.");
        clearRepairTarget(kit);
    }

    /** Called on vanilla early release as well as all server-side cancellation paths. */
    public static void cancelRepair(ServerPlayer player, ItemStack kit) {
        RepairSession session = player == null ? null : REPAIRS.remove(player.getUUID());
        if (session != null) clearRepairTarget(session.kit);
        clearRepairTarget(kit);
    }
    private static void clearRepairTarget(ItemStack kit) {
        if (kit == null || !kit.hasTag()) return;
        CompoundTag tag = kit.getTag(); tag.remove("IndustryRepairSite");
        if (tag.isEmpty()) kit.setTag(null);
    }

    public static void workerTick(WorkerEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return;
        IndustryData data = IndustryData.get(level.getServer());
        Worker worker = data.workers.get(entity.getUUID());
        if (worker == null) {
            // An old chunk containing a completed return must not reintroduce a second worker.
            if (entity.getPersistentData().hasUUID(SOURCE)) {
                Entity source = find(level.getServer(), entity.getPersistentData().getUUID(SOURCE));
                if (source != null && source.getPersistentData().hasUUID(RETURNED) && source.getPersistentData().getUUID(RETURNED).equals(entity.getUUID())) entity.discard();
            }
            return;
        }
        if (!worker.active() || !owned(entity, worker.owner)) { entity.discard(); return; }
        if (!worker.dimension.equals(level.dimension().location())) { worker.dimension = level.dimension().location(); data.setDirty(); }
        if (entity.isPassenger() || busy(entity)) return;
        if(Construction.workerTick(entity))return;
        if(entity.tickCount%20!=0)return;
        Site site = worker.site == null ? null : data.sites.get(worker.site);
        if (site == null || !site.dimension.equals(worker.dimension) || !loaded(level, site)) return;
        BlockPos destination = site.type == SiteType.SUBSTATION ? site.origin.offset(0, 1, -1) : site.origin.offset(0, 1, 0);
        if (entity.distanceToSqr(destination.getX() + .5, destination.getY(), destination.getZ() + .5) > 4)
            entity.getNavigation().moveTo(destination.getX() + .5, destination.getY(), destination.getZ() + .5, 1.0);
        else entity.getNavigation().stop();
    }
    public static void workerDied(WorkerEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return;
        IndustryData data = IndustryData.get(level.getServer());
        Worker worker = data.workers.get(entity.getUUID());
        if (worker != null) {
            worker.dead = true; worker.site = null;
            // Retain the transfer tombstone. Death cannot mint the recruit/equipment a second time.
            worker.source = worker.source == null ? null : new CompoundTag();
            data.setDirty();
        }
    }

    private static void production(MinecraftServer server, IndustryData data, Site site) {
        Order order = site.orders.peek();
        if (order == null) { site.status = site.powered ? "Готов к заказам" : "Нет энергии"; return; }
        IndustryCatalog.Recipe recipe = exactRecipe(order.recipe, site.type);
        if (recipe == null) { site.status = "Рецепт недоступен; отмени заказ для возврата материалов"; return; }
        if (!StockTransactions.outputFits(site.inventory, recipe.output())) { site.status = "Выход заполнен — пауза без потери материалов"; return; }
        // A paid, finished unit survives output congestion/restart. Inserting it does not charge twice.
        if (order.reserved && order.progress >= recipe.ticks()) { complete(data, site, order, recipe); return; }
        if (site.liveProduction < 1) { site.status = "Нет живых назначенных рабочих рядом"; return; }
        if (site.energy < recipe.fePerTick()) { site.status = "Недостаточно FE"; site.powered = false; return; }
        if (!order.reserved) {
            String denial=ResearchPolicy.recipeDenial(server,site.owner,recipe);
            if(!denial.isEmpty()){site.status=denial;return;}
            List<ItemStack> costs = recipe.cost();
            if (!StockTransactions.takeInputs(site.inventory, costs)) { site.status = "Ожидание материалов во входных слотах"; return; }
            order.paid.clear(); for (ItemStack stack : costs) order.paid.add(stack.copy());
            order.reserved = true; data.setDirty();
        }
        site.energy -= recipe.fePerTick();
        order.progress = Math.min(recipe.ticks(), order.progress + Math.min(site.liveProduction, 4));
        site.status = "Производство: " + recipe.title(); data.setDirty();
        if (order.progress >= recipe.ticks()) complete(data, site, order, recipe);
    }
    private static void complete(IndustryData data, Site site, Order order, IndustryCatalog.Recipe recipe) {
        if (!StockTransactions.putOutputs(site.inventory, recipe.output())) { site.status = "Выход заполнен"; return; }
        order.quantity--; order.progress = 0; order.reserved = false; order.paid.clear();
        if (order.quantity == 0) site.orders.removeFirst();
        data.setDirty();
    }
    private static void repairCrew(MinecraftServer server, IndustryData data, Site site) {
        ServerLevel level = level(server, site);
        if (level == null || !loaded(level, site)) return;
        if (site.matching >= site.total) {
            if (site.repairKit && !StockTransactions.refundInputs(site.inventory, List.of(new ItemStack(IndustryContent.REPAIR_KIT.get())))) return;
            site.repairKit = false; site.repairRequested = false; site.repairProgress = 0; data.setDirty(); return;
        }
        String requiredRole = site.constructing?"builder":site.type.isConsumer()?"factory_repair":"grid_repair";
        if (crew(server, data, site, requiredRole).isEmpty()) { site.status = "Ремонт: нужен " + roleTitle(requiredRole) + " рядом"; return; }
        if (!site.repairKit) {
            if (!StockTransactions.takeInputs(site.inventory, List.of(new ItemStack(IndustryContent.REPAIR_KIT.get())))) { site.status = "Ремонт: нужен комплект во входных слотах"; return; }
            site.repairKit = true; data.setDirty();
        }
        site.repairProgress = IndustryRules.repairStep(site.repairProgress, true, true, true); data.setDirty();
        site.status = "Ремонт: " + (IndustryRules.REPAIR_TICKS - site.repairProgress) + " тиков";
        if (site.repairProgress < IndustryRules.REPAIR_TICKS) return;
        int restored = restore(level, site);
        if (restored == 0) { site.status = "Ремонт: убери посторонние блоки; комплект зарезервирован"; return; }
        site.repairKit = false; site.repairRequested = false; site.repairProgress = 0;
        poll(level, site); data.setDirty();
        site.status = site.matching < site.total ? "Ремонт окончен; посторонние блоки сохранены" : "Ремонт окончен";
    }
    private static int restore(ServerLevel level, Site site) {
        if (site == null || !loaded(level, site)) return 0;
        int restored = 0;
        for (var entry : Construction.repairBlocks(site).entrySet()) {
            BlockPos pos = entry.getKey(); BlockState current = level.getBlockState(pos);
            if (IndustryRules.repairMayReplace(current.equals(entry.getValue()), current.isAir(), level.getBlockEntity(pos) != null)
                && ClaimPermissions.allows(level, site.owner, site.team, pos) && level.setBlock(pos, entry.getValue(), Block.UPDATE_ALL)) restored++;
        }
        return restored;
    }
    private static int supplyFromPlayer(ServerPlayer player, Site site, List<ItemStack> costs, int units) {
        List<ItemStack> sources = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) sources.add(player.getInventory().getItem(slot));
        int moved = StockTransactions.supplyMissingInputs(site.inventory, sources, costs, units);
        if (moved > 0) player.getInventory().setChanged();
        return moved;
    }
    public static void staff(ServerPlayer player, IndustryData data, Site site, String role) {
        if (!validRole(role) || !roleFits(site, role)) return;
        Worker candidate = data.workers.values().stream()
            .filter(worker -> worker.active() && worker.site == null && worker.owner.equals(player.getUUID()) && worker.dimension.equals(site.dimension))
            .filter(worker -> find(player.server, worker.id) instanceof WorkerEntity civilian && civilian.isAlive() && owned(civilian, player.getUUID())
                && civilian.level() == player.level() && civilian.distanceToSqr(player) <= 64 * 64 && !civilian.isPassenger() && !busy(civilian))
            .min(Comparator.comparingDouble(worker -> find(player.server, worker.id).distanceToSqr(player))).orElse(null);
        if (candidate == null) hire(player, data, site, role);
        else assign(player, data, site, candidate.id, role);
    }
    private static void hire(ServerPlayer player, IndustryData data, Site site, String role) {
        if (!validRole(role) || !roleFits(site, role)) { tell(player, "Для сети нужен электромонтёр; для завода — производственный рабочий или заводской ремонтник"); return; }
        long active = data.workers.values().stream().filter(w -> w.active() && w.owner.equals(player.getUUID())).count();
        if (!IndustryRules.canHire(active) || (role.equals("production") && assigned(data, site, role) >= IndustryRules.MAX_PRODUCTION)) { tell(player, "Предел: 64 гражданских работника на владельца, 12 производственных на завод"); return; }
        if (countItem(player, Items.EMERALD) < 8) { tell(player, "Наём стоит 8 изумрудов"); return; }
        BlockPos spawn = spawnPosition(player.serverLevel(), site);
        if (spawn == null) { tell(player, "Для рабочего нет безопасного места у входа"); return; }
        WorkerEntity entity = IndustryContent.WORKER.get().create(player.serverLevel());
        if (entity == null) return;
        Worker worker = new Worker(entity.getUUID(), player.getUUID(), role, site.dimension); worker.site = site.id;
        initialize(entity, worker); entity.moveTo(spawn.getX() + .5, spawn.getY(), spawn.getZ() + .5, player.getYRot(), 0);
        entity.setCustomName(Component.literal(roleTitle(role)));
        data.workers.put(worker.id, worker); data.setDirty();
        if (!player.serverLevel().addFreshEntity(entity)) { data.workers.remove(worker.id); entity.discard(); data.setDirty(); return; }
        // Single-threaded inventory debit follows successful spawn; preflight checked the whole cost.
        takePlayerItem(player, Items.EMERALD, 8); tell(player, "Нанят " + roleTitle(role) + " за 8 изумрудов");
    }
    private static void assign(ServerPlayer player, IndustryData data, Site site, UUID id, String role) {
        Worker worker = id == null ? null : data.workers.get(id);
        Entity entity = id == null ? null : find(player.server, id);
        if (worker == null || !worker.active() || !worker.owner.equals(player.getUUID()) || !(entity instanceof WorkerEntity civilian)
            || !owned(civilian, player.getUUID()) || !civilian.isAlive() || civilian.level() != player.level() || civilian.distanceToSqr(player) > 64 * 64
            || !validRole(role) || !roleFits(site, role) || civilian.isPassenger() || busy(civilian)) { tell(player, "Выбери своего свободного живого рабочего в этом измерении (64 блока)"); return; }
        if (role.equals("production") && (!site.id.equals(worker.site) || !role.equals(worker.role)) && assigned(data, site, role) >= IndustryRules.MAX_PRODUCTION) {
            tell(player, "На заводе уже назначено 12 производственных работников"); return;
        }
        worker.site = site.id; worker.role = role; initialize(civilian, worker); data.setDirty(); tell(player, "Назначен: " + roleTitle(role));
    }
    private static void unassign(ServerPlayer player, IndustryData data, UUID id) {
        Worker worker = id == null ? null : data.workers.get(id);
        Entity entity = id == null ? null : find(player.server, id);
        if (worker == null || !worker.active() || !worker.owner.equals(player.getUUID()) || !(entity instanceof WorkerEntity civilian)
            || civilian.level() != player.level() || civilian.distanceToSqr(player) > 64 * 64 || busy(civilian) || civilian.isPassenger()) { tell(player, "Рабочий недоступен или занят военной/транспортной задачей"); return; }
        worker.site = null; initialize(civilian, worker); civilian.getNavigation().stop(); data.setDirty(); tell(player, "Рабочий освобождён от объекта");
    }
    private static void dismiss(ServerPlayer player, IndustryData data, UUID id) {
        Worker worker = id == null ? null : data.workers.get(id);
        Entity live = id == null ? null : find(player.server, id);
        if (worker == null || !worker.active() || !worker.owner.equals(player.getUUID()) || !(live instanceof WorkerEntity civilian) || !civilian.isAlive()
            || civilian.level() != player.level() || civilian.distanceToSqr(player) > 64 * 64 || civilian.isPassenger() || busy(civilian)) {
            tell(player, "Увольнение требует своего живого свободного рабочего рядом (64 блока)"); return;
        }
        if (!worker.transferred()) {
            worker.returned = true; worker.site = null; data.setDirty(); civilian.discard(); tell(player, "Гражданский рабочий уволен"); return;
        }
        if (worker.source.isEmpty()) { tell(player, "Архив рекрута недоступен; рабочий сохранён"); return; }
        Entity existing = find(player.server, worker.sourceId);
        if (existing != null) {
            tell(player, "Возврат остановлен: исходный UUID уже загружен. Рабочий и архив сохранены."); return;
        }
        worker.returning = true; data.setDirty();
        Entity restored = EntityType.loadEntityRecursive(worker.source.copy(), player.serverLevel(), entity -> entity);
        if (!(restored instanceof AbstractRecruitEntity recruit) || !recruit.getIsOwned() || !player.getUUID().equals(recruit.getOwnerUUID())) {
            worker.returning = false; data.setDirty(); tell(player, "Архив не прошёл проверку владельца; рабочий сохранён"); return;
        }
        recruit.getPersistentData().remove(TRANSFERRED); recruit.getPersistentData().putUUID(RETURNED, worker.id);
        recruit.moveTo(civilian.getX(), civilian.getY(), civilian.getZ(), civilian.getYRot(), civilian.getXRot());
        if (!player.serverLevel().addFreshEntity(recruit)) {
            worker.returning = false; data.setDirty(); recruit.discard(); tell(player, "Не удалось вернуть рекрута: рабочий и архив сохранены"); return;
        }
        if (!NativeRecruitCustody.credit(data, worker, recruit)) {
            recruit.discard(); worker.returning = false; data.setDirty(); tell(player, "Армейский реестр не готов: рабочий и архив сохранены"); return;
        }
        worker.returned = true; worker.returning = false; worker.site = null;
        // Keep a minimal durable tombstone to suppress stale worker chunks after the original returns.
        worker.source = new CompoundTag(); data.setDirty(); civilian.discard();
        tell(player, "Рекрут возвращён с исходным UUID и сохранённым снаряжением");
    }

    private static void reconcileWorkers(MinecraftServer server, IndustryData data) {
        for (Worker worker : data.workers.values()) {
            Entity live = find(server, worker.id);
            Entity source = worker.sourceId == null ? null : find(server, worker.sourceId);
            if (worker.returned) { if (live instanceof WorkerEntity) live.discard(); continue; }
            if (source != null && source.getPersistentData().hasUUID(RETURNED) && source.getPersistentData().getUUID(RETURNED).equals(worker.id)) {
                if (!(source instanceof AbstractRecruitEntity recruit) || !NativeRecruitCustody.credit(data, worker, recruit)) continue;
                worker.returned = true; worker.returning = false; worker.site = null; worker.source = new CompoundTag();
                if (live instanceof WorkerEntity) live.discard(); data.setDirty(); continue;
            }
            if (source instanceof AbstractRecruitEntity recruit && worker.sourceId != null && recruit.getIsOwned() && worker.owner.equals(recruit.getOwnerUUID())) {
                // The journal proves custody, including when the corresponding worker chunk is currently absent.
                if (worker.nativeDebited || NativeRecruitCustody.debit(data, worker, recruit)) recruit.discard();
            }
            if (live instanceof WorkerEntity civilian && worker.active()) initialize(civilian, worker);
        }
    }
    public static void initialize(WorkerEntity entity, Worker worker) {
        entity.getPersistentData().putBoolean("frontierIndustryWorker", true);
        entity.getPersistentData().putString(OWNER, worker.owner.toString());
        if (!busy(entity)) entity.getPersistentData().putString(ROLE, worker.role);
        if (worker.site == null) entity.getPersistentData().remove(SITE); else entity.getPersistentData().putString(SITE, worker.site.toString());
        entity.setPersistenceRequired(); entity.setCanPickUpLoot(false);
    }
    public static List<WorkerEntity> crew(MinecraftServer server, IndustryData data, Site site, String role) {
        List<WorkerEntity> result = new ArrayList<>();
        for (Worker worker : data.workers.values()) if (worker.active() && worker.owner.equals(site.owner) && site.id.equals(worker.site) && role.equals(worker.role)) {
            Entity live = find(server, worker.id);
            if (live instanceof WorkerEntity entity && entity.isAlive() && entity.level().dimension().location().equals(site.dimension)
                && owned(entity, site.owner) && role.equals(entity.getPersistentData().getString(ROLE)) && !entity.isPassenger() && !busy(entity)
                && distance(site, entity.getX(), entity.getY(), entity.getZ()) <= 36) result.add(entity);
        }
        return result;
    }
    private static int assigned(IndustryData data, Site site, String role) {
        return (int) data.workers.values().stream().filter(w -> w.active() && site.id.equals(w.site) && role.equals(w.role)).count();
    }
    private static boolean roleFits(Site site, String role) {
        return site.constructing ? role.equals("builder") : site.type==SiteType.RESEARCH_CENTER ? role.equals("scientist")||role.equals("factory_repair") : site.type.isFactory() ? role.equals("production") || role.equals("factory_repair") : role.equals("grid_repair");
    }
    private static boolean validRole(String role) { return java.util.Set.of("production","grid_repair","factory_repair","scientist","builder").contains(role); }
    private static String role(int value) { return switch (value) { case 0 -> "production"; case 1 -> "grid_repair"; case 2 -> "factory_repair"; case 3 -> "scientist"; case 4 -> "builder"; default -> ""; }; }
    private static String roleTitle(String role) { return switch (role) { case "production" -> "производственный рабочий"; case "grid_repair" -> "электромонтёр"; case "factory_repair" -> "заводской ремонтник"; case "scientist" -> "учёный"; case "builder" -> "строитель"; default -> "рабочий"; }; }
    public static boolean owned(LivingEntity entity, UUID owner) { return owner.toString().equals(entity.getPersistentData().getString(OWNER)); }
    public static boolean busy(LivingEntity entity) {
        CompoundTag tag = entity.getPersistentData();
        return !tag.getString("frontierIndustryMilitaryStation").isEmpty() || tag.hasUUID("frontierIndustryMilitaryStation")
            || !tag.getString("frontierIndustryVehicle").isEmpty() || tag.hasUUID("frontierIndustryVehicle");
    }
    private static UUID parse(String value) { try { return UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; } }
    public static Entity find(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) { Entity entity = level.getEntity(id); if (entity != null) return entity; }
        return null;
    }
    public static ServerLevel level(MinecraftServer server, Site site) { return server.getLevel(ResourceKey.create(Registries.DIMENSION, site.dimension)); }
    private static Site accessible(ServerPlayer player, UUID id, int radius) {
        if (player == null || id == null || !player.isAlive() || player.isSpectator()) return null;
        Site site = IndustryData.get(player.server).sites.get(id);
        return site != null && site.owner.equals(player.getUUID()) && site.dimension.equals(player.level().dimension().location())
            && distance(site, player.getX(), player.getY(), player.getZ()) <= (double) radius * radius && loaded(player.serverLevel(), site) ? site : null;
    }
    private static boolean contains(Site site, BlockPos pos) {
        return Blueprint.bounds(site.type, site.blueprintVersion).contains(pos.getX() - site.origin.getX(), pos.getY() - site.origin.getY(), pos.getZ() - site.origin.getZ());
    }
    private static boolean overlaps(Site first, Site second) {
        BlueprintBounds a = Blueprint.bounds(first.type, first.blueprintVersion), b = Blueprint.bounds(second.type, second.blueprintVersion);
        return first.origin.getX() + a.minX() <= second.origin.getX() + b.maxX() && first.origin.getX() + a.maxX() >= second.origin.getX() + b.minX()
            && first.origin.getY() + a.minY() <= second.origin.getY() + b.maxY() && first.origin.getY() + a.maxY() >= second.origin.getY() + b.minY()
            && first.origin.getZ() + a.minZ() <= second.origin.getZ() + b.maxZ() && first.origin.getZ() + a.maxZ() >= second.origin.getZ() + b.minZ();
    }
    private static double distance(Site site, double x, double y, double z) {
        BlueprintBounds b = Blueprint.bounds(site.type, site.blueprintVersion);
        return IndustryRules.distanceToBox(x, y, z, site.origin.getX() + b.minX(), site.origin.getY() + b.minY(), site.origin.getZ() + b.minZ(),
            site.origin.getX() + b.maxX(), site.origin.getY() + b.maxY(), site.origin.getZ() + b.maxZ());
    }
    public static boolean loaded(ServerLevel level, Site site) {
        BlueprintBounds b = Blueprint.bounds(site.type, site.blueprintVersion);
        if (level.isOutsideBuildHeight(site.origin.offset(b.minX(), b.minY(), b.minZ()))
            || level.isOutsideBuildHeight(site.origin.offset(b.maxX(), b.maxY(), b.maxZ()))) return false;
        int minChunkX = Math.floorDiv(site.origin.getX() + b.minX(), 16), maxChunkX = Math.floorDiv(site.origin.getX() + b.maxX(), 16);
        int minChunkZ = Math.floorDiv(site.origin.getZ() + b.minZ(), 16), maxChunkZ = Math.floorDiv(site.origin.getZ() + b.maxZ(), 16);
        for (int x = minChunkX; x <= maxChunkX; x++) for (int z = minChunkZ; z <= maxChunkZ; z++) {
            if (!level.hasChunkAt(new BlockPos(x << 4, site.origin.getY(), z << 4))) return false;
        }
        return true;
    }
    public static void poll(ServerLevel level, Site site) {
        if (!loaded(level, site)) return;
        Map<BlockPos, BlockState> blueprint = Construction.repairBlocks(site);
        site.total = blueprint.size(); site.matching = 0;
        for (var entry : blueprint.entrySet()) if (level.getBlockState(entry.getKey()).equals(entry.getValue()) || site.blueprintVersion>=3 && level.getBlockState(entry.getKey()).is(entry.getValue().getBlock()) || entry.getValue().is(IndustryContent.controller(site.type)) && level.getBlockState(entry.getKey()).is(IndustryContent.controller(site.type))) site.matching++;
        site.controller = level.getBlockState(Blueprint.controller(site.type, site.origin, site.blueprintVersion)).is(IndustryContent.controller(site.type));
        site.machineryIntact = Blueprint.criticalBlocks(site.type, site.origin, site.blueprintVersion).entrySet().stream()
            .allMatch(entry -> level.getBlockState(entry.getKey()).equals(entry.getValue()));
    }
    /** Only real, loaded, structurally working owned sites can become automatic power sources. */
    public static void autoConnect(ServerLevel level, IndustryData data, Site placed) {
        if (placed.type != SiteType.POWER_STATION && placed.upstream == null) {
            Site source = nearestUpstream(level, data, placed);
            if (source != null) placed.upstream = source.id;
        }
        for (Site target : data.sites.values()) {
            if (target == placed || target.upstream != null || !target.owner.equals(placed.owner) || !target.dimension.equals(placed.dimension)
                || !loaded(level, target) || !((placed.type == SiteType.POWER_STATION && target.type == SiteType.SUBSTATION)
                || (placed.type == SiteType.SUBSTATION && target.type.isConsumer()))) continue;
            Site source = nearestUpstream(level, data, target);
            if (source != null) target.upstream = source.id;
        }
        data.setDirty();
    }
    private static Site nearestUpstream(ServerLevel level, IndustryData data, Site target) {
        SiteType required = target.type == SiteType.SUBSTATION ? SiteType.POWER_STATION : target.type.isConsumer() ? SiteType.SUBSTATION : null;
        if (required == null) return null;
        return data.sites.values().stream().filter(source -> source.type == required && source.owner.equals(target.owner) && source.dimension.equals(target.dimension))
            .filter(source -> IndustryRules.linkAllowed(true, true, source.type.ordinal(), target.type.ordinal(), source.origin.distSqr(target.origin)) && loaded(level, source))
            .peek(source -> { poll(level, source); source.loaded = true; }).filter(Site::functional)
            .min(Comparator.comparingDouble(source -> source.origin.distSqr(target.origin))).orElse(null);
    }
    private static Site upstream(IndustryData data, Site target, SiteType type) {
        Site source = target.upstream == null ? null : data.sites.get(target.upstream);
        return source != null && source.type == type && IndustryRules.linkAllowed(source.owner.equals(target.owner), source.dimension.equals(target.dimension),
            source.type.ordinal(), target.type.ordinal(), source.origin.distSqr(target.origin)) ? source : null;
    }
    private static void moveEnergy(IndustryData data, Site source, Site target, int maximum) {
        int transferred = IndustryRules.transfer(source.energy, target.energy, maximum);
        if (transferred > 0) { source.energy -= transferred; target.energy += transferred; data.setDirty(); }
    }
    private static IndustryCatalog.Recipe exactRecipe(String id, SiteType type) {
        // Saved queue IDs must be exact, never fuzzy-resolved into a newly installed item after reload.
        return IndustryCatalog.recipes().stream().filter(recipe -> recipe.factory() == type && recipe.id().equals(id)).findFirst().orElse(null);
    }
    private static boolean takeFuel(ItemStackHandler inventory) {
        for (int slot = 0; slot < IndustryRules.INPUTS; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.is(Items.COAL) || stack.is(Items.CHARCOAL)) { inventory.extractItem(slot, 1, false); return true; }
        }
        return false;
    }
    private static void fuel(ServerPlayer player, Site site) {
        if (site.type != SiteType.POWER_STATION) { tell(player, "Топливо принимается только на электростанции"); return; }
        int moved = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.is(Items.COAL) && !stack.is(Items.CHARCOAL)) continue;
            ItemStack remaining = stack.copy();
            for (int input = 0; input < IndustryRules.INPUTS && !remaining.isEmpty(); input++) remaining = site.inventory.insertItem(input, remaining, false);
            moved += stack.getCount() - remaining.getCount(); player.getInventory().setItem(slot, remaining);
        }
        player.getInventory().setChanged(); tell(player, "Загружено топлива: " + moved);
    }
    public static int countItem(ServerPlayer player, net.minecraft.world.item.Item item) {
        int count = 0; for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) { ItemStack stack = player.getInventory().getItem(slot); if (stack.is(item)) count += stack.getCount(); } return count;
    }
    public static void takePlayerItem(ServerPlayer player, net.minecraft.world.item.Item item, int count) {
        for (int slot = 0; slot < player.getInventory().getContainerSize() && count > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot); if (!stack.is(item)) continue; int taken = Math.min(count, stack.getCount()); stack.shrink(taken); count -= taken;
        }
        player.getInventory().setChanged();
    }
    private static BlockPos spawnPosition(ServerLevel level, Site site) {
        BlockPos entrance = Blueprint.entrance(site.type, site.origin, site.blueprintVersion);
        for (BlockPos pos : List.of(entrance, entrance.offset(0, 0, 1), site.origin.offset(-1, 1, 0), site.origin.offset(1, 1, 0))) {
            if (level.hasChunkAt(pos) && level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir() && level.getBlockState(pos.below()).isSolid()) return pos;
        }
        return null;
    }
    private static boolean repairSessionValid(ServerPlayer player, ItemStack kit, RepairSession session) {
        return session.server == player.server && session.kit == kit && !kit.isEmpty() && kit.is(IndustryContent.REPAIR_KIT.get())
            && kit.hasTag() && kit.getTag().hasUUID("IndustryRepairSite") && kit.getTag().getUUID("IndustryRepairSite").equals(session.site)
            && accessible(player, session.site, IndustryRules.REPAIR_RANGE) != null;
    }
    public static void tell(ServerPlayer player, String message) { player.displayClientMessage(Component.literal(message), false); }
    private static final class RepairSession {
        final MinecraftServer server; final UUID site; final ItemStack kit; final int startTick; int lastTick, heldTicks, countTick = Integer.MIN_VALUE;
        RepairSession(MinecraftServer server, UUID site, ItemStack kit, int tick) { this.server = server; this.site = site; this.kit = kit; this.startTick = tick; this.lastTick = tick; }
    }
}
