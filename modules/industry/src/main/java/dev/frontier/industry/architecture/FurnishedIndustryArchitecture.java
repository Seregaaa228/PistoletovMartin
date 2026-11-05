package dev.frontier.industry.architecture;

import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.SiteType;
import dev.frontier.industry.buildings.FunctionalBuildings;
import dev.frontier.industry.buildings.ProcessEquipment;
import dev.frontier.industry.buildings.WorkshopProp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.registries.ForgeRegistries;

/** V14: native furniture, grouped inspection offices and an unobstructed planted terrace. */
public final class FurnishedIndustryArchitecture {
    private static final Map<SiteType, Map<BlockPos, BlockState>> CACHE = new EnumMap<>(SiteType.class);

    private FurnishedIndustryArchitecture() {}

    public static Map<BlockPos, BlockState> relative(SiteType type) {
        return CACHE.computeIfAbsent(type, FurnishedIndustryArchitecture::design);
    }

    private static Map<BlockPos, BlockState> design(SiteType type) {
        Builder b = new Builder(type);
        if (type.isFactory()) {
            clearScatteredFactoryDecor(b);
            inspectionOffice(b);
        } else if (type == SiteType.RESEARCH_CENTER) {
            research(b);
        }
        return b.finish();
    }

    private static void clearScatteredFactoryDecor(Builder b) {
        var f = ReworkedIndustryArchitecture.baseBounds(b.type);
        // Plants belong to the entrance courts and office windows, never to assembly aisles.
        for (var e : new ArrayList<>(b.map.entrySet())) {
            BlockPos p = e.getKey();
            boolean inside = Math.abs(p.getX()) < f.maxX() - 2
                && p.getZ() > f.minZ() + 3 && p.getZ() < f.maxZ() - 3;
            if (inside && p.getY() >= 1 && p.getY() <= 4 && isPlanter(e.getValue())) b.remove(p);
            if (inside && e.getValue().getBlock() instanceof WorkshopProp prop
                && prop.kind.equals("courtyard_bench")) b.remove(p);
        }
        // The former isolated tabletop, distant stool and tool picture are replaced by one room.
        for (int side : new int[]{-1, 1}) {
            int x = side * (f.maxX() - 4);
            b.remove(new BlockPos(x, 1, 2));
            b.remove(new BlockPos(x, 2, 2));
            b.remove(new BlockPos(x, 1, 7));
            b.remove(new BlockPos(side * (f.maxX() - 3), 2, 4));
        }
    }

    private static void inspectionOffice(Builder b) {
        int z = ReworkedIndustryArchitecture.baseBounds(b.type).maxZ();
        // A compact room on the existing side apron, with a complete supported envelope.
        b.fill(15, 0, z, 24, 0, z + 11, Blocks.SMOOTH_STONE);
        b.open(16, 1, z, 24, 5, z + 11);
        for (int x = 17; x <= 23; x++) for (int zz = z + 1; zz <= z + 10; zz++) {
            if (x != 17 && x != 23 && zz != z + 1 && zz != z + 10) continue;
            boolean corner = (x == 17 || x == 23) && (zz == z + 1 || zz == z + 10);
            for (int y = 1; y <= 4; y++) {
                Block wall = corner ? Blocks.POLISHED_DEEPSLATE
                    : y == 4 ? Blocks.CYAN_CONCRETE
                    : y >= 2 ? Blocks.LIGHT_BLUE_STAINED_GLASS : Blocks.WHITE_CONCRETE;
                b.put(x, y, zz, wall.defaultBlockState());
            }
        }
        b.fill(16, 5, z, 24, 5, z + 11, Blocks.DEEPSLATE_TILES);
        b.fill(19, 5, z + 4, 21, 5, z + 6, Blocks.GLASS);
        // The three-wide warehouse opening leads into an enclosed vestibule and an actual door.
        b.open(15, 1, z + 4, 16, 3, z + 6);
        for (int zz : new int[]{z + 3, z + 7}) for (int x = 15; x <= 17; x++) {
            b.put(x, 1, zz, Blocks.WHITE_CONCRETE.defaultBlockState());
            b.put(x, 2, zz, Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState());
            b.put(x, 3, zz, Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState());
        }
        b.fill(15, 4, z + 3, 17, 4, z + 7, Blocks.SMOOTH_STONE);
        BlockState door = Blocks.SPRUCE_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, Direction.WEST);
        b.put(17, 1, z + 5, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        b.put(17, 2, z + 5, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        b.put(16, 0, z + 5, Blocks.SEA_LANTERN.defaultBlockState());
        b.put(20, 0, z + 5, Blocks.SEA_LANTERN.defaultBlockState());
        for (int zz : new int[]{z + 3, z + 8}) b.put(20, 4, zz, Blocks.SEA_LANTERN.defaultBlockState());

        // Receiving/inspection tools rest directly on a full-height steel work surface.
        b.ed(18, 1, z + 2, "steel_table");
        b.ed(19, 1, z + 2, "steel_table");
        b.part(18, 2, z + 2, "campus_service_tools", Direction.SOUTH);
        b.part(19, 2, z + 2, b.type == SiteType.DRONE_FACTORY ? "campus_electronics"
            : b.type == SiteType.MISSILE_FACTORY ? "lab_analyser" : "campus_blueprint", Direction.SOUTH);
        b.af(20, 1, z + 2, "dark_oak_drawer", "facing", "south", "open", "false");
        b.af(22, 1, z + 2, "dark_oak_drawer", "facing", "south", "open", "false");
        b.put(22, 2, z + 2, Blocks.POTTED_FERN.defaultBlockState());

        // A desk and a separate four-seat briefing table, leaving the entrance/centre aisle free.
        b.table(21, 1, z + 3);
        b.table(22, 1, z + 3);
        b.chair(21, 1, z + 4, Direction.NORTH);
        b.af(22, 2, z + 3, "gray_lamp", "facing", "up", "base", "true", "lit", "true");
        for (int x = 20; x <= 21; x++) for (int zz = z + 7; zz <= z + 8; zz++) b.table(x, 1, zz);
        b.chair(19, 1, z + 7, Direction.EAST);
        b.chair(22, 1, z + 8, Direction.WEST);
        b.chair(20, 1, z + 6, Direction.SOUTH);
        b.chair(21, 1, z + 9, Direction.NORTH);
    }

    private static void research(Builder b) {
        // Clear only interior/terrace dressing; the planted entrance courts remain intact.
        for (var e : new ArrayList<>(b.map.entrySet())) {
            BlockPos p = e.getKey();
            boolean interior = p.getY() >= 1 && p.getY() <= 4 && Math.abs(p.getX()) < 17
                && p.getZ() > -14 && p.getZ() < 14;
            boolean upperInterior = p.getY() >= 8 && p.getY() <= 12 && p.getX() > -16
                && p.getX() < 3 && p.getZ() > -6 && p.getZ() < 13;
            boolean terrace = p.getX() >= 4 && p.getX() <= 16 && p.getY() >= 8
                && p.getY() <= 10 && p.getZ() >= -13 && p.getZ() <= 13;
            if ((interior || upperInterior) && isPlanter(e.getValue())) b.remove(p);
            if (terrace && (isPlanter(e.getValue()) || e.getValue().getBlock() instanceof LeavesBlock
                || e.getValue().getBlock() instanceof WorkshopProp prop && Set.of("coffee_table", "lounge_chair",
                    "courtyard_bench", "parts_bin", "operator_console").contains(prop.kind)
                || e.getValue().getBlock() instanceof ProcessEquipment part
                    && Set.of("campus_blueprint", "campus_specimen").contains(part.kind))) b.remove(p);
        }
        // Visitor seating is grouped on both sides of reception, clear of the saved reception jobs.
        for (int start : new int[]{-13, 11}) {
            for (int x = start; x < start + 3; x++) b.af(x, 1, -12, "cyan_sofa", "facing", "south", "type", "single");
            b.table(start + 1, 1, -10);
            int side = start < 0 ? -15 : 15;
            b.af(side, 1, -10, "dark_oak_drawer", "facing", "south", "open", "false");
            b.af(side, 2, -10, "gray_lamp", "facing", "up", "base", "true", "lit", "true");
            b.put(side, 1, -12, Blocks.POTTED_FERN.defaultBlockState());
        }

        // Dining group: a connected normal-height table with four functional chairs.
        for (int x = 8; x <= 9; x++) for (int z = -1; z <= 0; z++) b.table(x, 8, z);
        b.chair(7, 8, -1, Direction.EAST);
        b.chair(10, 8, 0, Direction.WEST);
        b.chair(8, 8, -2, Direction.SOUTH);
        b.chair(9, 8, 1, Direction.NORTH);
        // Lounge group faces the central terrace, away from the retained rear HVAC platform.
        for (int z = 8; z <= 10; z++) b.af(15, 8, z, "gray_sofa", "facing", "west", "type", "single");
        b.table(12, 8, 9);
        b.table(13, 8, 9);
        b.chair(11, 8, 9, Direction.EAST);
        b.af(15, 8, 7, "dark_oak_drawer", "facing", "west", "open", "false");
        b.af(15, 9, 7, "gray_lamp", "facing", "up", "base", "true", "lit", "true");
        b.af(6, 8, 0, "dark_oak_drawer", "facing", "east", "open", "false");
        b.put(6, 9, 0, Blocks.POTTED_FERN.defaultBlockState());
        // Low, flowering borders replace the opaque leaf wall and isolated planter repetition.
        for (int z = -1; z <= 3; z++) {
            b.put(16, 7, z, Blocks.ROOTED_DIRT.defaultBlockState());
            b.put(16, 8, z, (z % 2 == 0 ? Blocks.FLOWERING_AZALEA : Blocks.AZALEA).defaultBlockState());
        }
        for (int x = 6; x <= 9; x++) {
            b.put(x, 7, 13, Blocks.ROOTED_DIRT.defaultBlockState());
            b.put(x, 8, 13, (x % 2 == 0 ? Blocks.FLOWERING_AZALEA : Blocks.AZALEA).defaultBlockState());
        }
        // The three-wide terrace door and its approach are always kept clear.
        b.open(4, 8, 4, 6, 10, 6);
    }

    private static boolean isPlanter(BlockState state) {
        return state.getBlock() instanceof ProcessEquipment part && part.kind.equals("campus_green_planter")
            || state.getBlock() instanceof WorkshopProp prop && prop.kind.equals("planter")
            || state.is(Blocks.FLOWER_POT);
    }

    private static BlockState required(String namespace, String path) {
        ResourceLocation id = new ResourceLocation(namespace, path);
        Block block = ForgeRegistries.BLOCKS.getValue(id);
        if (block == null || block == Blocks.AIR) throw new IllegalStateException("Required furniture block missing: " + id);
        return block.defaultBlockState();
    }

    private static BlockState with(BlockState state, String name, String value) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
        if (property == null) throw new IllegalStateException("Missing furniture property " + name + " in " + state);
        return value(state, property, value);
    }

    private static <T extends Comparable<T>> BlockState value(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value)
            .orElseThrow(() -> new IllegalStateException("Invalid furniture state " + property.getName() + "=" + value)));
    }

    private static boolean nativeFurniture(BlockState state, String suffix) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && id.getNamespace().equals("another_furniture") && id.getPath().endsWith(suffix);
    }

    private static final class Builder {
        final SiteType type;
        final Map<BlockPos, BlockState> original;
        final Map<BlockPos, BlockState> map;
        final Set<BlockPos> protectedCells = new HashSet<>();

        Builder(SiteType type) {
            this.type = type;
            original = GardenIndustryArchitecture.relative(type);
            map = new LinkedHashMap<>(original);
            // Restore every saved worker envelope, including absent cells, rather than guessing new jobs.
            for (var job : FinalIndustryArchitecture.workplaces(type, 13)) {
                protectedCells.add(job.equipment());
                for (int dy = 0; dy <= 2; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                    protectedCells.add(job.stand().offset(dx, dy, dz));
            }
            for (var e : original.entrySet()) {
                BlockState s = e.getValue();
                if (s.is(FunctionalBuildings.BELT.get()) || s.is(FunctionalBuildings.LINE_CONTROLLER.get())
                    || s.is(FunctionalBuildings.WAREHOUSE.get()) || s.is(FunctionalBuildings.CONTAINER.get())
                    || s.is(IndustryContent.controller(type)) || s.is(FunctionalBuildings.GUN_RIFLE_DISPLAY.get())
                    || s.is(FunctionalBuildings.GUN_RPG_DISPLAY.get()) || s.is(FunctionalBuildings.GUN_PISTOL_DISPLAY.get()))
                    protectedCells.add(e.getKey());
            }
        }

        void put(int x, int y, int z, BlockState state) {
            BlockPos p = new BlockPos(x, y, z);
            if (!protectedCells.contains(p)) map.put(p, state);
        }

        void remove(BlockPos p) {
            if (!protectedCells.contains(p)) map.remove(p);
        }

        void fill(int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
            for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++)
                put(x, y, z, block.defaultBlockState());
        }

        void open(int x1, int y1, int z1, int x2, int y2, int z2) {
            for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++)
                remove(new BlockPos(x, y, z));
        }

        void af(int x, int y, int z, String id, String... properties) {
            BlockState state = required("another_furniture", id);
            for (int i = 0; i < properties.length; i += 2) state = with(state, properties[i], properties[i + 1]);
            put(x, y, z, state);
        }

        void table(int x, int y, int z) { af(x, y, z, "dark_oak_table"); }

        void chair(int x, int y, int z, Direction direction) {
            af(x, y, z, "dark_oak_chair", "facing", direction.getName(), "tucked", "false", "variant", "1");
        }

        void ed(int x, int y, int z, String id) { put(x, y, z, required("engineersdecor", id)); }

        void part(int x, int y, int z, String id, Direction direction) {
            BlockState state = FunctionalBuildings.EQUIPMENT.get(id).get().defaultBlockState();
            if (state.hasProperty(BlockStateProperties.FACING)) state = state.setValue(BlockStateProperties.FACING, direction);
            put(x, y, z, state);
        }

        private boolean furnitureAt(BlockPos p, String suffix) {
            BlockState state = map.get(p);
            return state != null && nativeFurniture(state, suffix);
        }

        private void connectFurniture() {
            // Resolve native geometry in blueprint space before rotation, avoiding internal table legs.
            for (var e : new ArrayList<>(map.entrySet())) {
                BlockPos p = e.getKey();
                BlockState s = e.getValue();
                if (protectedCells.contains(p)) continue;
                if (nativeFurniture(s, "_table")) {
                    boolean n = furnitureAt(p.north(), "_table"), east = furnitureAt(p.east(), "_table");
                    boolean south = furnitureAt(p.south(), "_table"), w = furnitureAt(p.west(), "_table");
                    s = with(s, "leg_1", Boolean.toString(!n && !east || n && east && !furnitureAt(p.north().east(), "_table")));
                    s = with(s, "leg_2", Boolean.toString(!east && !south || east && south && !furnitureAt(p.south().east(), "_table")));
                    s = with(s, "leg_3", Boolean.toString(!south && !w || south && w && !furnitureAt(p.south().west(), "_table")));
                    s = with(s, "leg_4", Boolean.toString(!n && !w || n && w && !furnitureAt(p.north().west(), "_table")));
                    s = with(s, "update", Boolean.toString(((n ? 1 : 0) + (east ? 1 : 0) + (south ? 1 : 0) + (w ? 1 : 0)) % 2 == 0));
                    map.put(p, s);
                } else if (nativeFurniture(s, "_sofa")) {
                    Direction facing = s.getValue(BlockStateProperties.HORIZONTAL_FACING);
                    boolean left = furnitureAt(p.relative(facing.getCounterClockWise()), "_sofa");
                    boolean right = furnitureAt(p.relative(facing.getClockWise()), "_sofa");
                    map.put(p, with(s, "type", left && right ? "middle" : left ? "left" : right ? "right" : "single"));
                }
            }
        }

        Map<BlockPos, BlockState> finish() {
            connectFurniture();
            // Exact restoration also protects intentionally empty headroom and native equipment collisions.
            for (BlockPos p : protectedCells) {
                if (original.containsKey(p)) map.put(p, original.get(p));
                else map.remove(p);
            }
            if (protectedCells.stream().anyMatch(p -> !Objects.equals(original.get(p), map.get(p))))
                throw new IllegalStateException("Furniture changed a saved worker/mechanical cell: " + type);
            var bounds = CampusIndustryArchitecture.baseBounds(type);
            if (map.keySet().stream().anyMatch(p -> !bounds.contains(p.getX(), p.getY(), p.getZ())))
                throw new IllegalStateException("Furniture outside campus blueprint: " + type);
            return Collections.unmodifiableMap(map);
        }
    }
}
