package dev.frontier.flamingofix;

/** Explicit raw contact damage; native splash and native vehicle armor remain separate. */
public final class DirectImpactProfile {
    public static double base(String kind) {
        return switch(kind) {
            case "fp5" -> 300;
            case "storm_shadow" -> 220;
            case "jagm" -> 150;
            case "peacekeeper" -> 400;
            case "spike_nlos" -> 120;
            default -> 0;
        };
    }
    public static double scale(String warhead) {
        return switch(warhead) {
            case "none" -> .30;
            case "he", "graphite" -> 1;
            case "penetrating" -> 1.35;
            case "cluster" -> .65;
            case "thermobaric" -> .8;
            case "nuclear" -> 2.5;
            case "shaped_charge" -> 1.75;
            default -> 0;
        };
    }
    public static boolean supported(String kind,String warhead) {
        if(base(kind)==0 || scale(warhead)==0)return false;
        if(kind.equals("peacekeeper"))return java.util.Set.of("none","he","thermobaric","cluster","nuclear").contains(warhead);
        if(kind.equals("spike_nlos"))return java.util.Set.of("none","he","shaped_charge").contains(warhead);
        return !warhead.equals("nuclear") && !warhead.equals("shaped_charge");
    }
    public static float damage(String kind,String warhead,double raw,double scale) {
        if(!supported(kind,warhead) || !kind.equals("fp5") && warhead.equals("graphite"))return 0;
        if(!Double.isFinite(raw) || !Double.isFinite(scale) || raw<=0 || scale<=0)return 0;
        return (float)Math.min(16384,raw*scale);
    }
    private DirectImpactProfile() {}
}
