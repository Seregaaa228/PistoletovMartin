package dev.frontier.ifv.client;

/** Deadlines include each particle's lifetime, rather than only its spawn time. */
public final class WreckEffectTimeline {
    public static final int BRIGHT_TICKS = 40;
    public static final int RESIDUAL_TICKS = 100;
    private final long start;
    private long emitted = Long.MIN_VALUE;

    public WreckEffectTimeline(long start) { this.start = start; }

    public int age(long tick) { return (int) Math.max(0, Math.min(Integer.MAX_VALUE, tick - start)); }
    public int brightLifetime(long tick) { return Math.max(0, Math.min(8, BRIGHT_TICKS - age(tick))); }
    public int smokeLifetime(long tick) { return Math.max(0, Math.min(20, RESIDUAL_TICKS - age(tick))); }

    public boolean emit(long tick) {
        if (emitted == tick || age(tick) >= RESIDUAL_TICKS || age(tick) % 4 != 0) return false;
        emitted = tick;
        return true;
    }
}
