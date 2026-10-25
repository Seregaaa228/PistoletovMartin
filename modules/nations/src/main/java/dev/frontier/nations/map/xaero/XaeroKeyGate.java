package dev.frontier.nations.map.xaero;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lwjgl.glfw.GLFW;

/**
 * Key wiring for the Xaero country layer.
 *
 * <p>The legacy N binding is retired: M stays Xaero's own world-map key (we
 * never register or override it), T shows/hides our territory layer, and Tab
 * collapses the compact legend while the Xaero map is open.</p>
 */
public final class XaeroKeyGate {
    private static KeyMapping toggleLayer;
    private static boolean initialized;

    private XaeroKeyGate() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        FMLJavaModLoadingContext.get().getModEventBus().addListener(XaeroKeyGate::registerKeys);
        MinecraftForge.EVENT_BUS.addListener(XaeroKeyGate::clientTick);
        MinecraftForge.EVENT_BUS.addListener(XaeroKeyGate::collapseOnTab);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        // Literal labels work even when no optional language resource pack is installed.
        toggleLayer = new KeyMapping("Слой стран (Xaero)", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_T, "Frontier Nations");
        event.register(toggleLayer);
    }

    private static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || toggleLayer == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        while (toggleLayer.consumeClick()) {
            boolean on = XaeroCountryLayer.toggle();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.literal("Слой стран: " + (on ? "показан" : "скрыт")), true);
            }
        }
    }

    private static void collapseOnTab(ScreenEvent.KeyPressed.Pre event) {
        if (event.getKeyCode() != GLFW.GLFW_KEY_TAB) return;
        if (!XaeroCountryLayer.isXaeroWorldMapScreen(event.getScreen())) return;
        XaeroCountryLegend.toggleCollapsed();
        event.setCanceled(true);
    }
}
