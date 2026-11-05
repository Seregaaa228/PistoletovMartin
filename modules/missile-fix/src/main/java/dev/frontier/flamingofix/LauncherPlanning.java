package dev.frontier.flamingofix;

import net.minecraft.server.level.ServerLevel;

/** The level a DeepStrike launcher is ticking (and so planning a route) in, on this thread. */
public final class LauncherPlanning {
    private static final ThreadLocal<ServerLevel> LEVEL = new ThreadLocal<>();

    public static ServerLevel current() { return LEVEL.get(); }
    public static void enter(ServerLevel level) { LEVEL.set(level); }
    public static void leave() { LEVEL.remove(); }

    private LauncherPlanning() {}
}
