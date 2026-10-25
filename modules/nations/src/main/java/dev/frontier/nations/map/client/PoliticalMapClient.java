package dev.frontier.nations.map.client;

import dev.frontier.nations.map.xaero.CountryMapClient;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Compatibility entry point for callers of the retired political map.
 * @deprecated All map entry points now use Xaero. Border editing starts in the PDA.
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

    public static boolean isLegacyMap(Screen screen){return screen instanceof PoliticalMapScreen;}
    /** Preserve the old API while opening the same Xaero viewer as other entry points. */
    public static void openFallback() {
        CountryMapClient.openViewer();
    }
}
