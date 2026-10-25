package dev.frontier.flamingofix;

/** Sample-local exhaust visibility: sparse particle settings cannot phase-lock out flame. */
public final class TrailCadence {
    public static final int SMOKE = 1, FLAME = 2;

    public static int sample(long previous,long now,int interval,boolean nearby) {
        if(previous!=Long.MIN_VALUE && now>=previous && now-previous<interval)return 0;
        return SMOKE | (nearby?FLAME:0);
    }
    private TrailCadence() {}
}
