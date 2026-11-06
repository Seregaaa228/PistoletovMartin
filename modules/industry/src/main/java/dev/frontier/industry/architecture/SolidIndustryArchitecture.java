package dev.frontier.industry.architecture;

import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.SiteType;
import dev.frontier.industry.engine.Blueprint;
import dev.frontier.industry.engine.BlueprintBounds;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** V4: continuous full-block envelopes; the original equipment meshes remain equipment. */
public final class SolidIndustryArchitecture {
    private SolidIndustryArchitecture() {}

    public static BlueprintBounds bounds(SiteType type) {
        return switch (type) {
            case POWER_STATION -> new BlueprintBounds(-15, 0, -13, 15, 24, 13);
            case SUBSTATION -> new BlueprintBounds(-8, 0, -8, 8, 9, 8);
            case DRONE_FACTORY -> new BlueprintBounds(-10, 0, -10, 10, 12, 10);
            case VEHICLE_FACTORY -> new BlueprintBounds(-13, 0, -12, 13, 13, 12);
            case EQUIPMENT_FACTORY -> new BlueprintBounds(-11, 0, -10, 11, 12, 10);
            case MISSILE_FACTORY -> new BlueprintBounds(-11, 0, -14, 11, 16, 14);
            case RESEARCH_CENTER -> new BlueprintBounds(-10, 0, -10, 10, 13, 10);
        };
    }

    public static Map<BlockPos, BlockState> blocks(SiteType type, BlockPos origin) {
        Layout b = new Layout();
        BlueprintBounds f = bounds(type);
        b.fill(f.minX(), 0, f.minZ(), f.maxX(), 0, f.maxZ(), Blocks.SMOOTH_STONE);
        b.ring(f.minX(), 0, f.minZ(), f.maxX(), f.maxZ(), Blocks.POLISHED_DEEPSLATE);
        switch (type) {
            case POWER_STATION -> power(b);
            case SUBSTATION -> substation(b);
            case DRONE_FACTORY -> drone(b);
            case VEHICLE_FACTORY -> vehicle(b);
            case EQUIPMENT_FACTORY -> equipment(b);
            case MISSILE_FACTORY -> missile(b);
            case RESEARCH_CENTER -> research(b);
        }
        BlockPos controller = Blueprint.controller(type, BlockPos.ZERO, 4);
        b.put(controller.getX(), controller.getY(), controller.getZ(), IndustryContent.controller(type).defaultBlockState());
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        b.map.forEach((p, state) -> result.put(origin.offset(p), state));
        return result;
    }

    public static Map<BlockPos, BlockState> criticalBlocks(SiteType type, BlockPos origin) {
        if (type != SiteType.POWER_STATION && type != SiteType.SUBSTATION) return Map.of();
        Map<BlockPos, BlockState> layout = blocks(type, origin);
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        for (int x : type == SiteType.POWER_STATION ? new int[]{-6, -2} : new int[]{-2, 2}) {
            BlockPos p = origin.offset(x, 2, type == SiteType.POWER_STATION ? -1 : 0);
            result.put(p, layout.get(p));
        }
        return result;
    }

    private static void power(Layout b) {
        b.shell(-13, -10, 3, 3, 7, false);
        b.door(-2, 2, -10, 4);
        b.pitchedRoof(-13, -10, 3, 3, 8, 2, false);
        b.shell(-13, 4, 3, 11, 9, false);
        // Join the two volumes through a sealed service wall, leaving a central passage.
        b.open(-2, 1, 3, 1, 4, 4);
        b.flatRoof(-13, 4, 3, 11, 10);
        for (int x : new int[]{-6, 0}) {
            for (int y = 11; y < 24; y++) b.detail(x, y, 7, (y >= 14 && y <= 16 || y >= 21) ? "chimney_red" : "chimney_white");
            b.detail(x, 24, 7, "chimney_cap");
        }
        for (int x : new int[]{-6, -2}) {
            b.detail(x, 2, -1, "turbine");
            for (int z = 0; z <= 2; z++) b.detail(x, 4, z, "pipe");
            b.detail(x, 4, 2, "pipe_elbow");
            for (int y = 5; y <= 7; y++) b.detail(x, y, 2, "pipe_riser");
        }
        for (int z : new int[]{-4, 5}) {
            b.fill(6, 1, z - 1, 8, 1, z + 1, Blocks.POLISHED_DEEPSLATE);
            for (int y = 2; y <= 6; y++) b.detail(7, y, z, "silo");
            b.detail(7, 7, z, "silo_cap");
        }
        for (int z = -7; z <= 7; z++) b.detail(11, 4, z, "pipe");
        for (int z : new int[]{-8, 8}) b.fill(11, 1, z, 11, 4, z, Blocks.POLISHED_DEEPSLATE);
        b.detail(-8, 5, -11, "sign_power");
        b.canopy(-3, 2, -11, 5);
        for (int z : new int[]{-7, 0}) for (int x : new int[]{-11, 1}) b.detail(x, 6, z, "lamp");
        b.detail(-11, 11, 7, "vent");
    }

    private static void substation(Layout b) {
        // Keep both electrical cores at the saved circuit's fixed local positions.
        for (int x : new int[]{-2, 2}) {
            b.fill(x - 1, 1, -1, x + 1, 1, 1, Blocks.POLISHED_DEEPSLATE);
            b.detail(x, 2, 0, "transformer");
        }
        for (int x : new int[]{-6, 6}) {
            b.fill(x, 1, 3, x, 5, 3, Blocks.POLISHED_DEEPSLATE);
            b.detail(x, 6, 3, "insulator");
        }
        for (int x = -6; x <= 6; x++) b.detail(x, 5, 3, "beam", Direction.EAST);
        for (int x = -5; x <= 5; x++) b.detail(x, 6, 3, "pipe", Direction.EAST);
        // A proper brick control house behind the transformer yard.
        b.shell(-4, 4, 4, 7, 4, false);
        b.door(-1, 1, 4, 2);
        b.pitchedRoof(-4, 4, 4, 7, 5, 1, false);
        b.detail(-2, 1, 6, "server");
        b.detail(2, 1, 6, "machine");
        b.detail(0, 3, 5, "lamp");
        for (int z = -6; z <= 7; z++) for (int x : new int[]{-8, 8}) b.detail(x, 1, z, "rail");
        for (int x = -8; x <= 8; x++) b.detail(x, 1, 8, "rail", Direction.EAST);
        for (int x = -8; x <= 8; x++) if (Math.abs(x) > 1) b.detail(x, 1, -7, "rail", Direction.EAST);
    }

    private static void equipment(Layout b) {
        b.shell(-9, -7, 9, 8, 7, false);
        b.door(-2, 2, -7, 4);
        b.pitchedRoof(-9, -7, 9, 8, 8, 3, true);
        b.canopy(-3, 3, -8, 5);
        b.detail(4, 5, -8, "sign_factory");
        for (int x : new int[]{-6, 6}) {
            b.detail(x, 1, 0, "machine");
            b.detail(x, 1, 4, "press");
            b.detail(x, 1, 6, "rack");
            b.fill(x, 9, 6, x, 11, 6, Blocks.BRICKS);
            b.put(x, 12, 6, Blocks.POLISHED_DEEPSLATE);
            for (int z : new int[]{-4, 3}) b.detail(x, 6, z, "lamp");
        }
        for (int z = 0; z <= 5; z++) b.detail(-2, 1, z, "conveyor");
        b.lane(-1, 1, -6, 6);
    }

    private static void vehicle(Layout b) {
        b.shell(-11, -9, 11, 10, 8, false);
        b.door(-4, 4, -9, 5);
        b.pitchedRoof(-11, -9, 11, 10, 9, 3, true);
        b.detail(0, 6, -10, "sign_factory");
        for (int x : new int[]{-8, 8}) {
            for (int z : new int[]{-5, 7}) b.fill(x, 1, z, x, 6, z, Blocks.POLISHED_DEEPSLATE);
            for (int z = -5; z <= 7; z++) b.detail(x, 7, z, "crane");
            b.detail(x, 1, 4, x < 0 ? "press" : "machine");
            b.detail(x, 1, -3, "rack");
            for (int z : new int[]{-5, 1, 7}) b.detail(x, 6, z, "lamp");
        }
        for (int x = -8; x <= 8; x++) b.detail(x, 7, 4, "crane", Direction.EAST);
        for (int y = 5; y <= 6; y++) b.detail(0, y, 4, "hook");
        b.lane(-5, 5, -8, 8);
        b.detail(8, 12, 7, "vent");
    }

    private static void missile(Layout b) {
        b.shell(-9, -11, 9, 12, 10, false);
        b.door(-3, 3, -11, 5);
        b.pitchedRoof(-9, -11, 9, 12, 11, 3, true);
        b.detail(0, 6, -12, "sign_factory");
        for (int z : new int[]{-5, 2, 8}) {
            b.detail(-5, 1, z, "missile_fixture");
            b.detail(6, 1, z, "machine");
            for (int x : new int[]{-7, 7}) b.detail(x, 8, z, "lamp");
        }
        for (int z = 0; z <= 9; z++) b.detail(2, 1, z, "conveyor");
        for (int x = -7; x <= 7; x++) b.detail(x, 8, 7, "crane", Direction.EAST);
        b.detail(0, 6, 7, "hook");
        b.lane(-2, 2, -10, 10);
        b.detail(6, 14, 9, "vent");
    }

    private static void drone(Layout b) {
        b.shell(-8, -7, 8, 8, 6, true);
        b.door(-2, 2, -7, 3);
        b.pitchedRoof(-8, -7, 8, 8, 7, 2, true);
        b.canopy(-3, 3, -8, 4);
        b.detail(4, 4, -8, "sign_factory");
        for (int x : new int[]{-5, 5}) for (int z : new int[]{0, 5}) b.detail(x, 1, z, "drone_bench");
        for (int x : new int[]{-5, 5}) b.detail(x, 1, 7, "server");
        for (int z : new int[]{-4, 3}) for (int x : new int[]{-6, 6}) b.detail(x, 5, z, "lamp");
        b.detail(5, 9, 5, "vent");
        b.detail(5, 9, 6, "duct");
        b.lane(-2, 2, -6, 7);
    }

    private static void research(Layout b) {
        b.shell(-8, -7, 8, 8, 5, true);
        b.door(-2, 2, -7, 3);
        b.flatRoof(-8, -7, 8, 8, 6);
        b.canopy(-3, 3, -8, 4);
        b.detail(4, 4, -8, "sign_research");
        // Upper block rests on the continuous first-storey roof instead of floating.
        b.shellAt(-5, 0, 5, 6, 7, 10, true);
        b.flatRoof(-5, 0, 5, 6, 11);
        b.shellAt(-7, 0, -5, 7, 7, 10, true);
        b.flatRoof(-7, 0, -5, 7, 11);
        // A solid stair and a matching opening make the second storey usable.
        b.open(-6, 6, 1, -6, 6, 6);
        b.open(-5, 7, 5, -5, 8, 6);
        for (int z = 0; z <= 5; z++) b.put(-6, 1 + z, z, Blocks.QUARTZ_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
        for (int x : new int[]{-4, 4}) for (int z : new int[]{0, 5}) b.detail(x, 1, z, "laboratory");
        for (int x : new int[]{-3, 0, 3}) b.detail(x, 7, 5, "server");
        for (int z : new int[]{-3, 3}) b.detail(0, 4, z, "lamp");
        b.detail(2, 12, 3, "vent");
        for (int y = 7; y <= 12; y++) b.detail(7, y, 6, "pipe_riser");
        b.detail(7, 13, 6, "insulator");
    }

    private static final class Layout {
        final Map<BlockPos, BlockState> map = new LinkedHashMap<>();
        void put(int x, int y, int z, Block block) { put(x, y, z, block.defaultBlockState()); }
        void put(int x, int y, int z, BlockState state) { map.put(new BlockPos(x, y, z), state); }
        void detail(int x, int y, int z, String part) { detail(x, y, z, part, Direction.NORTH); }
        void detail(int x, int y, int z, String part, Direction facing) {
            put(x, y, z, IndustryContent.detail(part).defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing));
        }
        void fill(int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
            for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) put(x, y, z, block);
        }
        void open(int x1, int y1, int z1, int x2, int y2, int z2) {
            for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) map.remove(new BlockPos(x, y, z));
        }
        void ring(int x1, int y, int z1, int x2, int z2, Block block) {
            fill(x1, y, z1, x2, y, z1, block); fill(x1, y, z2, x2, y, z2, block);
            fill(x1, y, z1, x1, y, z2, block); fill(x2, y, z1, x2, y, z2, block);
        }
        void shell(int x1, int z1, int x2, int z2, int h, boolean laboratory) { shellAt(x1, z1, x2, z2, 1, h, laboratory); }
        void shellAt(int x1, int z1, int x2, int z2, int bottom, int top, boolean laboratory) {
            for (int y = bottom; y <= top; y++) for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
                if (x != x1 && x != x2 && z != z1 && z != z2) continue;
                boolean pier = (x == x1 || x == x2) && (z == z1 || z == z2)
                    || (z == z1 || z == z2) && (x - x1) % 4 == 0
                    || (x == x1 || x == x2) && (z - z1) % 4 == 0;
                Block block = pier ? (laboratory ? Blocks.SMOOTH_QUARTZ : Blocks.POLISHED_DEEPSLATE)
                    : y == bottom || y == top ? (laboratory ? Blocks.SMOOTH_QUARTZ : Blocks.BRICKS)
                    : (laboratory ? Blocks.LIGHT_BLUE_STAINED_GLASS : Blocks.GLASS);
                put(x, y, z, block);
            }
        }
        void door(int x1, int x2, int z, int height) {
            open(x1, 1, z, x2, height, z);
            fill(x1 - 1, 1, z, x1 - 1, height + 1, z, Blocks.POLISHED_DEEPSLATE);
            fill(x2 + 1, 1, z, x2 + 1, height + 1, z, Blocks.POLISHED_DEEPSLATE);
            fill(x1, height + 1, z, x2, height + 1, z, Blocks.POLISHED_DEEPSLATE);
        }
        void flatRoof(int x1, int z1, int x2, int z2, int y) {
            fill(x1, y, z1, x2, y, z2, Blocks.SMOOTH_QUARTZ);
            ring(x1, y + 1, z1, x2, z2, Blocks.POLISHED_DEEPSLATE);
        }
        void pitchedRoof(int x1, int z1, int x2, int z2, int eaves, int rise, boolean skylights) {
            // Fill each step down to eaves: diagonal roof strips must never leave slots.
            for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
                int top = eaves + Math.min(rise, Math.min(x - x1, x2 - x) / 3);
                boolean glass = skylights && x > x1 + 2 && x < x2 - 2 && z > z1 + 1 && z < z2 - 1 && Math.floorMod(z - z1, 6) == 3;
                for (int y = eaves; y <= top; y++) put(x, y, z, glass ? Blocks.LIGHT_BLUE_STAINED_GLASS : Blocks.DEEPSLATE_TILES);
            }
            fill(x1 - 1, eaves, z1 - 1, x1 - 1, eaves, z2 + 1, Blocks.DEEPSLATE_TILES);
            fill(x2 + 1, eaves, z1 - 1, x2 + 1, eaves, z2 + 1, Blocks.DEEPSLATE_TILES);
            fill(x1, eaves, z1 - 1, x2, eaves, z1 - 1, Blocks.DEEPSLATE_TILES);
            fill(x1, eaves, z2 + 1, x2, eaves, z2 + 1, Blocks.DEEPSLATE_TILES);
        }
        void canopy(int x1, int x2, int z, int y) {
            fill(x1, y, z, x2, y, z, Blocks.DEEPSLATE_TILES);
            for (int x : new int[]{x1, x2}) fill(x, 1, z, x, y - 1, z, Blocks.POLISHED_DEEPSLATE);
        }
        void lane(int x1, int x2, int z1, int z2) {
            for (int x : new int[]{x1, x2}) for (int z = z1; z <= z2; z++) put(x, 0, z, z % 2 == 0 ? Blocks.YELLOW_CONCRETE : Blocks.BLACK_CONCRETE);
        }
    }
}
