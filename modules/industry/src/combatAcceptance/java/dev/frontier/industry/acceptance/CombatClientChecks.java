package dev.frontier.industry.acceptance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

public final class CombatClientChecks {
    private static final Map<Integer,Vec3> firstPositions=new HashMap<>();
    private static final Map<String,Boolean> results=new LinkedHashMap<>();
    private static boolean connecting,finished;
    private static boolean actualWreckCaptured;
    private static int ticks,readyTick;
    public static void register(){MinecraftForge.EVENT_BUS.addListener(CombatClientChecks::tick);}
    private static void tick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||finished||!CombatChecks.enabled())return;
        var mc=Minecraft.getInstance();
        try {
            if(!connecting&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null){
                connecting=true;int port=Integer.getInteger("frontier.combatPort");
                ConnectScreen.startConnecting(mc.screen,mc,new ServerAddress("127.0.0.1",port),new ServerData("Isolated combat checks","127.0.0.1:"+port,false),false);return;
            }
            if(mc.level==null||mc.player==null)return;
            ticks++;
            results.putAll(AviationClientChecks.tick(mc,ticks));
            for(var entity:mc.level.entitiesForRendering()) {
                var id=net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
                if(id==null)continue;
                if(entity instanceof dev.sbw.deepstrike.game.GuidedMissileEntity){
                    results.put("native missile entity visible through normal tracking",true);
                    Vec3 first=firstPositions.putIfAbsent(entity.getId(),entity.position());
                    if(first!=null&&first.distanceToSqr(entity.position())>1)results.put("real missile flight changes client position",true);
                }
                if(entity instanceof dev.sbw.deepstrike.vehicle.Su24mEntity plane&&dev.frontier.aviation.AircraftProfile.of(plane)!=null) {
                    results.put("own aircraft correctly translated",!plane.getName().getString().toLowerCase().contains("su-24"));
                    if(plane.isWreck()&&!actualWreckCaptured){
                        actualWreckCaptured=true;capture(mc,"combat-actual-f16-wreck.png");
                        results.put("actual native aircraft wreck received through tracking",true);
                    }
                }
            }
            if(ticks==120||ticks==300||ticks==450||ticks==650||ticks==900){capture(mc,"combat-"+ticks+".png");}
            Path report=Path.of(System.getProperty("frontier.combatServerReport"));
            if(Files.isRegularFile(report)) {
                if(readyTick==0){readyTick=ticks;capture(mc,"combat-final.png");}
                if(ticks-readyTick>=40){
                    results.putIfAbsent("native missile entity visible through normal tracking",false);
                    results.putIfAbsent("real missile flight changes client position",false);
                    results.putIfAbsent("actual native aircraft wreck received through tracking",false);
                    results.put("two real players in client player list",mc.getConnection().getOnlinePlayers().size()==2);
                    CombatChecks.write("combat-client-results.json",results);finished=true;mc.stop();
                }
            }
            if(ticks>8800)throw new IllegalStateException("Combat server report timeout");
        }catch(Throwable error){results.put("unexpected client exception "+error,false);CombatChecks.write("combat-client-results.json",results);finished=true;mc.stop();}
    }
    private static void capture(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),message->{});}
}
