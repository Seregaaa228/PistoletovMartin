package dev.frontier.arcade.station.client;

import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.control.ControlContent;
import dev.frontier.arcade.station.StationContent;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Loaded only on the physical client. Common station registration never references a screen class. */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class StationClient {
    private StationClient() { }
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(StationContent.MENU.get(), StationScreen::new));
    }

    /** Station connections do not invoke MonitorItem.use, which normally enters this native camera mode. */
    @Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT)
    public static final class SessionCamera {
        private static CameraType previous;
        private static UUID player;

        @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            UUID currentPlayer = mc.player == null ? null : mc.player.getUUID();
            boolean active = false;
            if (mc.player != null && mc.level != null && mc.player.isAlive()) {
                var held = mc.player.getMainHandItem(); var tag = held.getTag();
                active = held.is(ControlContent.STATION_LINK.get()) && tag != null
                    && tag.getBoolean("Using") && tag.getBoolean("Linked");
            }
            if (previous != null && (!active || !java.util.Objects.equals(player, currentPlayer))) {
                mc.options.setCameraType(previous);
                previous = null; player = null;
            }
            if (active && previous == null) {
                previous = mc.options.getCameraType(); player = currentPlayer;
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
        }
    }
}
