package dev.frontier.aviation.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Explicit native callback boundary for the separately packaged acceptance helper. */
@Mixin(value = MouseHandler.class, remap = false)
public interface FlightMouseInvoker {
    @Invoker("m_91561_") void aviation$onMove(long window, double x, double y);
}
