package dev.frontier.industry.energy;

import java.util.Random;

/** Standalone Java17 checks against the same ledger used by the live FE adapter. */
public final class EnergyLedgerTest {
    private static int checks;

    public static void main(String[] args) {
        Balance station = new Balance(128), substation = new Balance(0), first = new Balance(0), second = new Balance(0);
        check("negative receive", EnergyLedger.receive(station, -1, false) == 0 && station.value == 128);
        check("negative extract", EnergyLedger.extract(station, Integer.MIN_VALUE, false) == 0 && station.value == 128);
        for (int i = 0; i < 10; i++) {
            check("repeated simulated extract", EnergyLedger.extract(station, 96, true) == 96 && station.value == 128 && station.writes == 0);
            check("repeated simulated receive", EnergyLedger.receive(substation, 128, true) == 128 && substation.value == 0 && substation.writes == 0);
        }
        check("station feeds distribution", EnergyLedger.transfer(station, substation, 128) == 128 && station.value == 0 && substation.value == 128);
        check("first consumer", EnergyLedger.transfer(substation, first, 96) == 96);
        check("second cannot double spend", EnergyLedger.transfer(substation, second, 96) == 32 && substation.value == 0);
        check("self transfer no writes", EnergyLedger.transfer(first, first, 10) == 0 && first.writes == 1);
        check("partial work debit refused", !EnergyLedger.consume(second, 33) && second.value == 32 && second.writes == 1);
        check("negative work debit refused", !EnergyLedger.consume(second, -1) && second.value == 32);
        check("exact work debit", EnergyLedger.consume(second, 32) && second.value == 0);
        check("empty extraction", EnergyLedger.extract(second, Integer.MAX_VALUE, false) == 0);
        Balance full = new Balance(31999);
        check("overflow capped", EnergyLedger.receive(full, Integer.MAX_VALUE, false) == 1 && full.value == 32000);
        check("full backpressure", EnergyLedger.transfer(first, full, 128) == 0 && first.value == 96);
        check("empty negative corrupt balance cannot mint", EnergyLedger.extract(new Balance(-100), 128, true) == 0);
        Balance recovered = new Balance(-100);
        check("receive normalizes old corrupt balance", EnergyLedger.receive(recovered, 7, false) == 7 && recovered.value == 7);
        Random random = new Random(0x50495338);
        Balance a = new Balance(17000), b = new Balance(15000);
        for (int i = 0; i < 10000; i++) {
            int before = a.value + b.value;
            int amount = random.nextInt(100000) - 100;
            if (random.nextBoolean()) EnergyLedger.transfer(a, b, amount); else EnergyLedger.transfer(b, a, amount);
            check("conservation under competing requests", a.value + b.value == before && a.value >= 0 && b.value >= 0 && a.value <= 32000 && b.value <= 32000);
        }
        var healthy = new GridConnection.Node(true, true);
        check("valid chain", GridConnection.consumer(healthy, healthy).connected());
        check("missing distribution cannot bypass", GridConnection.consumer(null, healthy) == GridConnection.NO_SUBSTATION);
        check("destroyed distribution stops downstream", GridConnection.consumer(new GridConnection.Node(true, false), healthy) == GridConnection.SUBSTATION_DAMAGED);
        check("unloaded distribution stops downstream", GridConnection.consumer(new GridConnection.Node(false, true), healthy) == GridConnection.SUBSTATION_UNLOADED);
        check("missing plant stops distribution", GridConnection.distribution(null) == GridConnection.NO_STATION);
        check("destroyed plant stops downstream", GridConnection.consumer(healthy, new GridConnection.Node(true, false)) == GridConnection.STATION_DAMAGED);
        check("unloaded plant stops downstream", GridConnection.consumer(healthy, new GridConnection.Node(false, true)) == GridConnection.STATION_UNLOADED);
        check("network repair reconnects", GridConnection.consumer(healthy, healthy) == GridConnection.CONNECTED);
        System.out.println("EnergyLedgerTest: " + checks + " checks passed");
    }

    private static void check(String label, boolean passed) {
        checks++;
        if (!passed) throw new AssertionError(label);
    }

    private static final class Balance implements EnergyLedger.Account {
        int value, writes;
        Balance(int value) { this.value = value; }
        @Override public int stored() { return value; }
        @Override public int capacity() { return 32000; }
        @Override public void store(int balance) { value = balance; writes++; }
    }
}
