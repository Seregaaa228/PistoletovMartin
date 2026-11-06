package dev.frontier.aviation.weapons;

import net.minecraft.world.phys.Vec3;

/** Tablet aircraft (DeepStrike Su24mEntity) as seen by our pylon code. */
public interface GlideBombRackView {
    /** Bit n set: pylon n holds a КАБ. */
    int aviation$kabMask();

    /** КАБ variant on a pylon (index into PylonOrdnance.KABS), -1 if none. */
    int aviation$kabVariant(int slot);

    /** Real flight velocity (blocks/tick), client-driven moves included. */
    Vec3 aviation$velocity();
}
