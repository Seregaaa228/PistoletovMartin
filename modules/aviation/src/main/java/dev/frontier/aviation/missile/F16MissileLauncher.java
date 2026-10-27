package dev.frontier.aviation.missile;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Server-only entry point installed on the native aircraft by the F16 mixin. */
public interface F16MissileLauncher {
    void aviation$fireMissiles(ServerPlayer pilot, int count, Vec3 target);
}
