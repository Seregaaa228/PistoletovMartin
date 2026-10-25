package dev.frontier.nations.flag;

import com.talhanation.recruits.client.ClientManager;
import com.talhanation.recruits.world.RecruitsFaction;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

/** Resolves synchronized native faction members by UUID; independent of display/scoreboard formatting. */
public final class TabFlags {
    private static final Map<UUID,RecruitsFaction> FACTIONS=new HashMap<>();
    private static long tick=Long.MIN_VALUE;
    private TabFlags(){}
    public static RecruitsFaction faction(PlayerInfo player){
        var level=Minecraft.getInstance().level;if(level==null)return null;
        if(tick!=level.getGameTime()){
            tick=level.getGameTime();FACTIONS.clear();
            if(ClientManager.factions!=null)for(RecruitsFaction f:ClientManager.factions)if(f!=null){
                if(f.getTeamLeaderUUID()!=null)FACTIONS.put(f.getTeamLeaderUUID(),f);
                if(f.getMembers()!=null)for(var member:f.getMembers())if(member!=null&&member.getUUID()!=null)FACTIONS.put(member.getUUID(),f);
            }
        }
        return FACTIONS.get(player.getProfile().getId());
    }
    public static Component reserve(PlayerInfo player,Component name){return faction(player)==null?name:Component.literal("   ").append(name);}
    public static void draw(GuiGraphics graphics,int x,int y,PlayerInfo player){
        RecruitsFaction faction=faction(player);if(faction==null)return;
        Minecraft mc=Minecraft.getInstance();
        boolean heads=mc.isLocalServer()||mc.getConnection()!=null&&mc.getConnection().getConnection().isEncrypted();
        FlagRenderer.draw(graphics,faction.getStringID(),color(faction),x+(heads?9:0)+1,y,8,8);
    }
    public static int color(RecruitsFaction faction){ChatFormatting format=ChatFormatting.getById(faction.getTeamColor());return format!=null&&format.getColor()!=null?format.getColor():0x8296A5;}
    public static void clear(){FACTIONS.clear();tick=Long.MIN_VALUE;}
}
