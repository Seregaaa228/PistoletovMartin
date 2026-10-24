package dev.frontier.arcade.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;

/** Access is used only after the server's real UF radio evaluation succeeds. */
@Mixin(value = AddonDroneEntity.class, remap = false)
public interface AddonControlAccess {
    @Accessor("SIGNAL_LOST") static EntityDataAccessor<Boolean> frontier$lost() { throw new AssertionError(); }
    @Accessor("SIGNAL_QUALITY") static EntityDataAccessor<Float> frontier$quality() { throw new AssertionError(); }
}
