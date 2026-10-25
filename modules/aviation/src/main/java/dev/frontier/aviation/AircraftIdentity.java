package dev.frontier.aviation;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/** Display identity is derived from the stable registry type, including loaded aircraft. */
public final class AircraftIdentity {
    private AircraftIdentity() { }

    public static String mapLabel(Entity plane, String nativeLabel) {
        var profile = plane == null ? null : AircraftProfile.of(plane);
        return profile == null ? nativeLabel : Component.translatable("entity.frontier_aviation." + profile.id).getString();
    }

    public static String mapHeading(Entity plane, String nativeHeading) {
        var profile = plane == null ? null : AircraftProfile.of(plane);
        return profile == null ? nativeHeading : Component.translatable("screen.frontier_aviation.aircraft_tablet",
                Component.translatable("entity.frontier_aviation." + profile.id)).getString();
    }
}
