package dev.frontier.industry;

import dev.frontier.industry.engine.Blueprint;
import dev.frontier.industry.engine.IndustryData;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;

/** Chimneys visibly burn the plant's real fuel; an idle, unloaded or broken station stays quiet. */
public final class IndustrialEffects {
    private IndustrialEffects() {}
    /** V8 coolant steam at idle is visual only; dense smoke still requires actual fuel burn. */
    public static int intensity(IndustryData.Site site){
        if(site.type!=SiteType.POWER_STATION||site.constructing||!site.functional())return 0;
        if(site.blueprintVersion%100>=9)return site.burn>0?6:2;
        if(dev.frontier.industry.buildings.BuildingOrientation.applies(site.blueprintVersion)&&site.blueprintVersion%100>=8)return site.burn>0?6:1;
        return site.blueprintVersion>=2&&site.burn>0?3:0;
    }
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        for (var site : IndustryData.get(server).sites.values()) {
            var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, site.dimension));
            if (level == null || !level.hasChunkAt(site.origin)) continue;
            emit(level,site);
        }
    }
    public static int emit(net.minecraft.server.level.ServerLevel level,IndustryData.Site site){
            int strength=intensity(site);if(strength==0)return 0;int packets=0;
            if(dev.frontier.industry.buildings.BuildingOrientation.applies(site.blueprintVersion)) {
                for(var p:dev.frontier.industry.architecture.FinalIndustryArchitecture.smokePoints(site.blueprintVersion))for(var player:level.players())
                    if(player.distanceToSqr(site.origin.getX()+p.getX()+.5,site.origin.getY()+p.getY()+.1,site.origin.getZ()+p.getZ()+.5)<192*192)
                        if(level.sendParticles(player,strength==1?ParticleTypes.CLOUD:strength==2?ParticleTypes.CAMPFIRE_COSY_SMOKE:ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,true,site.origin.getX()+p.getX()+.5,site.origin.getY()+p.getY()+.1,site.origin.getZ()+p.getZ()+.5,strength<=2?2:strength,.15,.1,.15,strength<=2?.018:.035))packets++;
            } else for(int dx:new int[]{-6,0})level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,site.origin.getX()+dx+.5,site.origin.getY()+(site.blueprintVersion>=5?27.1:25.1),site.origin.getZ()+7.5,3,.25,.15,.25,.035);
            return packets;
    }
}
