package dev.frontier.aviation.ordnance;

import java.util.List;
import static dev.frontier.aviation.ordnance.StoreStations.Store.*;

public final class StoreStationsTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void close(double[] actual, double[] expected, String message) {
        for (int i = 0; i < 3; i++) check(Math.abs(actual[i] - expected[i]) < 1e-9, message);
    }

    public static void main(String[] args) {
        var f16 = StoreStations.F16;
        // F-16: the saved/synced station order and allocation are unchanged.
        var fixed = new StoreStations.Store[]{STORM_SHADOW, STORM_SHADOW, null, null, null, null};
        var mixed = f16.assign(fixed, List.of(UNDERWING, UNDERWING, LIGHT_AIR_TO_AIR, LIGHT_AIR_TO_AIR));
        check(mixed.isPresent(), "six compatible stations");
        check(mixed.get().get(4) == LIGHT_AIR_TO_AIR && mixed.get().get(5) == LIGHT_AIR_TO_AIR, "wingtips");
        check(f16.assign(fixed, List.of(UNDERWING, UNDERWING, UNDERWING)).isEmpty(), "large stores cannot use tips");
        check(f16.assign(fixed, List.of(LIGHT_AIR_TO_AIR, LIGHT_AIR_TO_AIR, LIGHT_AIR_TO_AIR, LIGHT_AIR_TO_AIR, LIGHT_AIR_TO_AIR)).isEmpty(), "seven stores");
        check(f16.assign(new StoreStations.Store[]{null, null, null, null, STORM_SHADOW, null}, List.of()).isEmpty(), "fixed store compatibility");
        check(f16.assign(new StoreStations.Store[6], List.of(LIGHT_AIR_TO_AIR, UNDERWING, UNDERWING, UNDERWING, UNDERWING, LIGHT_AIR_TO_AIR)).isPresent(), "order independent allocation");
        check(f16.racks() == 2 && f16.rackStation(0) == 0 && f16.rackStation(1) == 1, "F-16 racks on the inner pylons");
        check(java.util.Arrays.equals(f16.order(UNDERWING), new int[]{2, 3, 0, 1}), "F-16 underwing fill order");
        check(java.util.Arrays.equals(f16.order(LIGHT_AIR_TO_AIR), new int[]{4, 5, 0, 1, 2, 3}), "F-16 light fill order");
        check(java.util.Arrays.equals(f16.fireOrder(LIGHT_AIR_TO_AIR), new int[]{4, 5, 2, 3, 0, 1}), "F-16 light firing order");
        check(java.util.Arrays.equals(f16.fireOrder(UNDERWING), new int[]{2, 3, 0, 1}), "F-16 underwing firing order");

        // Su-25: two tablet racks inner, AG missiles under the wings, light AAMs outermost.
        var su25 = StoreStations.SU25;
        check(su25.size() == 6 && su25.racks() == 2, "Su-25 six stations, two racks");
        check(su25.assign(new StoreStations.Store[6], List.of(STORM_SHADOW, STORM_SHADOW, UNDERWING, UNDERWING,
            LIGHT_AIR_TO_AIR, LIGHT_AIR_TO_AIR)).isPresent(), "Su-25 full mixed load");
        check(su25.assign(new StoreStations.Store[6], List.of(LIGHT_AIR_TO_AIR, LIGHT_AIR_TO_AIR, LIGHT_AIR_TO_AIR)).isEmpty(),
            "Su-25 light AAMs only on the outer pylons");
        check(su25.assign(new StoreStations.Store[]{STORM_SHADOW, STORM_SHADOW, null, null, null, null},
            List.of(UNDERWING, UNDERWING, UNDERWING)).isEmpty(), "Su-25 racks block the inner pylons");

        // J-10: one tablet rack on the belly centreline, no second rack.
        var j10 = StoreStations.J10;
        check(j10.size() == 5 && j10.racks() == 1 && j10.rackStation(0) == 0 && j10.rackStation(1) == -1,
            "J-10 single belly rack");
        var belly = j10.stations().get(0);
        check(belly.x() == 0 && belly.takes(STORM_SHADOW) && !belly.takes(UNDERWING), "belly is on the centreline");
        check(j10.assign(new StoreStations.Store[5], List.of(STORM_SHADOW, STORM_SHADOW)).isEmpty(), "two big stores do not fit a J-10");
        check(j10.assign(new StoreStations.Store[5], List.of(STORM_SHADOW, UNDERWING, UNDERWING,
            LIGHT_AIR_TO_AIR, LIGHT_AIR_TO_AIR)).isPresent(), "J-10 full mixed load");
        check(j10.assign(new StoreStations.Store[5], List.of(UNDERWING, UNDERWING, UNDERWING)).isEmpty(),
            "J-10 large missiles only on the inner pylons");

        // Hang and launch: the store top sits the clearance below the attach point.
        var inner = f16.stations().get(0);
        int agm = StoreStations.model(StoreStations.AGM_65, -1);
        close(StoreStations.hang(inner, agm), new double[]{-2.1, 2.2 - 0.1905 - 0.015, -0.2 + 1.86134}, "AGM hangs below pylon");
        close(StoreStations.launch(inner, agm), new double[]{2.1, 2.2 - 0.1905 - 0.015, 0.2 - 1.86134}, "launch in vehicle frame");
        check(StoreStations.model(StoreStations.RACK, -1) == 4 && StoreStations.model(StoreStations.RACK, 2) == 7,
            "rack models: Storm Shadow, КАБ-3000");
        for (var layout : List.of(su25, f16, j10)) for (var station : layout.stations())
            check(station.y() > 1.5 && station.y() < 2.5 && Math.abs(station.x()) < 5.2, layout.id() + " station inside airframe");
        check(StoreStations.forType("frontier_aviation:j10").orElseThrow() == j10
            && StoreStations.forType("deepstrike:su24m").isEmpty(), "layouts only for owned jets");
        System.out.println("Store stations passed");
    }
}
