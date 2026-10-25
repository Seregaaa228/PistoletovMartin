package dev.frontier.nations.acceptance;

import com.mojang.logging.LogUtils;
import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.world.RecruitsClaim;
import dev.frontier.nations.flag.FlagDefinition;
import dev.frontier.nations.flag.FlagNet;
import dev.frontier.nations.flag.FlagPreset;
import dev.frontier.nations.flag.FlagPresets;
import dev.frontier.nations.flag.NationFlags;
import dev.frontier.industry.IndustryContent;
import java.net.InetAddress;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

final class MapServerChecks {
    private int players;
    private boolean listening;
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event){
        if(event.phase!=TickEvent.Phase.END||!MapChecks.enabled())return;
        var server=event.getServer();var manager=FactionEvents.recruitsFactionManager;
        if(manager==null||server.getPlayerCount()==0)return;
        if(!listening){
            try{if(server.isDedicatedServer()){listening=true;}
                else{server.setUsesAuthentication(false);int port=Integer.getInteger("frontier.mapPort",25585);
                server.getConnection().startTcpServerListener(InetAddress.getByName("127.0.0.1"),port);
                // Mark the integrated server shared so map/flag screens do not pause
                // network ticks. Only the explicitly loopback-bound socket is opened;
                // publishServer would additionally advertise/bind on the LAN.
                var published=server.getClass().getDeclaredField("f_120017_");
                published.setAccessible(true);published.setInt(server,port);
                listening=true;MapChecks.write("acceptance-server-ready.txt","127.0.0.1:"+port);}}
            catch(Exception problem){throw new IllegalStateException(problem);}
        }
        if(server.getPlayerCount()==players)return;players=server.getPlayerCount();
        var level=server.overworld();
        for(var player:server.getPlayerList().getPlayers()){
            if(!IndustryContent.isTablet(player.getMainHandItem()))player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(IndustryContent.preferredTablet()));
            boolean host=player.getGameProfile().getName().equals("MapOwner");String id=host?MapChecks.FACTION:"map_guest";
            if(manager.getFactionByStringID(id)==null){
                manager.addTeam(id,host?"Вольная республика":"Северный союз",player.getUUID(),player.getGameProfile().getName(),new CompoundTag(),(byte)(host?14:11),host?ChatFormatting.GOLD:ChatFormatting.BLUE);
                manager.getFactionByStringID(id).addMember(player.getUUID(),player.getGameProfile().getName());
            }
            var board=server.getScoreboard();var team=board.getPlayerTeam(id);if(team==null)team=board.addPlayerTeam(id);
            team.setColor(host?ChatFormatting.GOLD:ChatFormatting.BLUE);board.addPlayerToTeam(player.getScoreboardName(),team);
            if(host&&com.talhanation.recruits.ClaimEvents.recruitsClaimManager.getAllClaims().stream().noneMatch(c->id.equals(c.getOwnerFactionStringID()))){
                RecruitsClaim claim=new RecruitsClaim("Тестовое владение",manager.getFactionByStringID(id));int cx=player.chunkPosition().x,cz=player.chunkPosition().z;claim.setCenter(new ChunkPos(cx,cz));
                for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)claim.addChunk(new ChunkPos(cx+x,cz+z));com.talhanation.recruits.ClaimEvents.recruitsClaimManager.addOrUpdateClaim(level,claim);
            }
            FlagDefinition flag=FlagPresets.build(host?FlagPreset.NORDIC_CROSS:FlagPreset.HORIZONTAL_TRICOLOR,host?14:11,0,14);
            if(NationFlags.get(server).flag(id)==null){NationFlags.get(server).set(id,flag);FlagNet.broadcastUpdate(id,flag);}
        }
        manager.save(level);manager.broadcastFactionsToAll(level);manager.broadcastOwnFactionToAll(level);com.talhanation.recruits.ClaimEvents.recruitsClaimManager.broadcastClaimsToAll(level);
        LogUtils.getLogger().info("MAP_ACCEPTANCE server: {} real players, native faction/claim fixtures ready",players);
    }
}
