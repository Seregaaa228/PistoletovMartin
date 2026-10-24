package dev.frontier.industry;
import dev.frontier.industry.construction.ConstructionRules;
import dev.frontier.industry.research.Technology;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraftforge.common.ForgeConfigSpec;
public final class IndustryConfig {
    public static final ForgeConfigSpec SPEC;
    private static final Map<String, ForgeConfigSpec.IntValue> BUILD = new LinkedHashMap<>(), TIME = new LinkedHashMap<>(), EMERALDS = new LinkedHashMap<>(), REDSTONE = new LinkedHashMap<>();
    public static final ForgeConfigSpec.IntValue PLACEMENT_BUDGET;
    static {
        var b = new ForgeConfigSpec.Builder();
        b.push("construction");
        PLACEMENT_BUDGET = b.defineInRange("globalBlocksPerTick", 64, 8, 256);
        for (SiteType type:SiteType.values()) BUILD.put(type.name(),b.defineInRange(type.path()+"Seconds",ConstructionRules.duration(type.name())/20,1,3600));
        b.pop();b.push("research");
        for (var t:Technology.values()) {
            b.push(t.id);TIME.put(t.id,b.defineInRange("seconds",t.seconds,1,36000));
            EMERALDS.put(t.id,b.defineInRange("emeralds",t.emeralds,0,384));REDSTONE.put(t.id,b.defineInRange("redstone",t.redstone,0,384));b.pop();
        }
        b.pop();SPEC=b.build();
    }
    public static int buildingTicks(SiteType type) { return BUILD.get(type.name()).get()*20; }
    public static int researchTicks(Technology t) { return TIME.get(t.id).get()*20; }
    public static int emeralds(Technology t) { return EMERALDS.get(t.id).get(); }
    public static int redstone(Technology t) { return REDSTONE.get(t.id).get(); }
}
