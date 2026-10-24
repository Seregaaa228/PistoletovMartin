package dev.frontier.industry;

import com.diego.recruitsvillages.data.FactionData;
import com.diego.recruitsvillages.data.LivingVillagesSavedData;
import com.diego.recruitsvillages.player.PlayerFactionService;
import com.diego.recruitsvillages.sim.Economy;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.talhanation.recruits.CommandEvents;
import com.talhanation.recruits.RecruitEvents;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import dev.frontier.industry.engine.IndustryData;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A projection of the existing countries, army and industrial records, never a second simulation. */
public final class Operations {
    private Operations() {}
    private static final Map<UUID, String> MESSAGES = new HashMap<>();
    private static final Set<String> ACTIONS = Set.of("refresh", "open_site", "village", "join", "guide", "commands",
        "hire_soldier", "follow", "hold", "soldier_inventory", "defense", "shop", "camera", "pvo_active", "pvo_hold", "pvo_reload", "pvo_operator", "pvo_release", "pvo_launch", "hire_builder", "building_kit");
    public static boolean allowed(String action) { return ACTIONS.contains(action); }
    public static void clear() { MESSAGES.clear(); }
    public static JsonObject snapshot(ServerPlayer player) {
        JsonObject view = new JsonObject();
        view.addProperty("player", player.getName().getString());
        view.addProperty("message", MESSAGES.getOrDefault(player.getUUID(), "Выбери раздел слева. Все действия доступны из этого КПК."));
        view.addProperty("x", player.getBlockX()); view.addProperty("y", player.getBlockY()); view.addProperty("z", player.getBlockZ());
        int emeralds = 0;
        for (ItemStack item : player.getInventory().items) if (item.is(Items.EMERALD)) emeralds += item.getCount();
        view.addProperty("emeralds", emeralds);
        FactionData own = PlayerFactionService.factionOfPlayer(player.serverLevel(), player);
        JsonObject country = new JsonObject();
        country.addProperty("member", own != null);
        country.addProperty("name", own == null ? player.getTeam() == null ? "Без страны" : player.getTeam().getName() : own.getName());
        if (own != null) {
            country.addProperty("id", own.getId().toString()); country.addProperty("soldiers", own.getRecruitCount());
            country.addProperty("civilians", own.getCivilianCount()); country.addProperty("treasury", own.getEmeralds());
            country.addProperty("food", own.getFood()); country.addProperty("stage", own.getWarStage().name());
            country.addProperty("villages", LivingVillagesSavedData.get(player.server).getFactionVillages(own).size());
        }
        view.add("country", country);
        JsonArray hires = new JsonArray();
        for (String type : PlayerFactionService.hireableTypes().stream().limit(24).toList()) {
            String nativeId = Economy.resolveRecruit(type);
            if (nativeId == null) continue;
            JsonObject hire = new JsonObject(); hire.addProperty("id", nativeId);
            hire.addProperty("name", troopName(nativeId)); hire.addProperty("cost", Economy.recruitCost(nativeId)); hires.add(hire);
        }
        view.add("hireTypes", hires);
        JsonArray soldiers = new JsonArray();
        for (AbstractRecruitEntity recruit : nearby(player)) {
            JsonObject row = new JsonObject(); row.addProperty("id", recruit.getUUID().toString());
            row.addProperty("name", recruit.getName().getString()); row.addProperty("health", Math.round(recruit.getHealth()));
            row.addProperty("state", recruit.getVehicle() != null ? "В экипаже" : recruit.getFollowState() == 2 ? "Следует" : "На позиции");
            row.addProperty("distance", (int) recruit.distanceTo(player)); soldiers.add(row);
        }
        view.add("soldiers", soldiers);
        view.addProperty("armyCount", RecruitEvents.recruitsPlayerUnitManager == null ? soldiers.size()
            : RecruitEvents.recruitsPlayerUnitManager.getRecruitCount(player.getUUID()));
        IndustryData data = IndustryData.get(player.server);
        JsonArray sites = new JsonArray();
        for (var site : data.sites.values().stream().filter(s -> s.owner.equals(player.getUUID()))
                .sorted(Comparator.comparing(s -> s.type.ordinal())).limit(128).toList()) {
            JsonObject full = IndustryEngine.snapshot(player, site.id), row = new JsonObject();
            for (String key : List.of("id", "type", "title", "status", "health", "powered", "workers", "progress", "total", "repairRemaining", "loaded", "x", "y", "z", "construction"))
                if (full.has(key)) row.add(key, full.get(key));
            if (!row.has("id")) {
                row.addProperty("id", site.id.toString()); row.addProperty("type", site.type.name()); row.addProperty("title", site.type.title());
                row.addProperty("x", site.origin.getX()); row.addProperty("y", site.origin.getY()); row.addProperty("z", site.origin.getZ());
                row.addProperty("status", "Другое измерение"); row.addProperty("health", site.total > 0 ? Math.round(100f * site.matching / site.total) : 0);
                row.addProperty("powered", false); row.addProperty("loaded", false);
            }
            row.addProperty("sameDimension", site.dimension.equals(player.level().dimension().location()));
            row.addProperty("distance", (int) Math.sqrt(site.origin.distToCenterSqr(player.position())));
            sites.add(row);
        }
        view.add("sites", sites);
        view.add("buildingKits",dev.frontier.industry.construction.BuildingKits.snapshot());
        view.addProperty("workers", data.workers.values().stream().filter(w -> w.owner.equals(player.getUUID()) && w.active()).count());
        view.addProperty("transferred", data.workers.values().stream().filter(w -> w.owner.equals(player.getUUID()) && w.active() && w.transferred()).count());
        view.add("weapons", optionalSnapshot("dev.frontier.industry.combat.ReadyWeapons", player));
        var shop=optionalSnapshot("dev.frontier.arcade.PdaShop",player);
        if(shop.has("items"))for(var raw:shop.getAsJsonArray("items"))if(raw.isJsonObject()){
            var row=raw.getAsJsonObject();String id=row.has("id")?row.get("id").getAsString():"";
            String denial=dev.frontier.industry.research.Research.admin(player)?"":dev.frontier.industry.research.ResearchPolicy.denialId(player.server,player.getUUID(),id);
            row.addProperty("locked",!denial.isEmpty());row.addProperty("denial",denial);
        }
        view.add("shop",shop);
        return view;
    }
    public static void action(ServerPlayer player, String action, String arg, int value) {
        if (!OperationsNet.hasPda(player) || !allowed(action) || arg.length() > 128 || value < 0 || value > 64) return;
        switch (action) {
            case "hire_builder" -> dev.frontier.industry.construction.Construction.hireBuilder(player);
            case "building_kit" -> message(player,dev.frontier.industry.construction.BuildingKits.purchase(player,arg));
            case "open_site" -> {
                UUID id = uuid(arg);
                var site = id == null ? null : IndustryData.get(player.server).sites.get(id);
                if (site == null || !IndustryEngine.owns(player, id)) { message(player, "Это не твой объект."); return; }
                if (!IndustryEngine.canAccess(player, id)) {
                    message(player, "Подойди к объекту: " + site.origin.getX() + ", " + site.origin.getY() + ", " + site.origin.getZ() + ". Управление рядом — до 12 блоков."); return;
                }
                IndustryMenus.open(player, id);
            }
            case "village" -> nativeCommand(player, "rvvillage");
            case "join" -> nativeCommand(player, "rvjoin");
            case "guide" -> nativeCommand(player, "rvguide");
            case "commands" -> CommandEvents.openCommandScreen(player);
            case "hire_soldier" -> message(player, NativePdaRecruitment.hire(player, arg, value));
            case "follow", "hold", "soldier_inventory" -> {
                List<AbstractRecruitEntity> units = nearby(player).stream().filter(r -> arg.equals("all") || r.getUUID().equals(uuid(arg))).toList();
                if (units.isEmpty()) { message(player, "Рядом нет выбранного собственного бойца."); return; }
                if (action.equals("soldier_inventory")) {
                    var unit = units.get(0); if (unit.distanceToSqr(player) <= 144) unit.openGUI(player);
                    else message(player, "Для снаряжения подойди к бойцу на 12 блоков.");
                } else {
                    // Mounted operators belong to their native crew system; do not steal them into an infantry order.
                    units = units.stream().filter(r -> r.getVehicle() == null
                        && r.getPersistentData().getString("frontierIndustryMilitaryStation").isBlank()
                        && !r.getPersistentData().hasUUID("frontierIndustryMilitaryStation")).toList();
                    CommandEvents.onMovementCommand(player, units, action.equals("follow") ? 2 : 1, 0);
                    message(player, "Приказ «" + (action.equals("follow") ? "За мной" : "Стоять") + "»: " + units.size() + " бойцам рядом.");
                }
            }
            case "defense" -> message(player, optionalAction("dev.frontier.industry.combat.ReadyWeapons", player, action, arg, value));
            case "pvo_active", "pvo_hold", "pvo_reload", "pvo_operator", "pvo_release", "pvo_launch" ->
                message(player, optionalAction("dev.frontier.industry.combat.ReadyWeapons", player, action.substring(4), arg, value));
            case "shop" -> {
                String denial=dev.frontier.industry.research.Research.admin(player)?"":dev.frontier.industry.research.ResearchPolicy.denialId(player.server,player.getUUID(),arg);
                message(player,denial.isEmpty()?optionalAction("dev.frontier.arcade.PdaShop",player,action,arg,value):denial);
            }
            case "camera" -> message(player, optionalAction("dev.frontier.arcade.PdaShop", player, action, arg, value));
            default -> { }
        }
    }
    private static List<AbstractRecruitEntity> nearby(ServerPlayer player) {
        return player.serverLevel().getEntitiesOfClass(AbstractRecruitEntity.class, player.getBoundingBox().inflate(128),
            r -> r.isAlive() && player.getUUID().equals(r.getOwnerUUID())).stream()
            .sorted(Comparator.comparingDouble(r -> r.distanceToSqr(player))).limit(64).toList();
    }
    private static JsonObject optionalSnapshot(String name, ServerPlayer player) {
        try {
            Object value = Class.forName(name).getMethod("snapshot", ServerPlayer.class).invoke(null, player);
            if (value instanceof JsonObject object) return object;
        } catch (ReflectiveOperationException | LinkageError absent) { }
        return new JsonObject();
    }
    private static String optionalAction(String name, ServerPlayer player, String action, String arg, int value) {
        try {
            Object result = Class.forName(name).getMethod("action", ServerPlayer.class, String.class, String.class, int.class).invoke(null, player, action, arg, value);
            return result instanceof String text ? text : "Команда передана.";
        } catch (ReflectiveOperationException | LinkageError absent) { return "Модуль ещё не подключён. Управление доступно штатным предметом мода."; }
    }
    private static void nativeCommand(ServerPlayer player, String fixed) {
        // Only fixed literal native commands above; never execute client-supplied text or raise permissions.
        player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), fixed);
    }
    public static void message(ServerPlayer player, String text) {
        MESSAGES.put(player.getUUID(), text.length() > 240 ? text.substring(0, 240) : text);
        player.displayClientMessage(Component.literal(text), true);
    }
    private static UUID uuid(String text) { try { return UUID.fromString(text); } catch (IllegalArgumentException invalid) { return null; } }
    private static String troopName(String id) {
        String name = id.substring(id.indexOf(':') + 1);
        return switch (name) { case "bowman" -> "Стрелок"; case "crossbowman" -> "Стрелок с арбалетом"; case "recruit" -> "Пехотинец";
            case "shieldman", "recruit_shieldman" -> "Щитоносец"; case "horseman" -> "Кавалерист"; case "nomad" -> "Конный стрелок";
            default -> "Боец: " + name.replace('_', ' '); };
    }
}
