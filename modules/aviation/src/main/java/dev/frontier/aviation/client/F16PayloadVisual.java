package dev.frontier.aviation.client;

import dev.frontier.aviation.LightAviation;
import dev.frontier.aviation.control.net.FlightNetwork;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = LightAviation.ID, value = Dist.CLIENT)
public final class F16PayloadVisual {
    private record Entry(int bits, long tick) {}
    private static final Map<UUID, Entry> STATES = new HashMap<>();
    private static ClientLevel world;
    private static long tick;
    private F16PayloadVisual() {}
    public static void receive(FlightNetwork.Payload payload) {
        if (Minecraft.getInstance().level != null) STATES.put(payload.entity(), new Entry(payload.bits(), tick));
    }
    public static int bits(UUID entity) {
        Entry entry = STATES.get(entity);
        return entry == null ? 0 : entry.bits();
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ClientLevel current = Minecraft.getInstance().level;
        if (world != current) { STATES.clear(); world = current; }
        if (current == null || Minecraft.getInstance().isPaused()) return;
        ++tick;
        STATES.values().removeIf(entry -> tick - entry.tick() > 100);
    }
}
