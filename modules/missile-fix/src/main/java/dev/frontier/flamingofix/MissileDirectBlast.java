package dev.frontier.flamingofix;

import net.minecraft.world.entity.Entity;

/** Bridge from vanilla's existing explosion recipient event to a native missile contact. */
public interface MissileDirectBlast {
    Entity flamingo$takeRecipient();
    void flamingo$hurtRecipient(Entity target);
    boolean flamingo$retainNativeVehicle(Entity target);
    boolean flamingo$finishRecipient();
    boolean flamingo$contactOnlyEvent();
}
