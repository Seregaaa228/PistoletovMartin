package dev.frontier.industry.architecture;

import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.SiteType;
import dev.frontier.industry.buildings.FunctionalBuildings;
import dev.frontier.industry.buildings.ProcessEquipment;
import dev.frontier.industry.buildings.WorkshopProp;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

/** V15: coherent industrial styling using WarStuff II, with a rebuilt research centre interior. */
public final class V15DecorArchitecture {
    private static final Map<SiteType, Map<BlockPos, BlockState>> CACHE = new EnumMap<>(SiteType.class);
    private static final Set<String> RESEARCH_DECOR = Set.of(
        "another_furniture", "w2d", "engineersdecor", "frontier_industry");

    private V15DecorArchitecture() {}

    public static Map<BlockPos, BlockState> relative(SiteType type) {
        return CACHE.computeIfAbsent(type, V15DecorArchitecture::design);
    }

    private static Map<BlockPos, BlockState> design(SiteType type) {
        Map<BlockPos, BlockState> map = new LinkedHashMap<>(FurnishedIndustryArchitecture.relative(type));
        Set<BlockPos> protectedCells = protectedCells(type);
        if (type == SiteType.RESEARCH_CENTER) rebuildResearch(map, protectedCells);
        else industrialAccents(map, type, protectedCells);
        return Collections.unmodifiableMap(map);
    }

    private static Set<BlockPos> protectedCells(SiteType type) {
        Set<BlockPos> protectedCells = new HashSet<>();
        for (var job : FinalIndustryArchitecture.workplaces(type, 13)) {
            protectedCells.add(job.equipment());
            for (int dy = 0; dy <= 2; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                protectedCells.add(job.stand().offset(dx, dy, dz));
        }
        Map<BlockPos, BlockState> original = FurnishedIndustryArchitecture.relative(type);
        for (var entry : original.entrySet()) {
            BlockState state = entry.getValue();
            if (state.is(FunctionalBuildings.BELT.get()) || state.is(FunctionalBuildings.LINE_CONTROLLER.get())
                || state.is(FunctionalBuildings.WAREHOUSE.get()) || state.is(FunctionalBuildings.CONTAINER.get())
                || state.is(IndustryContent.controller(type)) || state.getBlock() instanceof ProcessEquipment
                || state.getBlock() instanceof WorkshopProp)
                protectedCells.add(entry.getKey());
        }
        return protectedCells;
    }

    private static void rebuildResearch(Map<BlockPos, BlockState> map, Set<BlockPos> protectedCells) {
        // Remove the old scattered furniture and foliage while retaining shell, floors, doors and workstations.
        for (var entry : new ArrayList<>(map.entrySet())) {
            BlockPos p = entry.getKey();
            if (protectedCells.contains(p)) continue;
            boolean lower = p.getY() >= 1 && p.getY() <= 6 && p.getX() >= -16 && p.getX() <= 16 && p.getZ() >= -13 && p.getZ() <= 13;
            boolean terrace = p.getY() >= 8 && p.getY() <= 11 && p.getX() >= 5 && p.getX() <= 17 && p.getZ() >= -5 && p.getZ() <= 14;
            if ((lower || terrace) && isDecor(entry.getValue())) map.remove(p);
        }

        // Ground floor: clean reception, specimen preparation and a technical sample-storage wall.
        add(map, protectedCells, "w2d:table", 8, 1, -10);
        add(map, protectedCells, "w2d:chair_1", 8, 1, -9);
        add(map, protectedCells, "w2d:chair_1", 9, 1, -9);
        add(map, protectedCells, "w2d:papers", 9, 2, -10);
        add(map, protectedCells, "w2d:radio", -14, 1, -10);
        add(map, protectedCells, "w2d:wooden_crate", -14, 1, -8);
        add(map, protectedCells, "w2d:canister", 15, 1, -8);
        add(map, protectedCells, "w2d:extinguisher", 15, 1, -6);
        for (int z : new int[]{-5, 3, 11}) add(map, protectedCells, "w2d:dcamoslab", 15, 1, z);
        for (int x : new int[]{-14, -11, 14}) add(map, protectedCells, "w2d:candelabrum", x, 1, 12);

        // Upper analytics floor: a deliberate meeting island and server-room detailing.
        add(map, protectedCells, "w2d:table_2", -12, 8, 9);
        add(map, protectedCells, "w2d:chair_2", -12, 8, 8);
        add(map, protectedCells, "w2d:chair_2", -11, 8, 9);
        add(map, protectedCells, "w2d:book", -13, 9, 9);
        add(map, protectedCells, "w2d:generator", 1, 8, 11);
        add(map, protectedCells, "w2d:canister", 2, 8, 11);
        add(map, protectedCells, "w2d:radio", 2, 8, 9);

        // Terrace: open walkway, two recognizable seating bays, crates and military-style panels.
        for (int z : new int[]{-3, 4, 11}) add(map, protectedCells, "w2d:dcamodoor", 16, 8, z);
        add(map, protectedCells, "w2d:bench", 8, 8, -2);
        add(map, protectedCells, "w2d:bench", 11, 8, -2);
        add(map, protectedCells, "w2d:table", 9, 8, 0);
        add(map, protectedCells, "w2d:chair_1", 8, 8, 1);
        add(map, protectedCells, "w2d:chair_1", 10, 8, 1);
        add(map, protectedCells, "w2d:wooden_crate", 13, 8, 12);
        add(map, protectedCells, "w2d:canister", 14, 8, 12);
        add(map, protectedCells, "w2d:candelabrum", 6, 8, 12);
    }

    private static void industrialAccents(Map<BlockPos, BlockState> map, SiteType type, Set<BlockPos> protectedCells) {
        var bounds = CampusIndustryArchitecture.baseBounds(type);
        int z = bounds.maxZ() - 2;
        for (int x : new int[]{bounds.minX() + 3, bounds.maxX() - 3}) {
            add(map, protectedCells, "w2d:wooden_crate", x, 1, z);
            add(map, protectedCells, "w2d:canister", x, 1, z + 1);
            add(map, protectedCells, "w2d:extinguisher", x, 1, z + 2);
        }
        if (type.isFactory()) {
            for (int x : new int[]{-20, 0, 20}) add(map, protectedCells, "w2d:dcamoslab", x, 1, bounds.minZ() + 2);
            add(map, protectedCells, "w2d:radio", 0, 2, bounds.maxZ() - 3);
            add(map, protectedCells, "w2d:candelabrum", bounds.minX() + 2, 1, bounds.minZ() + 2);
            add(map, protectedCells, "w2d:candelabrum", bounds.maxX() - 2, 1, bounds.minZ() + 2);
        }
    }

    private static boolean isDecor(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null) return false;
        if (RESEARCH_DECOR.contains(id.getNamespace())) return !state.is(Blocks.SMOOTH_QUARTZ) && !state.is(Blocks.QUARTZ_BLOCK);
        return state.getBlock() instanceof LeavesBlock || state.is(Blocks.FLOWER_POT);
    }

    private static void add(Map<BlockPos, BlockState> map, Set<BlockPos> protectedCells, String id, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (protectedCells.contains(pos) || map.containsKey(pos)) return;
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
        if (block != null && block != Blocks.AIR) map.put(pos, block.defaultBlockState());
    }
}
