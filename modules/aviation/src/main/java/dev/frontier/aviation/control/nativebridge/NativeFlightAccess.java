package dev.frontier.aviation.control.nativebridge;

import dev.frontier.aviation.control.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public interface NativeFlightAccess {
    boolean supports(Entity vehicle);
    FlightProfile profile(Entity vehicle);
    LivingEntity pilot(Entity vehicle);
    FlightState initialState(Entity vehicle);
    FlightEnvironment sample(Entity vehicle, long tick);
    void apply(Entity vehicle, FlightOutput output);
    FlightState captureAttitude(Entity vehicle, FlightState control);
    void savePersistent(Entity vehicle, FlightState state);
}
