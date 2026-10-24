package dev.frontier.industry.engine;

import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.SiteType;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Geometry invariants; also used by the copied-world native runtime checks. */
public final class BlueprintLayoutChecks {
    private BlueprintLayoutChecks() {}
    public static Map<String, Boolean> run() {
        Map<String, Boolean> checks = new LinkedHashMap<>();
        BlockPos origin = new BlockPos(-31, 97, 22);
        int[][] dimensions = {{23,25,21}, {11,8,11}, {15,9,15}, {17,10,17}, {15,11,15}, {15,13,19}};
        for (SiteType type : SiteType.values()) {
            BlueprintBounds b = Blueprint.bounds(type, Blueprint.CURRENT_VERSION);
            int[] expected = dimensions[type.ordinal()];
            checks.put("blueprint " + type + " declared dimensions", b.width() == expected[0] && b.height() == expected[1] && b.depth() == expected[2]);
            Map<BlockPos, BlockState> map = Blueprint.blocks(type, origin, Blueprint.CURRENT_VERSION);
            checks.put("blueprint " + type + " contains every block", map.keySet().stream().allMatch(p -> b.contains(p.getX() - origin.getX(), p.getY() - origin.getY(), p.getZ() - origin.getZ())));
            checks.put("blueprint " + type + " visible controller", map.get(Blueprint.controller(type, origin, Blueprint.CURRENT_VERSION)).equals(IndustryContent.controller(type).defaultBlockState()));
            boolean corridor = true;
            for (int z = b.minZ(); z <= 0; z++) for (int y = 1; y <= 2; y++) corridor &= !map.containsKey(origin.offset(0, y, z));
            checks.put("blueprint " + type + " two high entrance corridor", corridor);
            boolean foundation = true;
            for (int x = b.minX(); x <= b.maxX(); x++) for (int z = b.minZ(); z <= b.maxZ(); z++) foundation &= map.containsKey(origin.offset(x, 0, z));
            checks.put("blueprint " + type + " continuous foundation", foundation);
            BlockPos controllerApproach = Blueprint.controller(type, origin, Blueprint.CURRENT_VERSION).offset(0, 0, -1);
            checks.put("blueprint " + type + " controller reachable from apron", !map.containsKey(controllerApproach) && !map.containsKey(controllerApproach.above()) && map.containsKey(controllerApproach.below()));
            Map<BlockPos, BlockState> critical = Blueprint.criticalBlocks(type, origin, Blueprint.CURRENT_VERSION);
            checks.put("blueprint " + type + " critical machinery is physical", critical.entrySet().stream().allMatch(e -> e.getValue() != null && e.getValue().equals(map.get(e.getKey()))));
            Map<BlockPos, BlockState> legacy = Blueprint.blocks(type, origin, Blueprint.LEGACY_VERSION);
            checks.put("blueprint " + type + " original 0.2 map unchanged", legacy.equals(original(type, origin)));
            checks.put("blueprint " + type + " legacy has no new critical dependency", Blueprint.criticalBlocks(type, origin, Blueprint.LEGACY_VERSION).isEmpty());
        }
        checks.put("blueprint missing saved version remains legacy", Blueprint.savedVersion(0) == Blueprint.LEGACY_VERSION);
        checks.put("blueprint current saved version preserved", Blueprint.savedVersion(Blueprint.CURRENT_VERSION) == Blueprint.CURRENT_VERSION);
        return checks;
    }
    // Independent copy of the released algorithm deliberately protects the complete legacy state map.
    private static Map<BlockPos, BlockState> original(SiteType type, BlockPos origin) {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        int r = type == SiteType.SUBSTATION ? 2 : 3;
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
        result.put(origin.offset(1, 1, -r), IndustryContent.controller(type).defaultBlockState());
        if (type == SiteType.POWER_STATION) {
            result.put(origin.offset(-1, 1, 1), IndustryContent.structure("iron").defaultBlockState());
            result.put(origin.offset(1, 1, 1), IndustryContent.structure("iron").defaultBlockState());
        } else if (type == SiteType.SUBSTATION) result.put(origin.offset(0, 1, 0), IndustryContent.structure("copper").defaultBlockState());
        else result.put(origin.offset(-1, 1, 1), IndustryContent.structure("iron").defaultBlockState());
        result.put(origin.offset(0, 4, 0), IndustryContent.structure("light").defaultBlockState());
        return result;
    }
}
