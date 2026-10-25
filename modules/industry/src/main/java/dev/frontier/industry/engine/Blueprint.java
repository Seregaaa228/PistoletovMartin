package dev.frontier.industry.engine;

import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.SiteType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Original industrial buildings. Version one is frozen for already saved sites. */
public final class Blueprint {
    public static final int LEGACY_VERSION = 1, CURRENT_VERSION = 3;
    private static final Map<SiteType, Map<BlockPos, BlockState>> CURRENT = new EnumMap<>(SiteType.class);
    private static final Map<SiteType, Map<BlockPos, BlockState>> CRITICAL_V2 = new EnumMap<>(SiteType.class);
    private static final Map<SiteType, Map<BlockPos, BlockState>> CRITICAL_V3 = new EnumMap<>(SiteType.class);
    private Blueprint() {}
    public static int savedVersion(int value) { return value == 2 || value == 3 ? value : LEGACY_VERSION; }
    public static BlueprintBounds bounds(SiteType type) { return bounds(type, CURRENT_VERSION); }
    public static BlueprintBounds bounds(SiteType type, int version) {
        if (version == LEGACY_VERSION) {
            int r = type == SiteType.SUBSTATION ? 2 : 3;
            return new BlueprintBounds(-r, 0, -r, r, 4, r);
        }
        return switch (type) {
            case POWER_STATION -> new BlueprintBounds(-11, 0, -10, 11, 24, 10);
            case SUBSTATION -> new BlueprintBounds(-5, 0, -5, 5, 7, 5);
            case DRONE_FACTORY -> new BlueprintBounds(-7, 0, -7, 7, 8, 7);
            case VEHICLE_FACTORY -> new BlueprintBounds(-8, 0, -8, 8, 9, 8);
            case EQUIPMENT_FACTORY -> new BlueprintBounds(-7, 0, -7, 7, 10, 7);
            case MISSILE_FACTORY -> new BlueprintBounds(-7, 0, -9, 7, 12, 9);
            case RESEARCH_CENTER -> new BlueprintBounds(-7,0,-7,7,9,7);
        };
    }
    public static int radius(SiteType type) { return radius(type, CURRENT_VERSION); }
    public static int radius(SiteType type, int version) { return bounds(type, version).radius(); }
    public static BlockPos entrance(SiteType type, BlockPos origin) { return entrance(type, origin, CURRENT_VERSION); }
    public static BlockPos entrance(SiteType type, BlockPos origin, int version) { return origin.offset(0, 1, bounds(type, version).minZ()); }
    public static BlockPos controller(SiteType type, BlockPos origin) { return controller(type, origin, CURRENT_VERSION); }
    public static BlockPos controller(SiteType type, BlockPos origin, int version) {
        return version >= 2 ? origin.offset(type == SiteType.VEHICLE_FACTORY ? 4 : 2, 1, bounds(type, version).minZ() + (version>=3?4:2))
            : origin.offset(1, 1, -radius(type, version));
    }
    public static Map<BlockPos, BlockState> blocks(SiteType type, BlockPos origin) { return blocks(type, origin, CURRENT_VERSION); }
    public static Map<BlockPos, BlockState> blocks(SiteType type, BlockPos origin, int version) {
        if(version>=3)return dev.frontier.industry.architecture.IndustryArchitecture.blocks(type,origin);
        if (version == LEGACY_VERSION) return legacy(type, origin);
        Map<BlockPos, BlockState> relative = CURRENT.computeIfAbsent(type, Blueprint::designed);
        Map<BlockPos, BlockState> result = new LinkedHashMap<>(relative.size());
        relative.forEach((pos, state) -> result.put(origin.offset(pos), state));
        return result;
    }
    /** Destroying highlighted turbine or transformer cores breaks a new site's power circuit. */
    public static Map<BlockPos, BlockState> criticalBlocks(SiteType type, BlockPos origin, int version) {
        if (version == LEGACY_VERSION || type.isConsumer()) return Map.of();
        Map<SiteType, Map<BlockPos, BlockState>> cache = version >= 3 ? CRITICAL_V3 : CRITICAL_V2;
        Map<BlockPos, BlockState> relative = cache.computeIfAbsent(type, key -> criticalRelative(key, version));
        Map<BlockPos, BlockState> result = new LinkedHashMap<>(relative.size());
        relative.forEach((pos, state) -> result.put(origin.offset(pos), state));
        return result;
    }

    private static Map<BlockPos, BlockState> criticalRelative(SiteType type, int version) {
        List<BlockPos> local = switch (type) {
            case POWER_STATION -> List.of(new BlockPos(-6, 2, -1), new BlockPos(-2, 2, -1));
            case SUBSTATION -> List.of(new BlockPos(-2, 2, 0), new BlockPos(2, 2, 0));
            default -> List.of();
        };
        // Resolve the exact existing architecture once per version/type, retaining
        // only the two relative critical states rather than rebuilding it each tick.
        Map<BlockPos, BlockState> blueprint = version>=3 ? blocks(type,BlockPos.ZERO,version) : CURRENT.computeIfAbsent(type, Blueprint::designed);
        Map<BlockPos, BlockState> result = new LinkedHashMap<>(local.size());
        for (BlockPos pos : local) result.put(pos, blueprint.get(pos));
        return Collections.unmodifiableMap(result);
    }
    private static Map<BlockPos, BlockState> designed(SiteType type) {
        Layout b = new Layout(); BlueprintBounds f = bounds(type);
        b.fill(f.minX(), 0, f.minZ(), f.maxX(), 0, f.maxZ(), "floor");
        b.ring(f.minX(), 0, f.minZ(), f.maxX(), f.maxZ(), "concrete");
        b.fill(-1, 0, f.minZ(), 1, 0, f.maxZ(), "concrete");
        switch (type) {
            case POWER_STATION -> powerStation(b);
            case SUBSTATION -> substation(b);
            case DRONE_FACTORY -> droneLab(b);
            case VEHICLE_FACTORY -> vehicleHangar(b);
            case EQUIPMENT_FACTORY -> armsWorkshop(b);
            case MISSILE_FACTORY -> missileHall(b);
            case RESEARCH_CENTER -> throw new IllegalArgumentException("Research centre requires blueprint v3");
        }
        BlockPos c = controller(type, BlockPos.ZERO, 2);
        b.put(c.getX(), c.getY(), c.getZ(), IndustryContent.controller(type).defaultBlockState());
        b.put(c.getX(), 2, c.getZ(), "light");
        return Collections.unmodifiableMap(b.blocks);
    }
    private static void powerStation(Layout b) {
        // Turbine hall, taller rear boilers, side fuel silos and twin white/red flues.
        b.shell(-9, -8, 3, 3, 6, "concrete", "steel"); b.open(-1, 1, -8, 1, 3, -8);
        b.fill(-2, 4, -8, 2, 4, -8, "blue");
        b.fill(-9, 1, 4, 3, 10, 8, "concrete"); b.open(-8, 1, 4, 2, 5, 7);
        b.fill(-9, 10, 4, 3, 10, 8, "roof");
        for (int x : new int[]{-9, -5, -1, 3}) b.fill(x, 1, 4, x, 9, 4, "steel");
        for (int x : new int[]{-6, -2}) {
            b.fill(x - 1, 1, -2, x + 1, 1, 1, "steel"); b.fill(x, 2, -2, x, 2, 1, "copper");
            b.put(x, 2, -1, "iron"); b.fill(x - 1, 2, 1, x + 1, 2, 1, "steel"); b.put(x, 3, 1, "light");
        }
        b.fill(-9, 7, -8, 3, 7, 3, "roof"); b.fill(-7, 7, -5, 1, 7, -4, "glass");
        b.fill(-7, 8, -2, 1, 8, 0, "steel");
        for (int x : new int[]{-6, 0}) b.chimney(x, 7, 6, 24);
        for (int z : new int[]{-3, 5}) {
            b.cylinder(7, z, 2, 1, 6, "steel", true); b.cylinder(7, z, 2, 7, 7, "coal", false);
            for (int x : new int[]{5, 9}) b.fill(x, 1, z, x, 2, z, "concrete");
        }
        b.fill(4, 4, 1, 8, 4, 2, "steel"); b.fill(4, 5, 1, 8, 5, 1, "coal");
        b.fill(4, 6, 0, 8, 6, 3, "roof"); b.fill(8, 1, 1, 8, 3, 1, "steel");
        for (int x = -8; x <= 2; x += 2) b.put(x, 6, -7, "light");
        b.put(2, 3, -8, "red");
    }
    private static void substation(Layout b) {
        // Open transformer yard, ceramic bushings, overhead busbars and a small control hut.
        b.fill(-5, 1, 5, 5, 1, 5, "steel"); b.fill(-5, 1, -3, -5, 1, 4, "steel"); b.fill(5, 1, -3, 5, 1, 4, "steel");
        for (int x : new int[]{-2, 2}) {
            b.fill(x - 1, 1, -1, x + 1, 1, 1, "steel"); b.fill(x, 2, -1, x, 2, 1, "copper");
            b.put(x - 1, 2, 0, "steel"); b.put(x + 1, 2, 0, "steel");
            for (int z : new int[]{-1, 1}) { b.put(x, 3, z, "insulator"); b.put(x, 4, z, "copper"); }
        }
        for (int x : new int[]{-4, 4}) { b.fill(x, 1, 3, x, 6, 3, "steel"); b.put(x, 7, 3, "insulator"); }
        b.fill(-4, 6, 3, 4, 6, 3, "steel"); b.fill(-3, 7, 3, 3, 7, 3, "copper");
        b.fill(-3, 1, -4, -1, 3, -2, "concrete"); b.open(-2, 1, -4, -2, 2, -3);
        b.put(-3, 2, -3, "glass"); b.fill(-3, 4, -4, -1, 4, -2, "roof"); b.put(-2, 3, -4, "blue");
        b.fill(1, 0, -4, 3, 0, -2, "blue");
    }
    private static void droneLab(Layout b) {
        // Low white laboratory meets a taller glazed precision assembly wing.
        b.shell(-6, -5, 6, 5, 4, "concrete", "concrete"); b.open(-1, 1, -5, 1, 2, -5);
        b.fill(-6, 4, -5, 6, 4, -5, "blue"); b.fill(-6, 5, -5, 0, 5, 5, "roof");
        b.fill(1, 5, -5, 6, 7, 5, "concrete"); b.open(2, 5, -4, 5, 6, 4);
        b.fill(1, 5, -4, 1, 6, 4, "glass"); b.fill(6, 5, -4, 6, 6, 4, "glass");
        b.fill(1, 7, -5, 6, 7, 5, "roof"); b.fill(2, 7, -3, 5, 7, 3, "glass");
        for (int z : new int[]{0, 3}) { b.fill(-5, 1, z, -3, 1, z, "steel"); b.put(-4, 2, z, "blue"); b.put(-4, 3, z, "light"); }
        b.fill(3, 1, 0, 4, 1, 3, "steel"); b.put(3, 2, 1, "iron"); b.put(4, 2, 2, "blue");
        b.fill(-5, 6, 1, -3, 6, 3, "steel"); b.put(-4, 7, 2, "copper"); b.put(-4, 8, 2, "insulator");
        b.fill(-1, 3, -6, 2, 3, -6, "roof"); b.put(-1, 3, -5, "light");
    }
    private static void vehicleHangar(Layout b) {
        // Wide open bay, deep service floor and overhead bridge crane.
        b.shell(-7, -6, 7, 6, 5, "steel", "concrete"); b.open(-3, 1, -6, 3, 4, -6);
        b.fill(-4, 5, -6, 4, 5, -6, "blue"); b.pitchedRoof(-7, 7, -6, 6, 6, 2, "steel");
        for (int z : new int[]{-3, 1, 5}) {
            b.fill(-7, 1, z, -7, 5, z, "steel"); b.fill(7, 1, z, 7, 5, z, "steel"); b.fill(-6, 5, z, 6, 5, z, "steel");
        }
        b.fill(-5, 0, -4, -4, 0, 4, "blue"); b.fill(4, 0, -4, 5, 0, 4, "blue");
        b.fill(-5, 1, 1, -5, 2, 4, "steel"); b.fill(5, 1, 1, 5, 2, 4, "steel");
        b.fill(-5, 3, 4, 5, 3, 4, "red"); b.put(0, 2, 4, "copper");
        b.fill(-6, 1, 5, -3, 1, 5, "iron"); b.fill(3, 1, 5, 6, 1, 5, "iron");
        for (int x : new int[]{-6, 6}) for (int z : new int[]{-4, 0, 4}) b.put(x, 4, z, "light");
        b.fill(-4, 0, -8, -4, 0, -7, "red"); b.fill(4, 0, -8, 4, 0, -7, "red");
        b.fill(-1, 9, 1, 1, 9, 3, "steel");
    }
    private static void armsWorkshop(Layout b) {
        // Brick pilasters, tall windows, dark pitched roof and twin brick chimney stacks.
        b.shell(-6, -5, 6, 5, 4, "brick", "brick"); b.open(-1, 1, -5, 1, 2, -5);
        b.fill(-6, 1, -5, 6, 1, -5, "brick"); b.open(-1, 1, -5, 1, 1, -5);
        b.pitchedRoof(-6, 6, -6, 6, 5, 2, "brick");
        for (int z : new int[]{-4, 0, 4}) for (int x : new int[]{-6, 6}) b.fill(x, 1, z, x, 4, z, "brick");
        for (int x : new int[]{-4, 4}) {
            b.fill(x - 1, 1, 2, x + 1, 1, 3, "steel"); b.put(x, 2, 3, "iron"); b.put(x, 3, 3, "light");
        }
        b.fill(-5, 1, -1, -3, 1, -1, "iron"); b.fill(3, 1, -1, 5, 1, -1, "iron");
        for (int x : new int[]{-4, 4}) { b.fill(x, 1, 5, x, 9, 5, "brick"); b.fill(x - 1, 9, 4, x + 1, 9, 6, "brick"); b.put(x, 10, 5, "roof"); }
        b.fill(-1, 3, -6, 2, 3, -6, "roof"); b.put(0, 3, -5, "light");
    }
    private static void missileHall(Layout b) {
        // Tall ribbed assembly hall, roof lantern, overhead gantry and rear test fixtures.
        b.shell(-6, -7, 6, 7, 7, "concrete", "steel"); b.open(-2, 1, -7, 2, 4, -7);
        b.fill(-3, 5, -7, 3, 5, -7, "red"); b.pitchedRoof(-6, 6, -7, 7, 8, 3, "steel");
        for (int z : new int[]{-6, -2, 2, 6}) {
            b.fill(-6, 1, z, -6, 7, z, "steel"); b.fill(6, 1, z, 6, 7, z, "steel"); b.fill(-5, 7, z, 5, 7, z, "steel");
        }
        b.fill(-1, 11, -4, 1, 11, 4, "glass"); b.fill(-1, 12, -4, 1, 12, 4, "roof");
        for (int z : new int[]{-2, 3}) {
            b.fill(-4, 1, z, -2, 1, z + 1, "steel"); b.put(-3, 2, z, "concrete"); b.put(-3, 3, z, "red"); b.put(-3, 4, z, "steel");
            b.fill(3, 1, z, 4, 1, z + 1, "iron"); b.put(4, 2, z, "blue");
        }
        b.fill(-4, 6, 5, 4, 6, 5, "red"); b.fill(0, 4, 5, 0, 5, 5, "copper");
        for (int z : new int[]{-4, 0, 4}) for (int x : new int[]{-5, 5}) b.put(x, 6, z, "light");
        b.fill(-3, 0, -9, -3, 0, -8, "red"); b.fill(3, 0, -9, 3, 0, -8, "red");
    }
    private static Map<BlockPos, BlockState> legacy(SiteType type, BlockPos origin) {
        // Frozen 0.2 map, including the exact original controller, roof light and machine states.
        Map<BlockPos, BlockState> result = new LinkedHashMap<>(); int r = radius(type, LEGACY_VERSION);
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            result.put(origin.offset(x, 0, z), IndustryContent.structure("floor").defaultBlockState());
            result.put(origin.offset(x, 4, z), IndustryContent.structure("brick").defaultBlockState());
            if (Math.abs(x) != r && Math.abs(z) != r) continue;
            for (int y = 1; y <= 3; y++) {
                if (x == 0 && z == -r && y <= 2) continue;
                boolean corner = Math.abs(x) == r && Math.abs(z) == r;
                result.put(origin.offset(x, y, z), IndustryContent.structure(corner || y == 3 ? "brick" : "glass").defaultBlockState());
            }
        }
        result.put(controller(type, origin, LEGACY_VERSION), IndustryContent.controller(type).defaultBlockState());
        if (type == SiteType.POWER_STATION) {
            result.put(origin.offset(-1, 1, 1), IndustryContent.structure("iron").defaultBlockState()); result.put(origin.offset(1, 1, 1), IndustryContent.structure("iron").defaultBlockState());
        } else if (type == SiteType.SUBSTATION) result.put(origin.offset(0, 1, 0), IndustryContent.structure("copper").defaultBlockState());
        else result.put(origin.offset(-1, 1, 1), IndustryContent.structure("iron").defaultBlockState());
        result.put(origin.offset(0, 4, 0), IndustryContent.structure("light").defaultBlockState()); return result;
    }
    private static final class Layout {
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        void put(int x, int y, int z, String material) { put(x, y, z, IndustryContent.structure(material).defaultBlockState()); }
        void put(int x, int y, int z, BlockState state) { blocks.put(new BlockPos(x, y, z), state); }
        void fill(int x1, int y1, int z1, int x2, int y2, int z2, String material) {
            for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) put(x, y, z, material);
        }
        void open(int x1, int y1, int z1, int x2, int y2, int z2) {
            for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) blocks.remove(new BlockPos(x, y, z));
        }
        void ring(int x1, int y, int z1, int x2, int z2, String material) {
            fill(x1, y, z1, x2, y, z1, material); fill(x1, y, z2, x2, y, z2, material); fill(x1, y, z1, x1, y, z2, material); fill(x2, y, z1, x2, y, z2, material);
        }
        void shell(int x1, int z1, int x2, int z2, int height, String wall, String pier) {
            for (int y = 1; y <= height; y++) for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
                if (x != x1 && x != x2 && z != z1 && z != z2) continue;
                boolean pillar = (x == x1 || x == x2) && (z == z1 || z == z2)
                    || (z == z1 || z == z2) && (x - x1) % 4 == 0 || (x == x1 || x == x2) && (z - z1) % 4 == 0;
                put(x, y, z, pillar ? pier : y >= 2 && y < height ? "glass" : wall);
            }
        }
        void pitchedRoof(int x1, int x2, int z1, int z2, int eaves, int rise, String gable) {
            int half = (x2 - x1) / 2;
            for (int x = x1; x <= x2; x++) {
                int inset = Math.min(x - x1, x2 - x), y = eaves + Math.min(rise, inset / 2);
                boolean slope = inset < rise * 2 && inset % 2 == 1;
                BlockState state = IndustryContent.structure(slope ? "roof_stairs" : "roof").defaultBlockState();
                if (slope) state = state.setValue(StairBlock.FACING, x - x1 <= half ? Direction.EAST : Direction.WEST);
                for (int z = z1; z <= z2; z++) put(x, y, z, state);
                for (int gy = eaves; gy < y; gy++) { put(x, gy, z1, gable); put(x, gy, z2, gable); }
            }
        }
        void cylinder(int cx, int cz, int radius, int bottom, int top, String material, boolean hollow) {
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
                int square = x * x + z * z;
                if (square > radius * radius + 1 || hollow && square < (radius - 1) * (radius - 1) + 1) continue;
                fill(cx + x, bottom, cz + z, cx + x, top, cz + z, material);
            }
        }
        void chimney(int x, int z, int bottom, int top) {
            for (int y = bottom; y <= top; y++) cylinder(x, z, 2, y, y,
                y == top ? "steel" : (y >= 14 && y <= 16 || y >= 21 && y <= 23) ? "red" : "concrete", true);
        }
    }
}
