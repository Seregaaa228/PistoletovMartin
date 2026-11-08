package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.world.RecruitsDiplomacyManager.DiplomacyStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Air-defence sites in a fresh world, observed from outside: the scenarios only place sites and
 * flying targets, give them owners and countries through Recruits' own diplomacy, move the
 * targets, and count what the sites do. Arcade classes are reached by registry id and reflection,
 * so this fixture does not compile against the arcade module.
 */
final class SamScenarios {
    static final String MISSILE = "frontier_arcade_vehicles:sam_missile";
    private SamScenarios() { }

    /** Two Recruits countries at war: A holds the test player and an offline friend, B an offline enemy. */
    static final class Countries {
        final String a, b;
        final UUID friend = UUID.randomUUID(), enemy = UUID.randomUUID(), neutral = UUID.randomUUID();
        private final ServerLevel level;
        private final ServerPlayer player;

        Countries(ServerLevel level, ServerPlayer player) {
            this.level = level;
            this.player = player;
            var manager = FactionEvents.recruitsFactionManager;
            if (manager == null || FactionEvents.recruitsDiplomacyManager == null) throw new IllegalStateException("Recruits diplomacy not initialised");
            String suffix = UUID.randomUUID().toString().substring(0, 6);
            a = "sama_" + suffix;
            b = "samb_" + suffix;
            manager.addTeam(a, "SAM test A", player.getUUID(), player.getGameProfile().getName(), new CompoundTag(), (byte) 0, ChatFormatting.BLUE);
            manager.addTeam(b, "SAM test B", enemy, "SamEnemy", new CompoundTag(), (byte) 1, ChatFormatting.RED);
            manager.getFactionByStringID(a).addMember(player.getUUID(), player.getGameProfile().getName());
            manager.getFactionByStringID(a).addMember(friend, "SamFriend");
            manager.getFactionByStringID(b).addMember(enemy, "SamEnemy");
            var scores = level.getScoreboard();
            scores.addPlayerToTeam(player.getGameProfile().getName(), scores.addPlayerTeam(a));
            var diplomacy = FactionEvents.recruitsDiplomacyManager;
            diplomacy.setRelation(a, b, DiplomacyStatus.ENEMY, level, false);
            diplomacy.setRelation(b, a, DiplomacyStatus.ENEMY, level, false);
        }

        void close() {
            var scores = level.getScoreboard();
            var team = scores.getPlayersTeam(player.getGameProfile().getName());
            if (team != null) scores.removePlayerFromTeam(player.getGameProfile().getName(), team);
            for (String country : List.of(a, b)) {
                var t = scores.getPlayerTeam(country);
                if (t != null) scores.removePlayerTeam(t);
                FactionEvents.recruitsFactionManager.removeTeam(country);
                FactionEvents.recruitsDiplomacyManager.diplomacyMap.remove(country);
                for (var relations : FactionEvents.recruitsDiplomacyManager.diplomacyMap.values()) relations.remove(country);
            }
        }
    }

    /** The server setting, through the arcade config class (not on this fixture's compile classpath). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void setAutoTargets(String mode) {
        try {
            Class<?> config = Class.forName("dev.frontier.arcade.airdefense.AirDefenseConfig");
            Class<?> modes = Class.forName("dev.frontier.arcade.airdefense.AirDefenseConfig$AutoTargets");
            Object value = config.getField("AUTO_TARGETS").get(null);
            value.getClass().getMethod("set", Object.class).invoke(value, Enum.valueOf((Class<Enum>) modes, mode));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("air-defence config unavailable", failure);
        }
    }

    static Entity site(ServerLevel level, String id, double x, double z, float yaw, UUID owner) {
        Entity site = TesterFix.create(level, id);
        int g = TesterFix.ground(level, (int) Math.floor(x), (int) Math.floor(z));
        site.moveTo(x, g + 0.05, z, yaw, 0);
        call(site, "setOwner", new Class<?>[]{UUID.class}, owner);
        if (!level.addFreshEntity(site)) throw new IllegalStateException("site not added: " + id);
        return site;
    }

    /** The current object for an entity: a chunk reload replaces the instance we created. */
    static Entity live(ServerLevel level, Entity entity) {
        Entity current = level.getEntity(entity.getUUID());
        return current != null ? current : entity;
    }

    static Object call(Object target, String name, Class<?>[] types, Object... args) {
        try { return target.getClass().getMethod(name, types).invoke(target, args); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(name + " on " + target, failure); }
    }

    static Object call(Object target, String name) { return call(target, name, new Class<?>[0]); }

    /** A flying target owned (for diplomacy) by {@code owner}; moved by the scenario. */
    static final class Target {
        final String label;
        Entity entity;
        final float maxHealth;
        Vec3 position, velocity;
        final double turn;
        int missilesAt;
        boolean killed;
        int killTick = -1;

        Target(ServerLevel level, String label, String id, UUID owner, Vec3 at, Vec3 velocity, double turnDegPerTick) {
            this.label = label;
            this.entity = TesterFix.create(level, id);
            entity.moveTo(at.x, at.y, at.z, 0, 0);
            entity.setNoGravity(true);
            entity.getPersistentData().putUUID("frontierIndustryVehicleOwner", owner);
            if (!level.addFreshEntity(entity)) throw new IllegalStateException("target not added: " + id);
            this.position = at;
            this.velocity = velocity;
            this.turn = Math.toRadians(turnDegPerTick);
            this.maxHealth = TesterFix.health(entity);
        }

        void fly(int tick) {
            if (entity.isRemoved() && entity.level() instanceof ServerLevel level) entity = live(level, entity);
            if (dead()) { if (!killed) { killed = true; killTick = tick; } return; }
            if (entity.isRemoved()) return; // gone without being shot down: not a kill
            if (turn != 0) {
                double c = Math.cos(turn), s = Math.sin(turn);
                velocity = new Vec3(velocity.x * c - velocity.z * s, velocity.y, velocity.x * s + velocity.z * c);
            }
            position = position.add(velocity);
            entity.setDeltaMovement(velocity);
            entity.setPos(position.x, position.y, position.z);
            entity.setYRot((float) Math.toDegrees(Math.atan2(-velocity.x, velocity.z)));
        }

        /** Shot down, not merely removed (a drone's signal loss or an unload discards it intact). */
        boolean dead() {
            if (entity instanceof VehicleEntity vehicle) return vehicle.isWreck() || vehicle.getHealth() <= 0;
            if (entity instanceof LivingEntity living) return living.isDeadOrDying();
            return entity.getRemovalReason() == Entity.RemovalReason.KILLED;
        }

        boolean hurt() { return dead() || TesterFix.health(entity) < maxHealth; }

        JsonObject report() {
            var out = new JsonObject();
            out.addProperty("type", String.valueOf(EntityType.getKey(entity.getType())));
            out.addProperty("missilesAt", missilesAt);
            out.addProperty("healthBefore", maxHealth);
            out.addProperty("healthAfter", TesterFix.health(entity));
            out.addProperty("destroyed", dead());
            out.addProperty("killTick", killTick);
            return out;
        }
    }

    /** Counts launches per target by reading each new missile's target once. */
    static final class MissileWatch {
        private final Set<Integer> seen = new HashSet<>();
        final Map<String, Integer> byType = new LinkedHashMap<>();
        int total, firstLaunchTick = -1;

        void tick(ServerLevel level, List<Target> targets, int tick) {
            for (Entity e : level.getAllEntities()) {
                if (!MISSILE.equals(String.valueOf(EntityType.getKey(e.getType()))) || !seen.add(e.getId())) continue;
                total++;
                if (firstLaunchTick < 0) firstLaunchTick = tick;
                byType.merge(String.valueOf(call(e, "missileType")), 1, Integer::sum);
                Object id = call(e, "targetId");
                for (Target t : targets) if (t.entity.getUUID().equals(id)) t.missilesAt++;
            }
        }

        JsonObject report() {
            var out = new JsonObject();
            out.addProperty("launches", total);
            out.addProperty("firstLaunchTick", firstLaunchTick);
            var types = new JsonObject();
            byType.forEach(types::addProperty);
            out.add("byMissile", types);
            return out;
        }
    }

    static JsonObject siteReport(Entity site) {
        var out = new JsonObject();
        out.addProperty("type", String.valueOf(EntityType.getKey(site.getType())));
        out.addProperty("alive", site.isAlive());
        out.addProperty("launches", (Integer) call(site, "launches"));
        out.addProperty("kills", (Integer) call(site, "kills"));
        out.addProperty("deploy", (Integer) call(site, "deployPercent"));
        return out;
    }

    // ============================================================ 1. friend or foe
    /**
     * An S-300PS in AUTO, owned by the test player of country A, with three jets crossing its zone:
     * one owned by an enemy of B, one by a friend in A, one by a neutral without a country. With the
     * default setting (all but friends) the enemy and the neutral are engaged; with "enemies only"
     * just the enemy. The friend never. The player rides the site for the HUD screenshots.
     */
    static final class Iff implements TesterFix.Scenario {
        private final boolean enemiesOnly;
        private final ServerPlayer player;
        private final ServerLevel level;
        private final Countries countries;
        private final Entity site;
        private final List<Target> targets = new ArrayList<>();
        private final MissileWatch watch = new MissileWatch();
        private final JsonObject result = new JsonObject();
        private int ticks, shots;

        Iff(ServerPlayer player, boolean enemiesOnly) {
            this.enemiesOnly = enemiesOnly;
            setAutoTargets(enemiesOnly ? "ENEMIES_ONLY" : "ALL_BUT_FRIENDS");
            this.player = player;
            this.level = player.serverLevel();
            if (player.isPassenger()) player.stopRiding();
            player.setGameMode(GameType.CREATIVE);
            countries = new Countries(level, player);
            int g = TesterFix.ground(level, 600, -600);
            player.teleportTo(level, 600.5, g + 1, -590.5, 180, 0);
            site = site(level, "frontier_arcade_vehicles:s300ps", 600.5, -600.5, 0, player.getUUID());
            result.addProperty("pass", false);
        }

        @Override public boolean tick() {
            ticks++;
            if (ticks == 10) {
                Entity current = live(level, site);
                // A stand-in driver takes seat 0, so the player boards into the commander's seat (mast camera).
                Entity driver = TesterFix.create(level, "minecraft:armor_stand");
                driver.moveTo(current.getX(), current.getY(), current.getZ());
                level.addFreshEntity(driver);
                result.addProperty("driverSeated", driver.startRiding(current, true));
                result.addProperty("crewMounted", player.startRiding(current, true));
                if (current instanceof VehicleEntity vehicle) result.addProperty("crewSeat", vehicle.getSeatIndex(player));
            }
            if (ticks == 20) {
                int g = TesterFix.ground(level, 600, -600);
                Vec3 c = site.position();
                targets.add(new Target(level, "enemy", "frontier_aviation:f16", countries.enemy,
                    new Vec3(c.x - 300, g + 70, c.z + 70), new Vec3(3.0, 0, 0), 0));
                targets.add(new Target(level, "friend", "frontier_aviation:f16", countries.friend,
                    new Vec3(c.x - 300, g + 80, c.z - 70), new Vec3(3.0, 0, 0), 0));
                targets.add(new Target(level, "neutral", "frontier_aviation:j10", countries.neutral,
                    new Vec3(c.x + 300, g + 90, c.z + 150), new Vec3(-3.0, 0, 0), 0));
            }
            for (Target t : targets) t.fly(ticks);
            watch.tick(level, targets, ticks);
            // Screenshots: the HUD before launch, a missile in flight, and the burst.
            if (watch.firstLaunchTick > 0 && shots == 0 && ticks >= watch.firstLaunchTick + 6) { shots++; TesterFix.requestActions("shot sam-iff-launch"); }
            if (shots == 1 && ticks >= watch.firstLaunchTick + 24) { shots++; TesterFix.requestActions("key F5", "sleep 0.4", "shot sam-iff-third", "key F5", "key F5"); }
            if (ticks == 100) TesterFix.requestActions("shot sam-iff-hud");
            boolean enemyDown = !targets.isEmpty() && targets.get(0).dead();
            boolean neutralSettled = enemiesOnly || targets.size() > 2 && targets.get(2).missilesAt > 0;
            if ((enemyDown && neutralSettled && ticks > 260) || ticks >= 520) return finish();
            return false;
        }

        private boolean finish() {
            result.addProperty("ticks", ticks);
            var list = new JsonObject();
            for (Target t : targets) list.add(t.label, t.report());
            result.add("targets", list);
            result.add("missiles", watch.report());
            result.add("site", siteReport(live(level, site)));
            Target enemy = targets.get(0), friend = targets.get(1), neutral = targets.get(2);
            boolean neutralOk = enemiesOnly ? neutral.missilesAt == 0 && !neutral.hurt() : neutral.missilesAt > 0;
            boolean pass = enemy.missilesAt > 0 && enemy.hurt() && friend.missilesAt == 0 && !friend.hurt() && neutralOk;
            result.addProperty("autoTargets", enemiesOnly ? "ENEMIES_ONLY" : "ALL_BUT_FRIENDS");
            setAutoTargets("ALL_BUT_FRIENDS");
            result.addProperty("pass", pass);
            player.stopRiding();
            for (Target t : targets) t.entity.discard();
            live(level, site).discard();
            countries.close();
            TesterFix.land(player);
            return true;
        }

        @Override public JsonObject result() { return result; }
    }

    // ============================================================ 2. drones
    /**
     * Pantsir-S1, S-125 and S-400 against a raid of Shahed-136 and jet Geranium-2 drones of the
     * enemy country, plus one friendly Shahed that must be left alone.
     */
    static final class Drones implements TesterFix.Scenario {
        private final ServerPlayer player;
        private final ServerLevel level;
        private final Countries countries;
        private final List<Entity> sites = new ArrayList<>();
        private final List<Target> targets = new ArrayList<>();
        private final MissileWatch watch = new MissileWatch();
        private final JsonObject result = new JsonObject();
        private int ticks;

        Drones(ServerPlayer player) {
            this.player = player;
            this.level = player.serverLevel();
            if (player.isPassenger()) player.stopRiding();
            player.setGameMode(GameType.CREATIVE);
            countries = new Countries(level, player);
            int g = TesterFix.ground(level, -600, -600);
            player.teleportTo(level, -600.5, g + 30, -660.5, 0, 10);
            sites.add(site(level, "frontier_arcade_vehicles:pantsir_s1", -600.5, -600.5, 0, player.getUUID()));
            sites.add(site(level, "frontier_arcade_vehicles:s125_pechora", -640.5, -560.5, 0, player.getUUID()));
            sites.add(site(level, "frontier_arcade_vehicles:s400_triumf", -560.5, -560.5, 0, player.getUUID()));
            result.addProperty("pass", false);
        }

        @Override public boolean tick() {
            ticks++;
            if (ticks == 110) {
                int g = TesterFix.ground(level, -600, -600);
                Vec3 c = sites.get(0).position();
                String shahed = "frontier_arcade_vehicles:shahed_136_drone", jet = "frontier_arcade_vehicles:geranium_2_drone";
                // A raid from the north-east at low level, spread out, and a friendly drone on its own course.
                targets.add(new Target(level, "shahed_1", shahed, countries.enemy, new Vec3(c.x + 180, g + 35, c.z + 200), new Vec3(-1.4, 0, -1.6), 0));
                targets.add(new Target(level, "shahed_2", shahed, countries.enemy, new Vec3(c.x + 230, g + 45, c.z + 140), new Vec3(-1.7, 0, -1.2), 0));
                targets.add(new Target(level, "geranium_jet_1", jet, countries.enemy, new Vec3(c.x + 260, g + 40, c.z + 220), new Vec3(-2.3, 0, -2.0), 0));
                targets.add(new Target(level, "geranium_jet_2", jet, countries.enemy, new Vec3(c.x - 250, g + 50, c.z + 180), new Vec3(2.5, 0, -1.6), 0));
                targets.add(new Target(level, "friendly_shahed", shahed, countries.friend, new Vec3(c.x - 200, g + 40, c.z - 100), new Vec3(2.0, 0, 0.3), 0));
                TesterFix.requestActions("sleep 4", "shot sam-drones-raid");
            }
            for (Target t : targets) t.fly(ticks);
            watch.tick(level, targets, ticks);
            boolean enemiesDown = targets.size() == 5 && targets.subList(0, 4).stream().allMatch(Target::dead);
            if ((enemiesDown && ticks > 200) || ticks >= 520) return finish();
            return false;
        }

        private boolean finish() {
            result.addProperty("ticks", ticks);
            var list = new JsonObject();
            for (Target t : targets) list.add(t.label, t.report());
            result.add("targets", list);
            result.add("missiles", watch.report());
            var siteList = new JsonArray();
            for (Entity s : sites) siteList.add(siteReport(live(level, s)));
            result.add("sites", siteList);
            boolean enemies = targets.size() == 5 && targets.subList(0, 4).stream().allMatch(Target::dead);
            Target friend = targets.isEmpty() ? null : targets.get(targets.size() - 1);
            result.addProperty("pass", enemies && friend != null && friend.missilesAt == 0 && !friend.hurt());
            for (Target t : targets) t.entity.discard();
            for (Entity s : sites) live(level, s).discard();
            countries.close();
            TesterFix.land(player);
            return true;
        }

        @Override public JsonObject result() { return result; }
    }

    // ============================================================ 4. poses
    /**
     * The four sites in a row facing south, each in MANUAL (aims, does not fire) with an enemy jet
     * hovering front-left and about 30 degrees up. Screenshots from fixed points show whether the
     * turrets and launchers point at it and how the S-300/S-400 deploy (jacks, rams, canisters).
     */
    static final class Poses implements TesterFix.Scenario {
        private static final String[] SITES = {"s125_pechora", "s300ps", "s400_triumf", "pantsir_s1", "patriot_m901"};
        private final ServerPlayer player;
        private final ServerLevel level;
        private final Countries countries;
        private final List<Entity> sites = new ArrayList<>();
        private final List<Target> targets = new ArrayList<>();
        private final JsonObject result = new JsonObject();
        private int ticks;
        private final int g;

        Poses(ServerPlayer player) {
            this.player = player;
            this.level = player.serverLevel();
            if (player.isPassenger()) player.stopRiding();
            player.setGameMode(GameType.CREATIVE);
            countries = new Countries(level, player);
            g = TesterFix.ground(level, -1200, 0);
            player.teleportTo(level, -1180.5, g + 12, -20.5, 45, 20);
            for (int i = 0; i < SITES.length; i++) {
                Entity site = site(level, "frontier_arcade_vehicles:" + SITES[i], -1200.5 - i * 18, 0.5, 0, player.getUUID());
                call(site, "setMode", new Class<?>[]{byte.class}, (byte) 1);
                sites.add(site);
            }
            result.addProperty("pass", false);
        }

        @Override public boolean tick() {
            ticks++;
            if (ticks == 30) for (int i = 0; i < sites.size(); i++) {
                Vec3 c = live(level, sites.get(i)).position();
                // Due east (+X) and about 35 degrees up: east is the site's left, since it faces south.
                targets.add(new Target(level, "t" + i, "frontier_aviation:f16", countries.enemy, c.add(40, 28, 0), Vec3.ZERO, 0));
            }
            for (Target t : targets) t.fly(ticks);
            double cx = -1236.5, z = 0.5;
            if (ticks == 60) TesterFix.requestActions("tp " + (cx + 34) + " " + (g + 6) + " " + (z - 30) + " 50 8", "sleep 1.2", "shot sam-pose-early");
            if (ticks == 200) {
                // Top-down over each site (north up, east right) and one side view from the south.
                List<String> steps = new ArrayList<>();
                for (int i = 0; i < sites.size(); i++) {
                    Vec3 c = live(level, sites.get(i)).position();
                    // The player falls after a teleport: take the shot before it has dropped far.
                    steps.add("tp " + c.x + " " + (g + 26) + " " + c.z + " 180 90");
                    steps.add("sleep 0.3");
                    steps.add("shot sam-pose-top-" + SITES[i]);
                }
                steps.add("tp " + cx + " " + (g + 4) + " " + (z + 40) + " 180 -12");
                steps.add("sleep 1.2");
                steps.add("shot sam-pose-south");
                TesterFix.requestActions(steps.toArray(String[]::new));
            }
            if (ticks >= 380) {
                var poses = new JsonArray();
                boolean all = targets.size() == sites.size();
                for (int i = 0; i < sites.size(); i++) {
                    Entity site = live(level, sites.get(i));
                    var row = siteReport(site);
                    float yaw = (Float) call(site, "aimYaw"), pitch = (Float) call(site, "aimPitch");
                    row.addProperty("aimYaw", yaw);
                    row.addProperty("aimPitch", pitch);
                    // Vertical packs (S-300, S-400) launch straight up and do not train toward a target.
                    boolean vertical = SITES[i].equals("s300ps") || SITES[i].equals("s400_triumf");
                    row.addProperty("vertical", vertical);
                    if (!vertical) {
                        // The sites stand in a row, so a neighbour's jet may be the most threatening
                        // track: the turret must point at one of the jets. Same convention as the
                        // site's slew: yaw relative to the hull, Minecraft degrees.
                        float yawError = 180;
                        String at = "none";
                        for (Target t : targets) {
                            Vec3 d = t.entity.position().subtract(site.position());
                            float bearing = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-d.x, d.z)) - site.getYRot());
                            float error = Math.abs(Mth.wrapDegrees(yaw - bearing));
                            if (error < yawError) { yawError = error; at = t.label; }
                        }
                        boolean ok = yawError <= 10 && pitch >= 5 && pitch <= 80;
                        row.addProperty("aimedAt", at);
                        row.addProperty("yawError", yawError);
                        row.addProperty("aimOk", ok);
                        all &= ok;
                    }
                    poses.add(row);
                }
                result.add("sites", poses);
                result.addProperty("pass", all);
                for (Target t : targets) t.entity.discard();
                for (Entity s : sites) live(level, s).discard();
                countries.close();
                TesterFix.land(player);
                return true;
            }
            return false;
        }

        @Override public JsonObject result() { return result; }
    }

    // ============================================================ 5. effects
    /**
     * Launch, trail and burst from the side: an S-125 and a Pantsir, each with an enemy jet hovering
     * north of it; the camera stands to the east looking west across both flight paths and takes a
     * series of screenshots from the first launch on.
     */
    static final class Effects implements TesterFix.Scenario {
        private final ServerPlayer player;
        private final ServerLevel level;
        private final Countries countries;
        private final List<Entity> sites = new ArrayList<>();
        private final List<Target> targets = new ArrayList<>();
        private final MissileWatch watch = new MissileWatch();
        private final JsonObject result = new JsonObject();
        private int ticks, shotsAt = -1;
        private final int g;

        Effects(ServerPlayer player) {
            this.player = player;
            this.level = player.serverLevel();
            if (player.isPassenger()) player.stopRiding();
            player.setGameMode(GameType.CREATIVE);
            countries = new Countries(level, player);
            g = TesterFix.ground(level, 1400, 0);
            sites.add(site(level, "frontier_arcade_vehicles:s125_pechora", 1400.5, 0.5, 180, player.getUUID()));
            sites.add(site(level, "frontier_arcade_vehicles:pantsir_s1", 1400.5, 40.5, 180, player.getUUID()));
            player.teleportTo(level, 1438.5, g + 3, 0.5, 125, -14);
            result.addProperty("pass", false);
        }

        @Override public boolean tick() {
            ticks++;
            if (ticks == 20) for (Entity s : sites) {
                Vec3 c = live(level, s).position();
                targets.add(new Target(level, "t" + targets.size(), "frontier_aviation:f16", countries.enemy,
                    c.add(0, 45, -110), new Vec3(0.6, 0, 0), 0));
            }
            for (Target t : targets) t.fly(ticks);
            watch.tick(level, targets, ticks);
            if (watch.firstLaunchTick > 0 && shotsAt < 0) {
                shotsAt = ticks;
                TesterFix.requestActions("tp 1438.5 " + (g + 3) + " 0.5 125 -14", "shot sam-fx-0", "sleep 0.25", "shot sam-fx-1",
                    "sleep 0.3", "shot sam-fx-2", "sleep 0.4", "shot sam-fx-3", "sleep 0.5", "shot sam-fx-4", "sleep 0.8", "shot sam-fx-5");
            }
            if (ticks >= 260 || shotsAt > 0 && ticks > shotsAt + 120) {
                for (Target t : targets) result.add(t.label, t.report());
                result.add("missiles", watch.report());
                result.addProperty("pass", watch.total > 0);
                for (Target t : targets) t.entity.discard();
                for (Entity s : sites) live(level, s).discard();
                countries.close();
                TesterFix.land(player);
                return true;
            }
            return false;
        }

        @Override public JsonObject result() { return result; }
    }

    // ============================================================ 6. legacy air defence
    /**
     * The older systems brought into this one, through their own classes (reflection: Industry is
     * not on this fixture's compile classpath):
     * <ol>
     *   <li>Industry's S-300, S-400 and Patriot launchers are replaced by our complexes, owner kept;</li>
     *   <li>the converted S-300 intercepts an enemy Industry ballistic missile passing over it.</li>
     * </ol>
     */
    static final class Legacy implements TesterFix.Scenario {
        private static final String[] KINDS = {"S300", "S400", "PATRIOT"};
        private static final String[] EXPECTED = {"frontier_arcade_vehicles:s300ps",
            "frontier_arcade_vehicles:s400_triumf", "frontier_arcade_vehicles:patriot_m901"};
        /** A SAM missile at the ballistic within this many blocks when it is removed: the burst got it. */
        private static final double INTERCEPT_DISTANCE = 12;
        private final Map<Integer, Entity> interceptors = new HashMap<>();
        private final ServerPlayer player;
        private final ServerLevel level;
        private final Countries countries;
        private final JsonObject result = new JsonObject();
        private final List<UUID> launchers = new ArrayList<>();
        private Entity ballistic;
        private int ticks, ballisticDue = -1, ballisticGone = -1;
        private final int g;
        private UUID converted;
        private int launchesBefore;

        Legacy(ServerPlayer player) {
            this.player = player;
            this.level = player.serverLevel();
            if (player.isPassenger()) player.stopRiding();
            player.setGameMode(GameType.CREATIVE);
            countries = new Countries(level, player);
            g = TesterFix.ground(level, 2000, 0);
            player.teleportTo(level, 2010.5, g + 2, -20.5, 0, 0);
            for (int i = 0; i < KINDS.length; i++) {
                Entity launcher = TesterFix.create(level, "frontier_industry:combat_launcher");
                try {
                    Class<?> kind = Class.forName("dev.frontier.industry.combat.LauncherEntity$Kind", false, launcher.getClass().getClassLoader());
                    @SuppressWarnings({"unchecked", "rawtypes"}) Object value = Enum.valueOf((Class<Enum>) kind, KINDS[i]);
                    launcher.getClass().getMethod("configure", kind, UUID.class).invoke(launcher, value, player.getUUID());
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                launcher.moveTo(2000.5 + i * 24, g, 0.5, 0, 0);
                if (!level.addFreshEntity(launcher)) throw new IllegalStateException("industry launcher not added");
                launchers.add(launcher.getUUID());
            }
            result.addProperty("pass", false);
        }

        @Override public boolean tick() {
            ticks++;
            if (ticks == 10) {
                var conversions = new JsonArray();
                boolean all = true;
                for (int i = 0; i < launchers.size(); i++) {
                    Entity old = level.getEntity(launchers.get(i));
                    Entity site = null;
                    for (Entity e : level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(2000 + i * 24 - 3, g - 2, -3, 2000 + i * 24 + 4, g + 4, 4),
                            e -> String.valueOf(EntityType.getKey(e.getType())).matches("frontier_arcade_vehicles:(s300ps|s400_triumf|patriot_m901)"))) site = e;
                    String replacement = site == null ? "none" : String.valueOf(EntityType.getKey(site.getType()));
                    var row = new JsonObject();
                    row.addProperty("oldGone", old == null || !old.isAlive());
                    row.addProperty("replacement", replacement);
                    row.addProperty("expected", EXPECTED[i]);
                    row.addProperty("ownerKept", site != null && player.getUUID().equals(call(site, "ownerId")));
                    all &= (old == null || !old.isAlive()) && EXPECTED[i].equals(replacement)
                        && player.getUUID().equals(call(site, "ownerId"));
                    if (i == 0 && site != null) converted = site.getUUID();
                    conversions.add(row);
                }
                result.add("conversions", conversions);
                result.addProperty("conversionsPass", all);
            }
            // The S-300 needs ~5 s to raise its canisters; then an enemy ballistic missile comes in.
            if (ticks == 130 && converted != null) {
                Entity site = level.getEntity(converted);
                ballistic = TesterFix.create(level, "frontier_industry:combat_ballistic_missile");
                // From 180 blocks out, over the site, to 180 blocks past it (all within simulation distance),
                // 20 blocks up at both ends, so it ends in the air: early removal is not a hillside.
                Vec3 target = site.position().add(180, 20, 30), origin = site.position().add(-180, 20, 30);
                launchesBefore = (Integer) call(site, "launches");
                ballistic.moveTo(origin.x, origin.y, origin.z, 0, 0);
                call(ballistic, "configure", new Class<?>[]{UUID.class, UUID.class, Vec3.class, Vec3.class}, countries.enemy, UUID.randomUUID(), origin, target);
                if (!level.addFreshEntity(ballistic)) throw new IllegalStateException("ballistic missile not added");
                ballisticDue = ticks + (Integer) call(ballistic, "flightDuration");
                result.addProperty("ballisticFlightTicks", ballisticDue - ticks);
            }
            if (ballistic != null && ballisticGone < 0) {
                // Every SAM missile sent at the ballistic, kept after its own removal for its last position.
                for (Entity e : level.getAllEntities())
                    if (MISSILE.equals(String.valueOf(EntityType.getKey(e.getType())))
                            && ballistic.getUUID().equals(call(e, "targetId"))) interceptors.putIfAbsent(e.getId(), e);
            }
            if (ballistic != null && ballisticGone < 0 && !ballistic.isAlive()) {
                ballisticGone = ticks;
                result.addProperty("ballisticRemovedAt", ticks);
                double nearest = Double.MAX_VALUE;
                String by = "none";
                for (Entity m : interceptors.values()) {
                    double d = m.position().distanceTo(ballistic.position());
                    if (d < nearest) { nearest = d; by = String.valueOf(call(m, "siteId")); }
                }
                Entity site = level.getEntity(converted);
                int s300 = site == null ? 0 : (Integer) call(site, "launches") - launchesBefore;
                result.addProperty("s300LaunchesAtBallistic", s300);
                result.addProperty("missilesAtBallistic", interceptors.size());
                result.addProperty("nearestInterceptor", nearest == Double.MAX_VALUE ? -1 : TesterFix.round(nearest));
                result.addProperty("interceptorFromS300", converted != null && converted.toString().equals(by));
                // Removed early, in the air, with a SAM missile aimed at it bursting right there (any of
                // the converted sites may take it: the S-400 and Patriot stand in range as well).
                result.addProperty("ballisticIntercepted", ticks < ballisticDue - 2 && !interceptors.isEmpty()
                    && nearest <= INTERCEPT_DISTANCE);
            }
            if (ticks >= 260) return finish();
            return false;
        }

        private boolean finish() {
            result.addProperty("ticks", ticks);
            boolean ballisticOk = result.has("ballisticIntercepted") && result.get("ballisticIntercepted").getAsBoolean();
            result.addProperty("pass", result.has("conversionsPass") && result.get("conversionsPass").getAsBoolean() && ballisticOk);
            for (Entity e : level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(1990, g - 4, -10, 2070, g + 10, 10),
                    e -> String.valueOf(EntityType.getKey(e.getType())).matches("frontier_arcade_vehicles:(s300ps|s400_triumf|patriot_m901)"))) e.discard();
            countries.close();
            TesterFix.land(player);
            return true;
        }

        @Override public JsonObject result() { return result; }
    }

    // ============================================================ 3. load
    /**
     * Six sites and twelve circling enemy aircraft and drones that respawn when shot down.
     * Ticks 1-300: the targets alone (baseline). Ticks 301-1500: with the sites, the player
     * riding one of them. Records server tick time and clientbound traffic for both phases.
     */
    static final class Load implements TesterFix.Scenario {
        private static final int BASELINE = 300, END = 1500;
        private final ServerPlayer player;
        private final ServerLevel level;
        private final Countries countries;
        private final List<Entity> sites = new ArrayList<>();
        private final List<Target> targets = new ArrayList<>();
        private final MissileWatch watch = new MissileWatch();
        private final JsonObject result = new JsonObject();
        private final List<Double> baseTicks = new ArrayList<>(), loadTicks = new ArrayList<>();
        private final Vec3 centre;
        private int ticks, respawns;

        Load(ServerPlayer player) {
            this.player = player;
            this.level = player.serverLevel();
            if (player.isPassenger()) player.stopRiding();
            player.setGameMode(GameType.CREATIVE);
            countries = new Countries(level, player);
            int g = TesterFix.ground(level, 0, 900);
            centre = new Vec3(0.5, g, 900.5);
            player.teleportTo(level, centre.x, g + 2, centre.z - 30, 0, 0);
            for (int i = 0; i < 4; i++) spawnJet(i);
            for (int i = 0; i < 8; i++) spawnDrone(i);
            PacketBytes.start();
            result.addProperty("pass", false);
        }

        private Target spawnJet(int i) {
            double a = i * Math.PI / 2;
            Vec3 at = centre.add(Math.cos(a) * 150, 80 + i * 6, Math.sin(a) * 150);
            Vec3 v = new Vec3(-Math.sin(a) * 3.0, 0, Math.cos(a) * 3.0);
            var t = new Target(level, "jet_" + i, i % 2 == 0 ? "frontier_aviation:f16" : "frontier_aviation:su25", countries.enemy, at, v, Math.toDegrees(3.0 / 150));
            targets.add(t);
            return t;
        }

        private Target spawnDrone(int i) {
            double a = i * Math.PI / 4 + 0.3;
            Vec3 at = centre.add(Math.cos(a) * 110, 35 + (i % 3) * 5, Math.sin(a) * 110);
            Vec3 v = new Vec3(-Math.sin(a) * 2.1, 0, Math.cos(a) * 2.1);
            var t = new Target(level, "drone_" + i, "frontier_arcade_vehicles:shahed_136_drone", countries.enemy, at, v, Math.toDegrees(2.1 / 110));
            targets.add(t);
            return t;
        }

        @Override public boolean tick() {
            ticks++;
            if (ticks > 3) (ticks <= BASELINE ? baseTicks : loadTicks).add(TesterFix.tickMs(level.getServer()));
            if (ticks == BASELINE) {
                result.add("baselineTraffic", PacketBytes.summary(BASELINE / 20.0));
                PacketBytes.stop();
                String[] kinds = {"s125_pechora", "s300ps", "s400_triumf", "pantsir_s1", "patriot_m901", "pantsir_s1"};
                for (int i = 0; i < kinds.length; i++) {
                    double a = i * Math.PI * 2 / kinds.length;
                    sites.add(site(level, "frontier_arcade_vehicles:" + kinds[i], centre.x + Math.cos(a) * 60, centre.z + Math.sin(a) * 60,
                        (float) Math.toDegrees(a), player.getUUID()));
                }
            }
            if (ticks == BASELINE + 5) result.addProperty("crewMounted", player.startRiding(live(level, sites.get(3)), true));
            if (ticks == BASELINE + 20) PacketBytes.start();
            if (ticks == BASELINE + 400) TesterFix.requestActions("shot sam-load");
            for (int i = 0; i < targets.size(); i++) {
                Target t = targets.get(i);
                t.fly(ticks);
                // Keep the sky busy: a downed target comes back on its circle a few seconds later.
                if (t.killed && ticks - t.killTick > 60 && ticks < END - 100) {
                    t.entity.discard();
                    targets.remove(i);
                    respawns++;
                    Target fresh = t.label.startsWith("jet") ? spawnJet(Integer.parseInt(t.label.substring(4))) : spawnDrone(Integer.parseInt(t.label.substring(6)));
                    targets.remove(fresh);
                    targets.add(i, fresh);
                }
            }
            if (ticks > BASELINE) watch.tick(level, targets, ticks);
            if (ticks >= END) return finish();
            return false;
        }

        private static JsonObject stats(List<Double> samples) {
            double[] sorted = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            var out = new JsonObject();
            if (sorted.length == 0) return out;
            out.addProperty("samples", sorted.length);
            out.addProperty("meanMs", TesterFix.round(Arrays.stream(sorted).average().orElse(0)));
            out.addProperty("p50Ms", TesterFix.round(sorted[sorted.length / 2]));
            out.addProperty("p95Ms", TesterFix.round(sorted[(int) (sorted.length * .95)]));
            out.addProperty("maxMs", TesterFix.round(sorted[sorted.length - 1]));
            return out;
        }

        private boolean finish() {
            result.add("traffic", PacketBytes.summary((END - BASELINE - 20) / 20.0));
            PacketBytes.stop();
            result.add("baselineTick", stats(baseTicks));
            result.add("loadTick", stats(loadTicks));
            result.add("missiles", watch.report());
            result.addProperty("respawns", respawns);
            var siteList = new JsonArray();
            for (Entity s : sites) siteList.add(siteReport(live(level, s)));
            result.add("sites", siteList);
            result.addProperty("pass", watch.total > 0 && respawns > 0);
            player.stopRiding();
            for (Target t : targets) t.entity.discard();
            for (Entity s : sites) live(level, s).discard();
            countries.close();
            TesterFix.land(player);
            return true;
        }

        @Override public JsonObject result() { return result; }
    }
}
