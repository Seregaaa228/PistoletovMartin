package dev.frontier.flamingofix;

import java.util.LinkedHashMap;
import java.util.Map;
import dev.sbw.deepstrike.game.GuidedMissileEntity;
import net.minecraftforge.common.ForgeConfigSpec;

public final class MissileImpactConfig {
    public static final ForgeConfigSpec SPEC;
    private static final Map<String,ForgeConfigSpec.DoubleValue> BASE=new LinkedHashMap<>(), SCALE=new LinkedHashMap<>();
    static {
        var builder=new ForgeConfigSpec.Builder();
        builder.comment("Raw damage on the exact native entity contact. Native splash and armor remain active.").push("directImpact");
        for(String kind:new String[]{"fp5","storm_shadow","jagm","peacekeeper","spike_nlos"})
            BASE.put(kind,builder.defineInRange(kind,DirectImpactProfile.base(kind),0,4096));
        builder.pop().comment("Contact multipliers; native supported warheads and graphite EMP policy are preserved.").push("warheadScale");
        for(String warhead:new String[]{"none","he","penetrating","graphite","cluster","thermobaric","nuclear","shaped_charge"})
            SCALE.put(warhead,builder.defineInRange(warhead,DirectImpactProfile.scale(warhead),0,10));
        SPEC=builder.pop().build();
    }
    public static float damage(GuidedMissileEntity missile,String warhead) {
        String kind=missile.kind().id;
        var base=BASE.get(kind);var multiplier=SCALE.get(warhead);
        return base==null || multiplier==null?0:DirectImpactProfile.damage(kind,warhead,base.get(),multiplier.get());
    }
    private MissileImpactConfig() {}
}
