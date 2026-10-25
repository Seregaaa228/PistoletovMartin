package dev.frontier.nations.map.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Legacy self-contained political map entry point.
 *
 * @deprecated Debug/fallback path only. The primary UI is the Xaero overlay
 * ({@code XaeroCountryLayer}): M opens Xaero's map with the country layer, T
 * toggles it. N no longer opens this screen. Kept so smoke/debug builds can
 * still reach the Recruits projection without Xaero installed.
 */
@Deprecated
public final class PoliticalMapClient {
    private static boolean initialized;

    private PoliticalMapClient() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        FMLJavaModLoadingContext.get().getModEventBus().addListener(PoliticalMapClient::registerKeys);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        // Intentionally registers no keys: the old N binding is retired and M
        // belongs to Xaero's own key options. Kept as a hook in case debug
        // builds need a fallback binding again.
    }

    /** Fallback/debug opener for the retired standalone screen. */
    public static void openFallback() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.level != null && minecraft.screen == null) {
            minecraft.setScreen(new PoliticalMapScreen());
        }
    }
}
