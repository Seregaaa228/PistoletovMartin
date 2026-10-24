package dev.frontier.arcade.smoke;

import dev.frontier.arcade.ArcadeVehicles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=ArcadeVehicles.ID,value=Dist.CLIENT)
public final class BmpSmokeClient {
    private static boolean opened;
    private static int ticks,finish;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e) {
        if(!Boolean.getBoolean("frontier.bmpSmoke")||e.phase!=TickEvent.Phase.END)return;
        var mc=Minecraft.getInstance();
        if(!opened&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null) {
            opened=true;
            if(java.nio.file.Files.exists(mc.gameDirectory.toPath().resolve("saves/Bmp-Test/level.dat")))
                mc.createWorldOpenFlows().loadLevel(mc.screen,"Bmp-Test");
            else mc.createWorldOpenFlows().createFreshLevel("Bmp-Test",new LevelSettings("BMP Test",GameType.CREATIVE,false,Difficulty.NORMAL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(20261024L,true,false),registries -> registries.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions());
        }
        if(mc.player!=null&&++ticks>3600) {System.err.println("BMP timeout");mc.stop();}
        if(BmpSmoke.done&&++finish>35)mc.stop();
    }
}
