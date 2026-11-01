package dev.frontier.aviation.ordnance;

import java.util.List;
import static dev.frontier.aviation.ordnance.F16Stations.Store.*;

public final class F16StationsTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        var fixed = new F16Stations.Store[]{STORM_SHADOW, STORM_SHADOW, null, null, null, null};
        var mixed = F16Stations.assign(fixed, List.of(UNDERWING, UNDERWING, LIGHT_AIR_TO_AIR, LIGHT_AIR_TO_AIR));
        check(mixed.isPresent(), "six compatible stations");
        check(mixed.get().get(4) == LIGHT_AIR_TO_AIR && mixed.get().get(5) == LIGHT_AIR_TO_AIR, "wingtips");
        check(F16Stations.assign(fixed, List.of(UNDERWING, UNDERWING, UNDERWING)).isEmpty(), "large stores cannot use tips");
        check(F16Stations.assign(fixed, List.of(LIGHT_AIR_TO_AIR, LIGHT_AIR_TO_AIR, LIGHT_AIR_TO_AIR, LIGHT_AIR_TO_AIR, LIGHT_AIR_TO_AIR)).isEmpty(), "seven stores");
        check(F16Stations.assign(new F16Stations.Store[]{null, null, null, null, STORM_SHADOW, null}, List.of()).isEmpty(), "fixed store compatibility");
        check(F16Stations.assign(new F16Stations.Store[6], List.of(LIGHT_AIR_TO_AIR, UNDERWING, UNDERWING, UNDERWING, UNDERWING, LIGHT_AIR_TO_AIR)).isPresent(), "order independent allocation");
        System.out.println("F-16 stations passed");
    }
}
