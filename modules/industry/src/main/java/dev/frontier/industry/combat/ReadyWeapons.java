package dev.frontier.industry.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.frontier.industry.engine.ClaimPermissions;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.items.IItemHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** A compatibility adapter: all missile models, inventory, flight and damage remain native DeepStrike. */
public final class ReadyWeapons {
    static final String CREW = "frontierNativeDefenseCrew";
    static final String ANCHOR = "frontierNativeDefenseAnchor";
    static final String DIMENSION = "frontierNativeDefenseDimension";
    static final String DUTY = "frontierNativeDefenseDuty";
    static final String PENDING = "frontierNativeDefenseTask";
    private static final String FROZEN = "frontierNativeDefenseFrozen";
    private static final String OLD_AI = "frontierNativeDefenseOldAI";
    private static final String GAME = "dev.sbw.deepstrike.game.";
    private static final String VEHICLE = "dev.sbw.deepstrike.vehicle.";
    private ReadyWeapons() { }
    public static boolean available() { return ModList.get().isLoaded("deepstrike"); }
    static Object invoke(Object object, String name, Class<?>[] parameters, Object... args) throws ReflectiveOperationException {
        Class<?> type = object instanceof Class<?> c ? c : object.getClass();
        Method method = type.getMethod(name, parameters);
        return method.invoke(object instanceof Class<?> ? null : object, args);
    }
    static Object call(Object object, String name) throws ReflectiveOperationException { return invoke(object, name, new Class<?>[0]); }
    static Object staticCall(String name, String method, Class<?>[] parameters, Object... args) throws ReflectiveOperationException {
        return invoke(Class.forName(name), method, parameters, args);
    }
    static boolean nativeLauncher(Object object) { return object != null && object.getClass().getName().equals(GAME + "LauncherBlockEntity"); }
    static boolean nativeDefense(Entity entity) { return entity != null && (entity.getClass().getName().equals(VEHICLE + "PatriotTruckEntity")
            || entity.getClass().getName().equals(VEHICLE + "IronDomeEntity") || entity.getClass().getName().equals(VEHICLE + "AnMpq65Entity")); }
    static boolean patriot(Entity entity) { return entity.getClass().getName().equals(VEHICLE + "PatriotTruckEntity"); }
    static boolean dome(Entity entity) { return entity.getClass().getName().equals(VEHICLE + "IronDomeEntity"); }
    static UUID nativeOwner(Entity entity) {
        CompoundTag tag = entity.getPersistentData();
        if (tag.hasUUID("DeepStrikeOwner")) return tag.getUUID("DeepStrikeOwner");
        return CombatOwnership.owner(entity);
    }
    static CompoundTag tags(Object station) { return station instanceof Entity e ? e.getPersistentData() : ((BlockEntity) station).getPersistentData(); }
    static Vec3 position(Object station) { return station instanceof Entity e ? e.position() : Vec3.atCenterOf(((BlockEntity) station).getBlockPos()); }
    static UUID stationId(Object station) throws ReflectiveOperationException { return station instanceof Entity e ? e.getUUID() : (UUID) call(station, "journalId"); }
    static ServerLevel level(Object station) { return (ServerLevel) (station instanceof Entity e ? e.level() : ((BlockEntity) station).getLevel()); }
    static boolean owned(ServerPlayer player, Object station) throws ReflectiveOperationException {
        if (station instanceof Entity e) return player.getUUID().equals(nativeOwner(e));
        return Boolean.TRUE.equals(invoke(station, "canOperate", new Class<?>[]{ServerPlayer.class}, player));
    }
    static LivingEntity crew(Object station) throws ReflectiveOperationException {
        CompoundTag data = tags(station);
        if (!data.hasUUID(CREW)) return null;
        Entity entity = level(station).getEntity(data.getUUID(CREW));
        if (!(entity instanceof LivingEntity living) || !living.isAlive() || living.distanceToSqr(position(station)) > (station instanceof Entity ? 256 : 36)
                || !stationId(station).toString().equals(living.getPersistentData().getString(CombatOwnership.STATION))) return null;
        UUID owner = station instanceof Entity e ? nativeOwner(e) : (data.hasUUID("frontierNativeDefenseOwner") ? data.getUUID("frontierNativeDefenseOwner") : null);
        if (owner == null || !owner.equals(CombatOwnership.owner(living))) return null;
        if (station instanceof Entity e && (patriot(e) ? living.getVehicle() != e : living.isPassenger())) return null;
        return living;
    }
    static List<BlockEntity> launchers(ServerPlayer player) {
        List<BlockEntity> result = new ArrayList<>();
        int cx = player.blockPosition().getX() >> 4, cz = player.blockPosition().getZ() >> 4;
        for (int x = cx - 4; x <= cx + 4; x++) for (int z = cz - 4; z <= cz + 4; z++) {
            var chunk = player.serverLevel().getChunkSource().getChunkNow(x, z);
            if (chunk == null) continue;
            for (BlockEntity block : chunk.getBlockEntities().values()) if (nativeLauncher(block)
                    && position(block).distanceToSqr(player.position()) <= 4096) result.add(block);
        }
        result.sort(Comparator.comparingDouble(block -> position(block).distanceToSqr(player.position())));
        return result;
    }
    public static JsonObject snapshot(ServerPlayer player) {
        JsonObject result = new JsonObject(); JsonArray rows = new JsonArray(); result.add("systems", rows);
        result.addProperty("available", available()); result.addProperty("summary", "Крылатые и баллистические ракеты · Patriot · Iron Dome · радар");
        if (!available()) { result.addProperty("message", "Для ракетных комплексов нужен DeepStrike 0.9.0."); return result; }
        try {
            for (Entity entity : player.serverLevel().getEntities(player, player.getBoundingBox().inflate(64), ReadyWeapons::nativeDefense)) {
                UUID owner = nativeOwner(entity); if (owner != null && !owner.equals(player.getUUID())) continue;
                JsonObject row = base(entity, entity.getUUID().toString(), entity.getName().getString());
                row.addProperty("entityId", entity.getId()); row.addProperty("owned", owner != null);
                row.addProperty("type", patriot(entity) ? "patriot" : dome(entity) ? "iron_dome" : "radar");
                int ammo = patriot(entity) ? ((Number) call(entity, "readyPac2")).intValue() + ((Number) call(entity, "readyPac3")).intValue()
                        : dome(entity) ? ((Number) call(entity, "getReadyTamir")).intValue() : 0;
                row.addProperty("ammo", ammo); row.addProperty("active", tags(entity).getBoolean(DUTY));
                boolean hasCrew = crew(entity) != null; row.addProperty("crew", hasCrew);
                String status = owner == null ? "Не подключена" : !hasCrew ? "Нет оператора" : tags(entity).getBoolean(DUTY) ? "Дежурство" : "Ожидание";
                if (hasCrew && (patriot(entity) || dome(entity))) {
                    int reserve = patriot(entity) ? ((Number) call(entity, "pac2Count")).intValue() + ((Number) call(entity, "pac3Count")).intValue() : ((Number) call(entity, "getReserveTamir")).intValue();
                    row.addProperty("reserve", reserve);
                    if (Boolean.TRUE.equals(call(entity, "isReloading"))) {
                        int duration = entity.getClass().getField("RELOAD_MAX_TICKS").getInt(null);
                        int ticks = ((Number) call(entity, "getReloadTicks")).intValue();
                        status = "Перезарядка " + Math.min(99, ticks * 100 / Math.max(1, duration)) + "% · запас " + reserve;
                    } else if (patriot(entity) && Boolean.TRUE.equals(call(entity, "isDeploying"))) status = "Развёртывание";
                    else if (ammo == 0) status = "Нет готовых ракет · запас " + reserve;
                }
                row.addProperty("status", status); rows.add(row);
            }
            for (BlockEntity block : launchers(player)) {
                if (!owned(player, block)) continue;
                BlockPos pos = block.getBlockPos(); JsonObject row = base(block, "block:" + pos.getX() + "," + pos.getY() + "," + pos.getZ(), block.getBlockState().getBlock().getName().getString());
                row.addProperty("type", "launcher"); row.addProperty("owned", true); row.addProperty("ammo", ((Number) call(block, "loadCount")).intValue());
                row.addProperty("crew", crew(block) != null); row.addProperty("active", !tags(block).getBoolean("frontierNativeDefenseHold"));
                row.addProperty("status", crew(block) == null ? "Нет оператора" : Boolean.TRUE.equals(call(block, "commandBusy")) ? "Подготовка / пуск" : "Готова к приказу"); rows.add(row);
            }
        } catch (ReflectiveOperationException | LinkageError incompatible) { result.addProperty("message", "API DeepStrike отличается от проверенной версии 0.9.0."); }
        if (rows.size() == 0) result.addProperty("message", "Разверни штатную пусковую, Patriot, Iron Dome или радар в радиусе 64 блоков.");
        return result;
    }
    private static JsonObject base(Object station, String id, String name) {
        JsonObject row = new JsonObject(); Vec3 point = position(station); row.addProperty("id", id); row.addProperty("name", name); row.addProperty("native", true);
        row.addProperty("x", (int) point.x); row.addProperty("y", (int) point.y); row.addProperty("z", (int) point.z); return row;
    }
    private static Object resolve(ServerPlayer player, String id) {
        try {
            if (id.startsWith("block:")) {
                String[] parts = id.substring(6).split(",", -1); if (parts.length != 3) return null;
                BlockPos pos = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                if (pos.distToCenterSqr(player.position()) > 4096 || !player.serverLevel().hasChunkAt(pos)) return null;
                BlockEntity block = player.serverLevel().getBlockEntity(pos); return nativeLauncher(block) ? block : null;
            }
            Entity entity = player.serverLevel().getEntity(UUID.fromString(id));
            return nativeDefense(entity) && entity.distanceToSqr(player) <= 4096 && entity.isAlive() ? entity : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }
    public static String action(ServerPlayer player, String action, String arg, int value) {
        if (player instanceof net.minecraftforge.common.util.FakePlayer) return "Технический контроллер NPC не может отдавать приказы через ПДА.";
        if (!available()) return "Нужен DeepStrike 0.9.0.";
        if (!player.serverLevel().getServer().isSameThread()) return "Приказ должен исполняться сервером.";
        if (action.startsWith("pvo_")) action = action.substring(4);
        if (arg.equals("terminal") || action.equals("terminal")) return "Открой карту целей кнопкой в ПДА, держа командный планшет.";
        String[] input = arg.split("\\|", 2); Object station = resolve(player, input[0]);
        if (station == null) return "Установка не найдена среди загруженных объектов в 64 блоках.";
        try {
            if (action.equals("operator") || action.equals("bind")) return assign(player, station);
            if (!owned(player, station)) return "Чужая установка: управление запрещено.";
            return switch (action) {
                case "reload" -> reload(player, station);
                case "active", "hold" -> duty(player, station, action.equals("active"));
                case "release", "operator_clear" -> { release(station); yield "Оператор освобождён."; }
                case "launch" -> input.length == 2 ? launch(player, station, input[1], value) : "Введи координаты цели: X, Y, Z.";
                default -> crew(station) == null ? "Назначь свободного своего NPC рядом с установкой." : "Установка подключена. Доступны боезапас, дежурство и пуск.";
            };
        } catch (ReflectiveOperationException | LinkageError incompatible) { return "Команда отклонена: несовместимый API DeepStrike."; }
    }
    private static String assign(ServerPlayer player, Object station) throws ReflectiveOperationException {
        if (position(station).distanceToSqr(player.position()) > 64) return "Подойди к установке на 8 блоков.";
        if (!ClaimPermissions.allows(player.serverLevel(), player.getUUID(), player.getTeam() == null ? "" : player.getTeam().getName(), BlockPos.containing(position(station)))) return "Нет доступа к территории установки.";
        if (station instanceof Entity e && nativeOwner(e) == null) {
            if (!ClaimPermissions.allows(player.serverLevel(), player.getUUID(), player.getTeam() == null ? "" : player.getTeam().getName(), e.blockPosition())) return "Нет доступа к территории.";
            String bound = (String) staticCall(GAME + "CommandDefense", "bind", new Class<?>[]{ServerPlayer.class, Entity.class}, player, e);
            if (!bound.isEmpty() && !bound.equals("already_bound")) return "Подключение отклонено DeepStrike: " + bound;
        }
        if (!owned(player, station)) return "Чужая установка: управление запрещено.";
        if (station instanceof BlockEntity) {
            Object center = staticCall(GAME + "CommandCenterData", "get", new Class<?>[]{ServerLevel.class}, player.serverLevel());
            String error = (String) invoke(center, "bind", new Class<?>[]{ServerPlayer.class, station.getClass()}, player, station);
            if (!error.isEmpty() && !error.equals("already_bound")) return "DeepStrike: " + error;
        }
        if (crew(station) != null) return "Оператор уже на месте.";
        UUID stationId = stationId(station);
        LivingEntity npc = player.serverLevel().getEntitiesOfClass(LivingEntity.class, new AABB(position(station), position(station)).inflate(6),
                living -> !(living instanceof net.minecraft.world.entity.player.Player) && CombatOwnership.eligibleOperator(living, player.getUUID(), stationId))
                .stream().min(Comparator.comparingDouble(living -> living.distanceToSqr(position(station)))).orElse(null);
        if (npc == null) return "Нужен свободный свой NPC в 6 блоках от установки.";
        if (station instanceof Entity e && patriot(e) && !npc.startRiding(e, true)) return "Нет свободного места для оператора.";
        clearAssignment(npc);
        tags(station).putUUID(CREW, npc.getUUID()); tags(station).putUUID("frontierNativeDefenseOwner", player.getUUID());
        npc.getPersistentData().putString(CombatOwnership.STATION, stationId.toString());
        if (npc instanceof Mob mob && CombatOwnership.isRecruit(npc)) {
            npc.getPersistentData().putBoolean(OLD_AI, mob.isNoAi()); npc.getPersistentData().putBoolean(FROZEN, true); mob.setNoAi(true);
        }
        npc.getPersistentData().putString(DIMENSION, player.serverLevel().dimension().location().toString());
        if (station instanceof BlockEntity block) npc.getPersistentData().putLong(ANCHOR, block.getBlockPos().asLong());
        CombatOwnership.stopNavigation(npc); if (station instanceof BlockEntity block) block.setChanged();
        return "Живой оператор назначен. Без него установка не стреляет.";
    }
    private static String reload(ServerPlayer player, Object station) throws ReflectiveOperationException {
        if (position(station).distanceToSqr(player.position()) > 64) return "Для загрузки подойди на 8 блоков.";
        int transferred = 0; int fuel = 0; String radar = "";
        if (station instanceof BlockEntity block) {
            if (call(block, "family").toString().equals("PEACEKEEPER")) fuel = refuel(player, station);
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                while (!stack.isEmpty()) {
                    int before = stack.getCount(), loadedBefore = ((Number) call(station, "loadCount")).intValue();
                    if (!Boolean.TRUE.equals(invoke(station, "load", new Class<?>[]{ItemStack.class, net.minecraft.world.entity.player.Player.class}, stack, player))) break;
                    int added = ((Number) call(station, "loadCount")).intValue() - loadedBefore;
                    if (added <= 0) break;
                    // Native creative loading otherwise leaves the supplied stack intact.
                    if (stack.getCount() == before) stack.shrink(added);
                    transferred += before - stack.getCount();
                }
            }
        } else {
            Object raw = call(station, "getInventory"); if (!(raw instanceof IItemHandler inventory)) return "Открой штатный инвентарь установки для загрузки ракет.";
            if (!patriot((Entity) station) && !dome((Entity) station)) return "Радар не требует ракет.";
            if (patriot((Entity) station)) radar = linkRadar(player, (Entity) station, inventory);
            String path = patriot((Entity) station) ? "pac_" : "tamir";
            // The native rack reloads inventory slots in order. Prefer PAC-3 when both types are supplied.
            for (int priority = 0; priority < (patriot((Entity) station) ? 2 : 1); priority++) {
                for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                    ItemStack stack = player.getInventory().getItem(slot); var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                    if (stack.isEmpty() || id == null || !id.getNamespace().equals("deepstrike") || !id.getPath().startsWith(path)) continue;
                    if (patriot((Entity) station) && (priority == 0) != id.getPath().equals("pac_3")) continue;
                    for (int target = 0; target < inventory.getSlots() && !stack.isEmpty(); target++) {
                        int before = stack.getCount(); ItemStack remainder = inventory.insertItem(target, stack.copy(), false);
                        int moved = before - remainder.getCount(); if (moved > 0) { stack.shrink(moved); transferred += moved; }
                    }
                }
            }
            call(station, patriot((Entity) station) ? "refreshDefenseInventory" : "refreshInventory"); call(station, "startManualReload");
        }
        player.getInventory().setChanged(); return "Ракет загружено: " + transferred + ". Топливо: " + fuel + " вед. " + radar;
    }
    private static int refuel(ServerPlayer player, Object launcher) throws ReflectiveOperationException {
        int used = 0;
        for (int count = 0; count < 3 && !Boolean.TRUE.equals(call(launcher, "platformFueled")); count++) {
            int source = -1;
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) if (player.getInventory().getItem(slot).is(net.minecraft.world.item.Items.LAVA_BUCKET)) { source = slot; break; }
            if (source < 0) break;
            String beforeStatus = (String) call(launcher, "platformFuelStatus");
            ItemStack fuel = player.getInventory().removeItem(source, 1); ItemStack held = player.getOffhandItem();
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, fuel);
            ItemStack remainder;
            try { invoke(launcher, "refuel", new Class<?>[]{net.minecraft.world.entity.player.Player.class, net.minecraft.world.InteractionHand.class}, player, net.minecraft.world.InteractionHand.OFF_HAND); }
            finally { remainder = player.getOffhandItem(); player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, held); }
            if (remainder.is(net.minecraft.world.item.Items.LAVA_BUCKET) && !beforeStatus.equals(call(launcher, "platformFuelStatus"))) remainder = new ItemStack(net.minecraft.world.item.Items.BUCKET);
            if (!remainder.is(net.minecraft.world.item.Items.LAVA_BUCKET)) used++;
            if (!remainder.isEmpty() && !player.getInventory().add(remainder)) player.drop(remainder, false);
        }
        return used;
    }
    private static String linkRadar(ServerPlayer player, Entity station, IItemHandler inventory) throws ReflectiveOperationException {
        Entity radar = player.serverLevel().getEntities(player, station.getBoundingBox().inflate(64),
                e -> e.getClass().getName().equals(VEHICLE + "AnMpq65Entity") && player.getUUID().equals(nativeOwner(e)))
                .stream().min(Comparator.comparingDouble(station::distanceToSqr)).orElse(null);
        if (radar == null) return "Для Patriot разверни свой радар AN/MPQ-65.";
        ItemStack terminal = ItemStack.EMPTY; int existing = -1;
        for (int slot = 0; slot < inventory.getSlots(); slot++) if (item(inventory.getStackInSlot(slot), "air_defense_terminal")) { terminal = inventory.extractItem(slot, 1, false); existing = slot; break; }
        if (terminal.isEmpty()) for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) if (item(player.getInventory().getItem(slot), "air_defense_terminal")) { terminal = player.getInventory().removeItem(slot, 1); break; }
        if (terminal.isEmpty()) return "Нужен настоящий терминал ПВО в инвентаре для связи с радаром.";
        ItemStack held = player.getOffhandItem(); player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, terminal);
        try { radar.interact(player, net.minecraft.world.InteractionHand.OFF_HAND); }
        finally { terminal = player.getOffhandItem(); player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, held); }
        if (existing >= 0) terminal = inventory.insertItem(existing, terminal, false);
        for (int slot = 0; slot < inventory.getSlots() && !terminal.isEmpty(); slot++) terminal = inventory.insertItem(slot, terminal, false);
        if (!terminal.isEmpty() && !player.getInventory().add(terminal)) player.drop(terminal, false);
        return "Терминал ПВО связан со своим радаром.";
    }
    private static boolean item(ItemStack stack, String path) {
        var id = ForgeRegistries.ITEMS.getKey(stack.getItem()); return !stack.isEmpty() && id != null && id.getNamespace().equals("deepstrike") && id.getPath().equals(path);
    }
    static boolean radarReady(Entity station) throws ReflectiveOperationException {
        if (!patriot(station)) return true;
        if (!(call(station, "getInventory") instanceof IItemHandler inventory)) return false;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack terminal = inventory.getStackInSlot(slot); CompoundTag tag = terminal.getTag();
            if (!item(terminal, "air_defense_terminal") || tag == null || !tag.hasUUID("TargetUUID")) continue;
            Entity radar = ((ServerLevel) station.level()).getEntity(tag.getUUID("TargetUUID"));
            if (radar != null && radar.isAlive() && radar.getClass().getName().equals(VEHICLE + "AnMpq65Entity")
                    && java.util.Objects.equals(nativeOwner(station), nativeOwner(radar)) && crew(radar) != null) return true;
        }
        return false;
    }
    private static String duty(ServerPlayer player, Object station, boolean enabled) throws ReflectiveOperationException {
        if (enabled && crew(station) == null) return "Сначала назначь живого оператора.";
        if (enabled && station instanceof Entity e && patriot(e) && !radarReady(e)) return "Нужен свой радар с оператором и связанный терминал ПВО. Положи терминал в инвентарь и нажми «Загрузить».";
        if (station instanceof Entity entity) {
            tags(entity).putBoolean(DUTY, enabled);
            if (patriot(entity)) {
                boolean deployed = Boolean.TRUE.equals(call(entity, "isDeployed"));
                if (enabled && !deployed && !Boolean.TRUE.equals(call(entity, "isDeploying"))) call(entity, "toggleDeployment");
            } else if (dome(entity)) {
                invoke(entity, "setAutoDefense", new Class<?>[]{boolean.class}, false);
                invoke(entity, "setAircraftTargets", new Class<?>[]{boolean.class}, true);
            }
        } else { tags(station).putBoolean("frontierNativeDefenseHold", !enabled); ((BlockEntity) station).setChanged(); }
        return enabled ? "Боевое дежурство включено." : "Приказ: удерживать огонь.";
    }
    static void clearAssignment(LivingEntity living) {
        var tag = living.getPersistentData();
        tag.remove(CombatOwnership.STATION); tag.remove(ANCHOR); tag.remove(DIMENSION);
        if (tag.getBoolean(FROZEN) && living instanceof Mob mob) mob.setNoAi(tag.getBoolean(OLD_AI));
        tag.remove(FROZEN); tag.remove(OLD_AI); CombatOwnership.stopNavigation(living);
    }
    static void release(Object station) throws ReflectiveOperationException {
        CompoundTag data = tags(station);
        Entity raw = data.hasUUID(CREW) ? level(station).getEntity(data.getUUID(CREW)) : null;
        if (raw instanceof LivingEntity living && stationId(station).toString().equals(living.getPersistentData().getString(CombatOwnership.STATION))) {
            clearAssignment(living);
            if (station instanceof Entity entity && living.getVehicle() == entity) living.stopRiding();
        }
        data.remove(CREW); data.putBoolean(DUTY, false);
        if (station instanceof Entity entity && dome(entity)) invoke(entity, "setAutoDefense", new Class<?>[]{boolean.class}, false);
        if (station instanceof BlockEntity block) block.setChanged();
    }
    static boolean validStation(LivingEntity living) {
        if (!(living.level() instanceof ServerLevel server) || !available()) return false;
        try {
            Object station = living.getVehicle(); CompoundTag data = living.getPersistentData();
            if (station == null) { UUID id = CombatOwnership.parse(data.getString(CombatOwnership.STATION)); if (id != null) station = server.getEntity(id); }
            if (data.contains(ANCHOR) && server.dimension().location().toString().equals(data.getString(DIMENSION))) {
                BlockPos pos = BlockPos.of(data.getLong(ANCHOR));
                if (!server.hasChunkAt(pos)) return true; station = server.getBlockEntity(pos);
            }
            return (station instanceof Entity e && nativeDefense(e) || nativeLauncher(station)) && crew(station) == living;
        } catch (ReflectiveOperationException incompatible) { return false; }
    }
    private static String launch(ServerPlayer player, Object selected, String coordinates, int count) throws ReflectiveOperationException {
        if (player.getPersistentData().hasUUID(PENDING)) return "Предыдущий залп ещё готовится. Дождись штатного отсчёта.";
        if (!(selected instanceof BlockEntity block)) return "Выбери наземную ракетную пусковую. ПВО управляет перехватом автоматически.";
        if (count < 1 || count > 3) return "Залп: от 1 до 3 ракет.";
        String[] parts = coordinates.trim().split("[,;\\s]+", -1); if (parts.length != 3) return "Координаты: X, Y, Z.";
        Vec3 target; try { target = new Vec3(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2])); }
        catch (NumberFormatException invalid) { return "Координаты должны быть числами."; }
        BlockPos targetPos = BlockPos.containing(target);
        if (!Double.isFinite(target.lengthSqr()) || !player.serverLevel().getWorldBorder().isWithinBounds(targetPos)
                || player.serverLevel().isOutsideBuildHeight(targetPos)) return "Цель за границей мира.";
        List<BlockEntity> candidates = new ArrayList<>(); candidates.add(block);
        for (BlockEntity other : launchers(player)) if (other != block && other.getBlockState().getBlock() == block.getBlockState().getBlock()) candidates.add(other);
        List<BlockEntity> selectedUnits = new ArrayList<>();
        for (BlockEntity candidate : candidates) if (owned(player, candidate) && crew(candidate) != null
                && !tags(candidate).getBoolean("frontierNativeDefenseHold") && ((Number) call(candidate, "loadCount")).intValue() > 0
                && !Boolean.TRUE.equals(call(candidate, "commandBusy"))) { selectedUnits.add(candidate); if (selectedUnits.size() == count) break; }
        if (selectedUnits.size() != count) return "Для одновременного залпа нужны " + count + " заряженные пусковые с живыми операторами.";
        Object center = staticCall(GAME + "CommandCenterData", "get", new Class<?>[]{ServerLevel.class}, player.serverLevel());
        CompoundTag session = (CompoundTag) invoke(center, "session", new Class<?>[]{ServerPlayer.class}, player);
        if (session == null) {
            String error = (String) invoke(center, "login", new Class<?>[]{ServerPlayer.class, String.class}, player, player.getGameProfile().getName());
            if (!error.isEmpty()) return "DeepStrike: " + error;
        }
        ListTag assignments = new ListTag();
        for (BlockEntity candidate : selectedUnits) {
            CompoundTag currentSession = (CompoundTag) invoke(center, "session", new Class<?>[]{ServerPlayer.class}, player);
            boolean bound = false;
            for (var tag : currentSession.getList("Units", 10)) if (tag instanceof CompoundTag entry && entry.hasUUID("Id") && entry.getUUID("Id").equals(stationId(candidate))) bound = true;
            if (!bound) return "Сначала подключи каждую пусковую назначением оператора рядом с ней.";
            CompoundTag assignment = new CompoundTag(); assignment.putUUID("Unit", stationId(candidate)); assignment.putInt("Count", 1); assignments.add(assignment);
        }
        CompoundTag payload = new CompoundTag(); String name = "ПДА " + UUID.randomUUID().toString().substring(0, 8); payload.putString("Name", name);
        ListTag route = new ListTag(); CompoundTag point = new CompoundTag(); point.putDouble("X", target.x); point.putDouble("Y", target.y); point.putDouble("Z", target.z); point.putDouble("Speed", 240); route.add(point);
        payload.put("Route", route); payload.put("Assignments", assignments); payload.putInt("LaunchDelay", 40); payload.putBoolean("ReadyOnly", false);
        String error = nativeAction(center, player, "save", payload); if (!error.isEmpty()) return "DeepStrike: " + error;
        session = (CompoundTag) invoke(center, "session", new Class<?>[]{ServerPlayer.class}, player);
        UUID task = null; for (var tag : session.getList("Tasks", 10)) if (tag instanceof CompoundTag entry && name.equals(entry.getString("Name"))) task = entry.getUUID("Id");
        if (task == null) return "DeepStrike не создал задачу пуска.";
        CompoundTag order = new CompoundTag(); order.putUUID("Task", task); error = nativeAction(center, player, "arm", order);
        if (!error.isEmpty()) return "DeepStrike: " + error;
        player.getPersistentData().putUUID(PENDING, task); return "Залп принят: " + count + ". Штатная подготовка ракет и синхронный отсчёт начнутся после готовности всех установок.";
    }
    static String nativeAction(Object center, ServerPlayer player, String name, CompoundTag payload) throws ReflectiveOperationException {
        return (String) invoke(center, "action", new Class<?>[]{ServerPlayer.class, String.class, CompoundTag.class}, player, name, payload);
    }
}
