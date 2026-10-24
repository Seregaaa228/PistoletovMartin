package dev.frontier.nations.map.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lwjgl.glfw.GLFW;

/** Loaded only through the physical-client DistExecutor gate. */
public final class PoliticalMapClient {
    private static KeyMapping openMap;
    private static boolean initialized;

    private PoliticalMapClient() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        FMLJavaModLoadingContext.get().getModEventBus().addListener(PoliticalMapClient::registerKeys);
        MinecraftForge.EVENT_BUS.addListener(PoliticalMapClient::clientTick);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        // Literal labels work even when no optional language resource pack is installed.
        openMap = new KeyMapping("Политическая карта", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_N, "Frontier Nations");
        event.register(openMap);
    }

    private static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || openMap == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        while (openMap.consumeClick()) {
            if (minecraft.player != null && minecraft.level != null && minecraft.screen == null) {
                minecraft.setScreen(new PoliticalMapScreen());
            }
        }
    }

    static boolean matchesOpenKey(int keyCode, int scanCode) {
        return openMap != null && openMap.matches(keyCode, scanCode);
    }
}
