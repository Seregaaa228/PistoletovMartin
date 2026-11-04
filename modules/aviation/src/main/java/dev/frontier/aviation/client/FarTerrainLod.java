package dev.frontier.aviation.client;

import dev.frontier.aviation.LightAviation;
import dev.frontier.aviation.control.client.FlightClient;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/**
 * Distant Horizons' far terrain reaches the horizon only from an aircraft. In a flight
 * benchmark at 260 km/h a 32-chunk view never loaded the terrain ahead, while 12 chunks
 * plus a far LOD showed it to the horizon; but the LOD cost roughly three quarters of the
 * frame rate, which on foot or in a ground vehicle buys nothing. Outside an aircraft the
 * LOD radius is set to its minimum through the DH API; in one, the player's configured
 * radius is restored. (Switching DH rendering off instead hides all terrain in DH 3.3.)
 * DH keeps its data either way, so the horizon is back as soon as the jet is entered.
 * Does nothing without DH, or with -Dfrontier.lodOnlyInAircraft=false.
 */
@Mod.EventBusSubscriber(modid = LightAviation.ID, value = Dist.CLIENT)
public final class FarTerrainLod {
    private static final boolean ENABLED = !"false".equals(System.getProperty("frontier.lodOnlyInAircraft"))
        && ModList.get().isLoaded("distanthorizons");
    private static Object radius;
    private static Method setValue;
    private static int groundRadius, flightRadius;
    private static Boolean applied;
    private static boolean failed;

    private FarTerrainLod() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || failed || event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        boolean flying = FlightClient.controlledEntity() != null
            || minecraft.player.getRootVehicle() instanceof Su24mEntity;
        if (applied != null && applied == flying) return;
        try {
            if (radius == null && !bind()) return;
            setValue.invoke(radius, flying ? flightRadius : groundRadius);
            applied = flying;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            failed = true;
            System.out.println("[frontier-aviation] Distant Horizons LOD radius switch unavailable: " + failure);
        }
    }

    /** False while DH has not published its config API yet. */
    private static boolean bind() throws ReflectiveOperationException {
        Object configs = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed").getField("configs").get(null);
        if (configs == null) return false;
        Class<?> configsType = Class.forName("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig");
        Class<?> graphicsType = Class.forName("com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig");
        Class<?> valueType = Class.forName("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue");
        Object graphics = configsType.getMethod("graphics").invoke(configs);
        Object value = graphicsType.getMethod("chunkRenderDistance").invoke(graphics);
        // The player's own setting, from the config file rather than any API override.
        flightRadius = (Integer) valueType.getMethod("getTrueValue").invoke(value);
        groundRadius = Math.min(flightRadius, (Integer) valueType.getMethod("getMinValue").invoke(value));
        setValue = valueType.getMethod("setValue", Object.class);
        radius = value;
        System.out.println("[frontier-aviation] Distant Horizons LOD radius: " + groundRadius + " chunks outside aircraft, "
            + flightRadius + " in flight");
        return true;
    }
}
