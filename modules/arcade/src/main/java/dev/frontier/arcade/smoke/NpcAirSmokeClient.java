package dev.frontier.arcade.smoke;

import dev.frontier.arcade.ArcadeVehicles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Automatic test-world entry only; never supplies player/vehicle flight inputs. */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT)
public final class NpcAirSmokeClient {
    private static boolean opened;
    private static int ticks, finish;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("frontier.npcAirSmoke") || event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!opened && minecraft.screen instanceof TitleScreen && minecraft.getOverlay() == null) {
            opened = true;
            if (java.nio.file.Files.exists(minecraft.gameDirectory.toPath().resolve("saves/NpcAir-Test/level.dat")))
                minecraft.createWorldOpenFlows().loadLevel(minecraft.screen, "NpcAir-Test");
            else minecraft.createWorldOpenFlows().createFreshLevel("NpcAir-Test",
                new LevelSettings("NPC Air Test", GameType.CREATIVE, false, Difficulty.NORMAL, true,
                    new GameRules(), WorldDataConfiguration.DEFAULT),
                new WorldOptions(20261024L, true, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                    .getOrThrow(WorldPresets.FLAT).createWorldDimensions());
        }
        if (minecraft.player != null && ++ticks > 2400) {
            System.err.println("NPC_AIR timeout waiting for native helicopter test");
            minecraft.stop();
        }
        if (NpcAirSmoke.done && ++finish > 35) minecraft.stop();
    }
}
