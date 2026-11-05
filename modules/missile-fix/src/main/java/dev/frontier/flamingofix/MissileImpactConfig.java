package dev.frontier.flamingofix;

import java.util.LinkedHashMap;
import java.util.Map;
import dev.sbw.deepstrike.game.GuidedMissileEntity;
import net.minecraftforge.common.ForgeConfigSpec;

public final class MissileImpactConfig {
    public static final ForgeConfigSpec SPEC;
    private static final Map<String,ForgeConfigSpec.DoubleValue> BASE=new LinkedHashMap<>(), SCALE=new LinkedHashMap<>(), BLAST=new LinkedHashMap<>(),
            PENETRATION=new LinkedHashMap<>();
    static {
        var builder=new ForgeConfigSpec.Builder();
        builder.comment("Raw damage on the exact native entity contact. Native splash and armor remain active.").push("directImpact");
        for(String kind:new String[]{"fp5","storm_shadow","jagm","peacekeeper","spike_nlos"})
            BASE.put(kind,builder.defineInRange(kind,DirectImpactProfile.base(kind),0,4096));
        builder.pop().comment("Contact multipliers; native supported warheads and graphite EMP policy are preserved.").push("warheadScale");
        for(String warhead:new String[]{"none","he","penetrating","graphite","cluster","thermobaric","nuclear","shaped_charge"})
            SCALE.put(warhead,builder.defineInRange(warhead,DirectImpactProfile.scale(warhead),0,10));
        builder.pop().comment("Native crater radius/depth and partial-destruction radius multiplier; destroyed blocks grow ~ scale^3.").push("blastScale");
        for(String kind:new String[]{"fp5","storm_shadow","jagm","peacekeeper","spike_nlos"})
            BLAST.put(kind,builder.defineInRange(kind,switch(kind){case "storm_shadow"->2.0;case "peacekeeper"->1.5;default->1.0;},0.25,3.0));
        builder.pop().comment("Penetrator depth in blocks: HE/penetrating warheads punch through roofs and floors along",
                "their dive and burst on the first solid mass at least 4 blocks thick. 0 bursts at the contact.").push("penetration");
        for(String kind:new String[]{"fp5","storm_shadow","jagm","peacekeeper","spike_nlos"})
            PENETRATION.put(kind,builder.defineInRange(kind,switch(kind){case "storm_shadow"->24.0;case "fp5"->12.0;default->0.0;},0,64));
        SPEC=builder.pop().build();
    }
    /** 0 outside a loaded server config. */
    public static double penetration(String kind) {
        var value=PENETRATION.get(kind);
        if(value==null)return 0;
        try { return value.get(); } catch(IllegalStateException notLoaded) { return 0; }
    }
    /** 1 outside a loaded server config (client, early load). */
    public static double blastScale(String kind) {
        var value=BLAST.get(kind);
        if(value==null)return 1;
        try { return value.get(); } catch(IllegalStateException notLoaded) { return 1; }
    }
    public static float damage(GuidedMissileEntity missile,String warhead) {
        String kind=missile.kind().id;
        var base=BASE.get(kind);var multiplier=SCALE.get(warhead);
        return base==null || multiplier==null?0:DirectImpactProfile.damage(kind,warhead,base.get(),multiplier.get());
    }
    private MissileImpactConfig() {}
}
