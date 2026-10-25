package dev.frontier.nations.map;

import dev.frontier.nations.map.xaero.XaeroCountryLayer;
import dev.frontier.nations.map.xaero.XaeroKeyGate;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

/** Entry point safe to call from the common mod constructor on either physical side. */
public final class PoliticalMapBridge {
    private PoliticalMapBridge() {}

    public static void init() {
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> XaeroBridgeClient::init);
    }

    /** Client-only bootstrap kept separate so servers never load client keys. */
    private static final class XaeroBridgeClient {
        private XaeroBridgeClient() {}

        static void init() {
            XaeroCountryLayer.init();
            XaeroKeyGate.init();
        }
    }
}
