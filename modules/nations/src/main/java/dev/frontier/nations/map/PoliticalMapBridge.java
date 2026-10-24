package dev.frontier.nations.map;

import dev.frontier.nations.map.client.PoliticalMapClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

/** Entry point safe to call from the common mod constructor on either physical side. */
public final class PoliticalMapBridge {
    private PoliticalMapBridge() {}

    public static void init() {
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> PoliticalMapClient::init);
    }
}
