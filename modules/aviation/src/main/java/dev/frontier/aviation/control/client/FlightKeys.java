package dev.frontier.aviation.control.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.IKeyConflictContext;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/** Remappable controls scoped to piloting an aircraft without an open screen. */
public final class FlightKeys {
    private static final IKeyConflictContext FLIGHT_CONTEXT = new IKeyConflictContext() {
        @Override public boolean isActive() {
            return FlightClient.isLocalPilot() && Minecraft.getInstance().screen == null;
        }

        @Override public boolean conflicts(IKeyConflictContext other) {
            return this == other || other == HOVER_CONTEXT || other == KeyConflictContext.IN_GAME;
        }
    };

    private static final IKeyConflictContext HOVER_CONTEXT = new IKeyConflictContext() {
        @Override public boolean isActive() {
            return FlightClient.supportsLocalHover() && Minecraft.getInstance().screen == null;
        }
        @Override public boolean conflicts(IKeyConflictContext other) {
            return this == other || other == FLIGHT_CONTEXT || other == KeyConflictContext.IN_GAME;
        }
    };

    public static final KeyMapping ENGINE = key("engine", GLFW.GLFW_KEY_I);
    public static final KeyMapping BRAKE = key("brake", GLFW.GLFW_KEY_SPACE);
    public static final KeyMapping GEAR = key("gear", GLFW.GLFW_KEY_G);
    public static final KeyMapping HOVER = new KeyMapping("key.frontier_aviation.hover", HOVER_CONTEXT,
        InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.categories.frontier_aviation.flight");

    private FlightKeys() {}

    private static KeyMapping key(String name, int defaultKey) {
        return new KeyMapping("key.frontier_aviation." + name, FLIGHT_CONTEXT,
            InputConstants.Type.KEYSYM, defaultKey, "key.categories.frontier_aviation.flight");
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(ENGINE);
        event.register(BRAKE);
        event.register(GEAR);
        event.register(HOVER);
    }
}
