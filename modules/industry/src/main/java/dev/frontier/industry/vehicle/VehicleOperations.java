package dev.frontier.industry.vehicle;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Server authority for real mounted NPC orders. No tickets, teleportation or fake player controls. */
public final class VehicleOperations {
    private static final String OWNER = "frontierIndustryVehicleOwner";
    private static final String CREW = "frontierIndustrySelectedDriver";
    private static final String CRAFT = "frontierIndustrySelectedVehicle";
    private static final String ASSIGNED = "frontierIndustryVehicle";
    private static final String OLD_AI = "frontierIndustryVehiclePreviousNoAI";
    private static final Map<UUID, Route> ROUTES = new HashMap<>();
    private static boolean initialized;

    private VehicleOperations() {}
    public static void register(IEventBus bus) { VehicleContent.register(bus); }
    public static void init() {
        if (!initialized) { initialized = true; MinecraftForge.EVENT_BUS.register(VehicleOperations.class); }
    }

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("industryvehicle")
            .executes(c -> help(c.getSource()))
            .then(Commands.literal("crew").executes(c -> selectCrew(c.getSource(), null))
                .then(Commands.argument("npc", EntityArgument.entity()).executes(c -> selectCrew(c.getSource(), EntityArgument.getEntity(c, "npc")))))
            .then(Commands.literal("vehicle").executes(c -> selectVehicle(c.getSource(), null))
                .then(Commands.argument("craft", EntityArgument.entity()).executes(c -> selectVehicle(c.getSource(), EntityArgument.getEntity(c, "craft")))))
            .then(Commands.literal("claim").executes(c -> claim(c.getSource())))
            .then(Commands.literal("go").then(Commands.argument("destination", Vec3Argument.vec3()).executes(c -> go(c.getSource(), Vec3Argument.getVec3(c, "destination")))))
            .then(Commands.literal("stop").executes(c -> stopCommand(c.getSource())))
            .then(Commands.literal("status").executes(c -> status(c.getSource())))
            .then(Commands.literal("fire").then(Commands.argument("target", Vec3Argument.vec3()).executes(c -> fire(c.getSource(), Vec3Argument.getVec3(c, "target"))))));
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Транспорт NPC: /industryvehicle crew [NPC], vehicle [техника], go X Y Z, stop, status. Выберите своего NPC и свою технику в 6 блоках. Первый раз сядьте в технику и выполните claim. Shift+ПКМ также выбирает NPC/технику. Самолёт по прибытии кружит у точки до stop; нужен свободный разгон. Канонерка: fire X Y Z (железный самородок)."), false);
        return 1;
    }

    private static int selectCrew(CommandSourceStack source, Entity explicit) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        LivingEntity npc = explicit instanceof LivingEntity living ? living : null;
        if (explicit == null) npc = player.serverLevel().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(6),
            e -> ownedNpc(e, player.getUUID()) && selectableCrew(e, player.getUUID())).stream().min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
        if (npc == null || npc.level() != player.level() || player.distanceToSqr(npc) > 36 || !ownedNpc(npc, player.getUUID()))
            return fail(source, "В 6 блоках нет вашего живого рекрута или работника.");
        if (!selectableCrew(npc, player.getUUID())) return fail(source, "NPC занят на предприятии, военном посту или в другом транспорте. Сначала освободите его.");
        player.getPersistentData().putUUID(CREW, npc.getUUID());
        source.sendSuccess(() -> Component.literal("Водитель выбран. Теперь выберите технику: /industryvehicle vehicle"), false);
        return 1;
    }

    private static int selectVehicle(CommandSourceStack source, Entity explicit) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Entity craft = explicit != null ? explicit : nearestCraft(player, false);
        if (craft == null || craft.level() != player.level() || player.distanceToSqr(craft) > 36)
            return fail(source, "Подойдите к своей технике ближе 6 блоков.");
        if (!knownType(craft)) return fail(source, "Этот тип транспорта не поддерживается. Поддержка: лодка, канонерка, SimplePlanes (самолёты), совместимая сборка Trajan tanks.");
        if (!player.getUUID().equals(owner(craft)) || !nativeOwnershipAgrees(craft, player.getUUID()))
            return fail(source, "Владелец техники не подтверждён. Для свободной техники сядьте на место водителя и выполните /industryvehicle claim.");
        player.getPersistentData().putUUID(CRAFT, craft.getUUID());
        String adapter = adapterDescription(craft);
        source.sendSuccess(() -> Component.literal("Техника выбрана: " + adapter + ". /industryvehicle go X Y Z"), false);
        return 1;
    }

    private static int claim(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Entity craft = player.getVehicle();
        if (craft == null || !knownType(craft) || craft.getControllingPassenger() != player)
            return fail(source, "Сначала вручную сядьте на место водителя поддерживаемой свободной техники.");
        UUID current = owner(craft);
        if (current != null && !current.equals(player.getUUID()) || !nativeOwnershipAgrees(craft, player.getUUID()))
            return fail(source, "Техника принадлежит другому игроку; право собственности не изменено.");
        setOwner(craft, player.getUUID());
        player.getPersistentData().putUUID(CRAFT, craft.getUUID());
        source.sendSuccess(() -> Component.literal("Техника закреплена за вами. Выйдите, выберите своего NPC командой crew и задайте go X Y Z."), false);
        return 1;
    }

    private static int go(CommandSourceStack source, Vec3 destination) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Entity craft = selectedCraft(player);
        LivingEntity npc = selectedNpc(player);
        String result = start(player, npc, craft, destination);
        if (result != null) return fail(source, result);
        source.sendSuccess(() -> Component.literal("NPC занял место водителя и направляется к координатам. Отмена: /industryvehicle stop."), false);
        return 1;
    }

    /** Also used by isolated runtime tests. Returns a visible rejection reason; null means accepted. */
    public static String start(ServerPlayer player, LivingEntity npc, Entity craft, Vec3 destination) {
        if (craft == null || npc == null) return "Выберите NPC (crew) и технику (vehicle) в пределах 6 блоков.";
        if (craft.level() != player.level() || npc.level() != player.level() || player.distanceToSqr(craft) > 64 * 64 || npc.distanceToSqr(craft) > 12 * 12)
            return "NPC должен находиться в 12 блоках от техники, вы — не дальше 64 блоков.";
        if (!craft.isAlive() || !ownedNpc(npc, player.getUUID()) || !player.getUUID().equals(owner(craft)) || !nativeOwnershipAgrees(craft, player.getUUID()))
            return "NPC или техника вам не принадлежат.";
        if (ROUTES.containsKey(craft.getUUID())) return "У этой техники уже есть активный водитель. Сначала stop.";
        if (!unassignedNpc(npc) || npc.isPassenger() && npc.getVehicle() != craft) return "NPC уже занят другим назначением.";
        if (!craft.getPassengers().isEmpty() && !(craft.getPassengers().size() == 1 && craft.getFirstPassenger() == npc))
            return "Сначала освободите технику. Ручное управление игрока имеет приоритет.";
        if (ROUTES.size() >= 64) return "На сервере уже 64 активных маршрута.";
        if (!Double.isFinite(destination.x) || !Double.isFinite(destination.y) || !Double.isFinite(destination.z)
            || craft.position().distanceToSqr(destination) > 1024 * 1024) return "Пункт назначения должен быть не дальше 1024 блоков.";
        ServerLevel level = player.serverLevel();
        BlockPos target = BlockPos.containing(destination);
        if (!level.getWorldBorder().isWithinBounds(target) || target.getY() < level.getMinBuildHeight() || target.getY() >= level.getMaxBuildHeight())
            return "Пункт назначения вне границ мира.";
        if (!level.hasChunkAt(target)) return "Пункт назначения находится в незагруженном чанке. Маршруты не загружают мир принудительно.";
        VehicleAdapters.Driver adapter = VehicleAdapters.find(craft);
        if (adapter == null) return "В этой версии транспорта нет безопасного API NPC. Для Trajan требуется arcade-версия с frontierSupportsNpcDriver(); вертолёты и Superb Warfare не поддерживаются.";
        String unavailable = adapter.unavailable(craft);
        if (unavailable != null) return unavailable;
        if (adapter.aircraft() && destination.y < craft.getY() + 3 && craft.onGround()) return "Для взлёта задайте высоту хотя бы на 3 блока выше самолёта и свободную полосу перед ним.";
        // Respect vanilla's 60-tick boarding cooldown after dismounting, and Forge mount cancellation.
        if (npc.getVehicle() != craft && !npc.startRiding(craft, false))
            return "NPC не смог сесть: после высадки подождите 3 секунды (60 тиков), затем повторите go. Посадку также может запретить сервер.";
        if (craft.getControllingPassenger() != npc) {
            npc.stopRiding();
            return "NPC не смог занять место водителя; посадка отменена.";
        }
        if (npc instanceof Mob mob) { npc.getPersistentData().putBoolean(OLD_AI, mob.isNoAi()); mob.getNavigation().stop(); mob.setNoAi(true); }
        npc.getPersistentData().putUUID(ASSIGNED, craft.getUUID());
        craft.getPersistentData().putUUID("frontierIndustryVehicleDriver", npc.getUUID());
        ROUTES.put(craft.getUUID(), new Route(player.getUUID(), npc.getUUID(), craft.getUUID(), level.dimension(), destination, adapter, craft.position()));
        player.getPersistentData().putUUID(CRAFT, craft.getUUID());
        player.getPersistentData().putUUID(CREW, npc.getUUID());
        return null;
    }

    public static void tick(MinecraftServer server) {
        for (Route route : new ArrayList<>(ROUTES.values())) {
            ServerLevel level = server.getLevel(route.dimension);
            Entity craft = level == null ? null : level.getEntity(route.craft);
            Entity entity = level == null ? null : level.getEntity(route.npc);
            ServerPlayer owner = server.getPlayerList().getPlayer(route.owner);
            if (craft == null || !(entity instanceof LivingEntity npc) || !npc.isAlive() || !craft.isAlive()
                || owner == null || owner.level() != level || !ownedNpc(npc, route.owner)
                || !route.owner.equals(owner(craft)) || !nativeOwnershipAgrees(craft, route.owner)
                || npc.getVehicle() != craft || craft.getControllingPassenger() != npc
                || craft.getPassengers().stream().anyMatch(e -> e instanceof Player)) {
                cancel(server, route, "Маршрут отменён: водитель, владелец или загруженная техника изменились."); continue;
            }
            Vec3 to = route.destination.subtract(craft.position());
            double horizontal = to.horizontalDistance();
            if (!route.adapter.aircraft() && horizontal < 2.8) { cancel(server, route, "Техника прибыла к точке."); continue; }
            if (++route.age > 20 * 300) { cancel(server, route, "Маршрут остановлен по лимиту 5 минут."); continue; }
            Vec3 ahead = craft.position().add(new Vec3(to.x, 0, to.z).normalize().scale(4));
            if (!level.hasChunkAt(BlockPos.containing(ahead)) || !level.hasChunkAt(BlockPos.containing(route.destination))) {
                cancel(server, route, "Маршрут остановлен перед незагруженным чанком."); continue;
            }
            String unavailable = route.adapter.unavailable(craft);
            if (unavailable != null) { cancel(server, route, unavailable); continue; }
            Vec3 steeringTarget = route.destination;
            if (route.adapter.aircraft() && horizontal < 24 && Math.abs(to.y) < 12) route.orbit = true;
            if (route.orbit) {
                double angle = Math.atan2(craft.getZ() - route.destination.z, craft.getX() - route.destination.x) + 0.65;
                steeringTarget = route.destination.add(Math.cos(angle) * 34, 0, Math.sin(angle) * 34);
            }
            if (!route.adapter.aircraft()) {
                // Native collision still resolves movement; stop rather than drive into occupied craft/people.
                Vec3 step = new Vec3(to.x, 0, to.z).normalize().scale(Math.max(1.5, craft.getBbWidth()));
                if (!level.getEntities(craft, craft.getBoundingBox().expandTowards(step).inflate(0.4), e -> e != npc && e.isPickable() && !e.isPassengerOfSameVehicle(craft)).isEmpty()) {
                    cancel(server, route, "Маршрут остановлен: впереди человек или техника."); continue;
                }
                if (route.age % 100 == 0) {
                    if (craft.position().distanceToSqr(route.checkpoint) < 0.25) { cancel(server, route, "Маршрут остановлен: техника застряла. Освободите путь и повторите go."); continue; }
                    route.checkpoint = craft.position();
                }
            }
            try { route.adapter.steer(craft, steeringTarget, horizontal); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) { cancel(server, route, "Маршрут остановлен: API транспорта несовместим."); }
        }
    }

    public static boolean active(Entity craft) { return ROUTES.containsKey(craft.getUUID()); }
    public static void stop(MinecraftServer server, Entity craft) {
        Route route = ROUTES.get(craft.getUUID());
        if (route != null) cancel(server, route, null);
    }

    private static int stopCommand(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Entity craft = selectedCraft(player);
        if (craft == null || !player.getUUID().equals(owner(craft))) return fail(source, "Выберите свою загруженную технику.");
        Route route = ROUTES.get(craft.getUUID());
        if (route == null || !route.owner.equals(player.getUUID())) return fail(source, "У техники нет активного NPC-маршрута.");
        cancel(player.server, route, "NPC-маршрут остановлен; водитель освобождён."); return 1;
    }

    private static int status(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Entity craft = selectedCraft(player);
        if (craft == null || !player.getUUID().equals(owner(craft))) return fail(source, "Своя техника не выбрана или её чанк выгружен.");
        Route route = ROUTES.get(craft.getUUID());
        String state = route == null ? "ручное управление / свободна" : route.orbit ? "NPC кружит у точки" : "NPC выполняет маршрут";
        source.sendSuccess(() -> Component.literal(adapterDescription(craft) + ": " + state + ". " + Math.round(craft.getX()) + " " + Math.round(craft.getY()) + " " + Math.round(craft.getZ())), false);
        return 1;
    }

    private static int fire(CommandSourceStack source, Vec3 target) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Entity craft = selectedCraft(player);
        if (!(craft instanceof VehicleGunboat gunboat)) return fail(source, "Выберите свою канонерскую лодку.");
        String error = gunboat.fire(player, target);
        if (error != null) return fail(source, error);
        source.sendSuccess(() -> Component.literal("Пушка произвела выстрел."), false); return 1;
    }

    private static void cancel(MinecraftServer server, Route route, String reason) {
        cancel(server, route, reason, true);
    }

    private static void cancel(MinecraftServer server, Route route, String reason, boolean dismount) {
        if (ROUTES.remove(route.craft) == null) return;
        ServerLevel level = server.getLevel(route.dimension);
        if (level != null) {
            Entity craft = level.getEntity(route.craft);
            Entity npc = level.getEntity(route.npc);
            if (craft != null) {
                // A player taking manual control must never inherit a late autopilot input write.
                if (!(craft.getControllingPassenger() instanceof Player)) try { route.adapter.stop(craft); }
                catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
                craft.getPersistentData().remove("frontierIndustryVehicleDriver");
            }
            if (npc != null) { restoreNpc(npc); if (dismount && npc.getVehicle() == craft) npc.stopRiding(); }
        }
        ServerPlayer player = server.getPlayerList().getPlayer(route.owner);
        if (player != null && reason != null) player.displayClientMessage(Component.literal(reason), false);
    }

    private static void restoreNpc(Entity npc) {
        CompoundTag data = npc.getPersistentData();
        if (data.contains(OLD_AI)) { if (npc instanceof Mob mob) mob.setNoAi(data.getBoolean(OLD_AI)); data.remove(OLD_AI); }
        data.remove(ASSIGNED);
    }

    @SubscribeEvent public static void joined(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide) return;
        Entity entity = event.getEntity();
        if (entity.getPersistentData().contains(ASSIGNED) && !ROUTES.values().stream().anyMatch(r -> r.npc.equals(entity.getUUID()))) restoreNpc(entity);
        if (entity.getPersistentData().contains("frontierIndustryVehicleDriver") && !ROUTES.containsKey(entity.getUUID())) {
            VehicleAdapters.Driver adapter = VehicleAdapters.find(entity);
            if (adapter != null) try { adapter.stop(entity); } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
            entity.getPersistentData().remove("frontierIndustryVehicleDriver");
        }
    }

    @SubscribeEvent public static void left(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide || event.getLevel().getServer() == null) return;
        Entity entity = event.getEntity();
        for (Route route : new ArrayList<>(ROUTES.values())) if (route.craft.equals(entity.getUUID()) || route.npc.equals(entity.getUUID())) {
            // EntityLeaveLevel can fire after removal from the level UUID map. Restore this actual object too.
            cancel(event.getLevel().getServer(), route, "NPC-маршрут отменён при выгрузке/удалении сущности.");
            if (route.npc.equals(entity.getUUID())) restoreNpc(entity);
            if (route.craft.equals(entity.getUUID())) {
                try { route.adapter.stop(entity); } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
                entity.getPersistentData().remove("frontierIndustryVehicleDriver");
            }
        }
    }

    @SubscribeEvent public static void mount(EntityMountEvent event) {
        if (event.getLevel().isClientSide || event.getLevel().getServer() == null) return;
        Entity craft = event.getEntityBeingMounted();
        if (craft == null) return;
        Entity rider = event.getEntityMounting();
        Route route = ROUTES.get(craft.getUUID());
        if (route != null && (!event.isMounting() && rider.getUUID().equals(route.npc) || event.isMounting() && rider instanceof Player))
            cancel(event.getLevel().getServer(), route, "NPC-маршрут отменён: посадка игрока или высадка водителя.", event.isMounting());
        // Automatic first claim is only made by a player actually attempting the empty driver's seat.
        if (event.isMounting() && rider instanceof ServerPlayer player && knownType(craft) && craft.getPassengers().isEmpty()
            && owner(craft) == null && nativeOwnershipAgrees(craft, player.getUUID())) setOwner(craft, player.getUUID());
    }

    @SubscribeEvent public static void interact(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide || !event.getEntity().isShiftKeyDown() || !(event.getEntity() instanceof ServerPlayer player)) return;
        Entity target = event.getTarget();
        try {
            if (target instanceof LivingEntity living && ownedNpc(living, player.getUUID())) {
                selectCrew(player.createCommandSourceStack(), target); event.setCanceled(true); event.setCancellationResult(InteractionResult.SUCCESS);
            } else if (knownType(target)) {
                selectVehicle(player.createCommandSourceStack(), target); event.setCanceled(true); event.setCancellationResult(InteractionResult.SUCCESS);
            }
        } catch (CommandSyntaxException ignored) { }
    }

    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        for (Route route : new ArrayList<>(ROUTES.values())) cancel(event.getServer(), route, null);
        ROUTES.clear();
    }

    public static UUID owner(Entity entity) {
        CompoundTag data = entity.getPersistentData();
        if (data.hasUUID(OWNER)) return data.getUUID(OWNER);
        try { return UUID.fromString(data.getString(OWNER)); } catch (IllegalArgumentException ignored) { return null; }
    }
    public static void setOwner(Entity entity, UUID owner) { entity.getPersistentData().putUUID(OWNER, owner); }

    public static boolean ownedNpc(LivingEntity npc, UUID owner) {
        if (!npc.isAlive() || npc instanceof Player) return false;
        CompoundTag data = npc.getPersistentData();
        if (data.getBoolean("frontierIndustryWorker")) {
            try {
                UUID actual = data.hasUUID("frontierIndustryOwner") ? data.getUUID("frontierIndustryOwner") : UUID.fromString(data.getString("frontierIndustryOwner"));
                return owner.equals(actual);
            } catch (IllegalArgumentException ignored) { return false; }
        }
        for (Class<?> type = npc.getClass(); type != null; type = type.getSuperclass()) {
            if (!type.getName().equals("com.talhanation.recruits.entities.AbstractRecruitEntity")) continue;
            try {
                return Boolean.TRUE.equals(type.getMethod("getIsOwned").invoke(npc)) && owner.equals(type.getMethod("getOwnerUUID").invoke(npc));
            } catch (ReflectiveOperationException | LinkageError ignored) { return false; }
        }
        return false;
    }

    private static boolean unassignedNpc(LivingEntity npc) {
        CompoundTag data = npc.getPersistentData();
        return !data.contains("frontierIndustrySite") && !data.contains("frontierIndustryMilitaryStation") && !data.contains(ASSIGNED);
    }
    private static boolean selectableCrew(LivingEntity npc, UUID expectedOwner) {
        if (!unassignedNpc(npc)) return false;
        if (!npc.isPassenger()) return true;
        Entity craft = npc.getVehicle();
        // Vanilla boats can pick up a free mob automatically. Its own driver can still receive an order.
        return craft != null && knownType(craft) && expectedOwner.equals(owner(craft)) && nativeOwnershipAgrees(craft, expectedOwner)
            && craft.getControllingPassenger() == npc && craft.getPassengers().size() == 1 && !ROUTES.containsKey(craft.getUUID());
    }

    private static boolean nativeOwnershipAgrees(Entity craft, UUID expected) {
        // Honor the installed mod's ownership API when present, in addition to our persistent claim.
        for (String name : new String[]{"getOwnerUUID", "getOwnerUuid"}) {
            try {
                Object result = craft.getClass().getMethod(name).invoke(craft);
                if (result instanceof Optional<?> optional) result = optional.orElse(null);
                if (result != null && (!(result instanceof UUID uuid) || !expected.equals(uuid))) return false;
            } catch (NoSuchMethodException absent) { }
            catch (ReflectiveOperationException | LinkageError failure) { return false; }
        }
        return true;
    }

    private static Entity selectedCraft(ServerPlayer player) {
        if (player.getVehicle() != null && player.getUUID().equals(owner(player.getVehicle()))) return player.getVehicle();
        CompoundTag data = player.getPersistentData();
        Entity selected = data.hasUUID(CRAFT) ? player.serverLevel().getEntity(data.getUUID(CRAFT)) : null;
        return selected != null ? selected : nearestCraft(player, true);
    }
    private static LivingEntity selectedNpc(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        Entity selected = data.hasUUID(CREW) ? player.serverLevel().getEntity(data.getUUID(CREW)) : null;
        if (selected instanceof LivingEntity living) return living;
        return player.serverLevel().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(6), e -> ownedNpc(e, player.getUUID()) && selectableCrew(e, player.getUUID()))
            .stream().min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }
    private static Entity nearestCraft(ServerPlayer player, boolean ownedOnly) {
        return player.serverLevel().getEntities(player, player.getBoundingBox().inflate(6), e -> knownType(e) && (!ownedOnly || player.getUUID().equals(owner(e))))
            .stream().min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }
    private static boolean knownType(Entity entity) {
        if (entity instanceof Boat) return true;
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals("com.crescentine.trajanscore.basetank.BaseTankEntity") || type.getName().equals("xyz.przemyk.simpleplanes.entities.PlaneEntity")) return true;
        return false;
    }
    private static String adapterDescription(Entity craft) {
        VehicleAdapters.Driver adapter = VehicleAdapters.find(craft);
        return adapter == null ? "тип известен, NPC API этой версии не поддерживается" : adapter.name();
    }
    private static int fail(CommandSourceStack source, String text) { source.sendFailure(Component.literal(text)); return 0; }

    private static final class Route {
        final UUID owner, npc, craft;
        final ResourceKey<Level> dimension;
        final Vec3 destination;
        final VehicleAdapters.Driver adapter;
        Vec3 checkpoint;
        int age;
        boolean orbit;
        Route(UUID owner, UUID npc, UUID craft, ResourceKey<Level> dimension, Vec3 destination, VehicleAdapters.Driver adapter, Vec3 position) {
            this.owner = owner; this.npc = npc; this.craft = craft; this.dimension = dimension; this.destination = destination; this.adapter = adapter; this.checkpoint = position;
        }
    }
}
