package dev.frontier.industry.engine;
import com.mojang.authlib.GameProfile;
import dev.frontier.industry.*;
import dev.frontier.industry.architecture.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraftforge.common.util.FakePlayerFactory;

/** Capture the actual packets sent by ServerLevel.sendParticles, including all three outlets. */
public final class WorkshopSmokeChecks {
    public static Map<String,Boolean> run(MinecraftServer server){
        var checks=new LinkedHashMap<String,Boolean>();var level=server.overworld();var data=IndustryData.get(server);var origin=new BlockPos(0,80,0);
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"SmokeProbe"));player.moveTo(.5,81,.5,0,0);var packets=new ArrayList<ClientboundLevelParticlesPacket>();
        player.connection=new ServerGamePacketListenerImpl(server,new Connection(PacketFlow.SERVERBOUND),player){@Override public void send(Packet<?> packet){if(packet instanceof ClientboundLevelParticlesPacket particles)packets.add(particles);}};
        level.players().add(player);
        try{for(int q=0;q<4;q++){
            int v=10+100*q;var site=new IndustryData.Site(data,UUID.randomUUID(),player.getUUID(),SiteType.POWER_STATION,level.dimension().location(),origin);site.blueprintVersion=v;site.loaded=true;site.controller=true;site.machineryIntact=true;site.total=site.matching=10;
            packets.clear();site.burn=0;int emitted=IndustrialEffects.emit(level,site);checks.put("smoke10/"+q+" idle plume packets reach player",emitted==3&&packets.size()==3&&packets.stream().allMatch(p->p.getCount()==2&&p.isOverrideLimiter()));
            boolean positioned=true;for(var p:FinalIndustryArchitecture.smokePoints(v))positioned&=packets.stream().anyMatch(packet->Math.abs(packet.getX()-(origin.getX()+p.getX()+.5))<.001&&Math.abs(packet.getY()-(origin.getY()+p.getY()+.1))<.001&&Math.abs(packet.getZ()-(origin.getZ()+p.getZ()+.5))<.001);
            checks.put("smoke10/"+q+" actual packet positions match three chimney mouths",positioned);
            packets.clear();site.burn=1200;IndustrialEffects.emit(level,site);checks.put("smoke10/"+q+" real burn produces denser smoke",packets.size()==3&&packets.stream().allMatch(p->p.getCount()==6));
            packets.clear();player.moveTo(150,81,.5,0,0);checks.put("smoke10/"+q+" plume visible from neighbouring factory",IndustrialEffects.emit(level,site)==3&&packets.size()==3);player.moveTo(.5,81,.5,0,0);
            packets.clear();site.constructing=true;checks.put("smoke10/"+q+" construction emits no fake work",IndustrialEffects.emit(level,site)==0&&packets.isEmpty());
        }}finally{level.players().remove(player);}
        return checks;
    }
}
