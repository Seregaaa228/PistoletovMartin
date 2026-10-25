package dev.frontier.nations.acceptance;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.talhanation.recruits.client.ClientManager;
import dev.frontier.nations.flag.FlagEditorScreen;
import dev.frontier.nations.flag.FlagCache;
import dev.frontier.nations.flag.FlagDefinition;
import dev.frontier.nations.flag.FlagNet;
import dev.frontier.nations.flag.FlagPreset;
import dev.frontier.nations.flag.FlagPresets;
import dev.frontier.nations.flag.TabFlags;
import dev.frontier.nations.map.border.BorderCache;
import dev.frontier.nations.map.border.BorderNet;
import dev.frontier.nations.map.geometry.BorderPolygon;
import dev.frontier.nations.map.geometry.BorderPolygon.Point;
import dev.frontier.nations.map.xaero.XaeroMapAccess;
import dev.frontier.industry.client.OperationsScreen;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import org.lwjgl.glfw.GLFW;
import xaero.map.gui.GuiMap;

final class MapClientChecks {
    private static final Map<String,Boolean> RESULTS=new LinkedHashMap<>();
    private static boolean started,stopping;
    private static int ticks,next,stage;
    private static List<Point> saved;
    private static int nativeChunks,pdaPhase,flagPhase;
    private static boolean payloadRequested;
    private static double zoomBefore;
    static void register(){MinecraftForge.EVENT_BUS.addListener(MapClientChecks::tick);}
    private static void tick(TickEvent.ClientTickEvent event){
        if(!MapChecks.enabled()||event.phase!=TickEvent.Phase.END||stopping)return;
        Minecraft mc=Minecraft.getInstance();
        try{
            if(!started){
                if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;
                started=true;mc.options.pauseOnLostFocus=false;
                if(Boolean.getBoolean("frontier.mapGuest")||Boolean.getBoolean("frontier.mapDedicated")){
                    int port=Integer.getInteger("frontier.mapPort",25585);String address="127.0.0.1:"+port;
                    ConnectScreen.startConnecting(mc.screen,mc,new ServerAddress("127.0.0.1",port),new ServerData("Map checks",address,false),false);return;
                }
                String folder="Map-Acceptance-"+System.getProperty("frontier.mapRunId").substring(0,8);
                if(Boolean.getBoolean("frontier.mapRestart"))mc.createWorldOpenFlows().loadLevel(mc.screen,folder);
                else{
                    if(Files.exists(mc.gameDirectory.toPath().resolve("saves").resolve(folder)))throw new IllegalStateException("Fresh world already exists");
                    mc.createWorldOpenFlows().createFreshLevel(folder,new LevelSettings("Xaero acceptance",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(20261025L,true,false),WorldPresets::createNormalWorldDimensions);
                }
                return;
            }
            if(mc.player==null||mc.level==null||mc.getOverlay()!=null)return;
            if(++ticks>3000)throw new IllegalStateException("Acceptance timed out at stage "+stage);
            if(ticks<next)return;
            if(Boolean.getBoolean("frontier.mapGuest")){guest(mc);return;}
            host(mc);
        }catch(Throwable error){LogUtils.getLogger().error("MAP_ACCEPTANCE failed stage {}",stage,error);RESULTS.put("unexpected exception stage "+stage,false);capture(mc,"failure-stage-"+stage+".png");finish(mc);}
    }
    private static void host(Minecraft mc){
        if(stage==0){
            if(ClientManager.ownFaction==null||!MapChecks.FACTION.equals(ClientManager.ownFaction.getStringID())||mc.screen!=null)return;
            if(Boolean.getBoolean("frontier.mapRestart")){check("border survives actual restart and login snapshot",BorderCache.get(MapChecks.FACTION)!=null);check("flag survives actual restart and login snapshot",expectedFlag().equals(FlagCache.flag(MapChecks.FACTION)));saved=BorderCache.get(MapChecks.FACTION).points();nativeChunks=claimChunks();KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_M));advance(40);return;}
            if(!payloadRequested){payloadRequested=true;MapChecks.requestLargePda();next=ticks+5;return;}
            try{var field=OperationsScreen.class.getDeclaredField("state");field.setAccessible(true);var data=(com.google.gson.JsonObject)field.get(null);
                if(!data.has("fixture"))return;check("large Unicode PDA snapshot arrives intact through real multi-packet transport",data.get("blob").getAsString().equals(MapChecks.largeBlob()));
            }catch(ReflectiveOperationException failed){throw new IllegalStateException(failed);}
            dev.frontier.industry.OperationsNet.request("refresh","",0);
            check("host native leader synced",mc.player.getUUID().equals(ClientManager.ownFaction.getTeamLeaderUUID()));
            nativeChunks=claimChunks();KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_M));xaero.map.controls.ControlsRegister.keyOpenMap.setDown(true);advance(40);return;
        }
        if(Boolean.getBoolean("frontier.mapRestart")){restart(mc);return;}
        switch(stage){
            case 1->{
                xaero.map.controls.ControlsRegister.keyOpenMap.setDown(false);check("M opens native Xaero GuiMap with exact adapter ("+(mc.screen==null?"none":mc.screen.getClass().getName())+")",mc.screen instanceof GuiMap&&mc.screen instanceof XaeroMapAccess);
                check("normal M map does not offer border editing",mc.screen.children().stream().noneMatch(c->c instanceof AbstractWidget w&&w.visible&&w.getMessage().getString().equals("Изменить контур")));
                capture(mc,"01-native-map.png");advance(8);}
            case 2->{if(!preparePdaEditor(mc))return;click(mc.screen.width-15,mc.screen.height-170,0);double w=mc.screen.width,h=mc.screen.height;for(Point p:List.of(new Point(w*.40,h*.33),new Point(w*.79,h*.33),new Point(w*.79,h*.70),new Point(w*.59,h*.58),new Point(w*.40,h*.75)))click(p.x(),p.z(),0);
                Point last=new Point(w*.40,h*.75);
                MinecraftForge.EVENT_BUS.post(new ScreenEvent.MouseButtonPressed.Pre(mc.screen,last.x(),last.z(),0));
                MinecraftForge.EVENT_BUS.post(new ScreenEvent.KeyPressed.Pre(mc.screen,GLFW.GLFW_KEY_BACKSPACE,0,0));
                MinecraftForge.EVENT_BUS.post(new ScreenEvent.MouseDragged.Pre(mc.screen,last.x()+5,last.z()+5,0,5,5));
                MinecraftForge.EVENT_BUS.post(new ScreenEvent.MouseButtonReleased.Pre(mc.screen,last.x()+5,last.z()+5,0));
                click(last.x(),last.z(),0);check("undo during a held vertex drag does not crash",true);advance(12);}
            case 3->{capture(mc,"02-drawing.png");click(30,79,0);advance(30);}
            case 4->{saved=BorderCache.get(MapChecks.FACTION)==null?null:BorderCache.get(MapChecks.FACTION).points();check("drawn concave polygon accepted via real C2S",saved!=null&&saved.size()==5);check("native claims unchanged after cosmetic save",nativeChunks==claimChunks());capture(mc,"03-saved-contour.png");((XaeroMapAccess)mc.screen).frontier$pan(70,-30);advance(12);}
            case 5->{capture(mc,"04-panned.png");mc.options.guiScale().set(4);mc.resizeDisplay();advance(12);}
            case 6->{capture(mc,"05-gui-scale-4.png");FlagEditorScreen.openFromMap(mc.screen);advance(10);}
            case 7->{if(flagPhase==0){check("flag editor reachable from Xaero",mc.screen instanceof FlagEditorScreen);for(var child:mc.screen.children())if(child instanceof AbstractWidget widget)check("flag widget fits small viewport",widget.getX()>=0&&widget.getY()>=0&&widget.getX()+widget.getWidth()<=mc.screen.width&&widget.getY()+widget.getHeight()<=mc.screen.height);capture(mc,"06-flag-editor.png");button(mc,"Круг / солнце");button(mc,"Сохранить");check("flag editor waits for server acknowledgement",mc.screen instanceof FlagEditorScreen);flagPhase=1;next=ticks+20;return;}
                check("flag save ACK returns to same Xaero",mc.screen instanceof GuiMap);check("saved flag matches authoritative cache",expectedFlag().equals(FlagCache.flag(MapChecks.FACTION)));mc.options.guiScale().set(2);mc.resizeDisplay();advance(8);}
            case 8->{if(mc.getConnection().getOnlinePlayers().size()<2)return;mc.setScreen(null);mc.options.keyPlayerList.setDown(true);advance(15);}
            case 9->{check("two real online players in vanilla Tab",mc.getConnection().getOnlinePlayers().size()>=2);for(var player:mc.getConnection().getOnlinePlayers())check("native Tab flag membership "+player.getProfile().getName(),TabFlags.faction(player)!=null);capture(mc,"07-vanilla-tab-flags.png");mc.options.keyPlayerList.setDown(false);BorderNet.save("not_own_country",saved);advance(25);}
            case 10->{var result=BorderCache.takeResult();check("foreign border write denied by real server",result!=null&&!result.accepted());check("foreign rejection preserves own contour",BorderCache.get(MapChecks.FACTION).points().equals(saved));advance(1);}
            case 11->{if(!peerContains("denied"))return;BorderNet.save(MapChecks.FACTION,List.of());advance(25);}
            case 12->{check("reset received by host",BorderCache.get(MapChecks.FACTION)==null);if(!peerContains("reset"))return;BorderNet.save(MapChecks.FACTION,saved);advance(25);}
            case 13->{check("saved polygon restored for restart",BorderCache.get(MapChecks.FACTION)!=null);check("native claims preserved through reset",nativeChunks==claimChunks());mc.setScreen(null);KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_M));xaero.map.controls.ControlsRegister.keyOpenMap.setDown(true);advance(20);}
            case 14->{
                xaero.map.controls.ControlsRegister.keyOpenMap.setDown(false);
                check("PDA editor access does not leak to next M map",mc.screen instanceof GuiMap&&!visibleButton(mc,"Изменить контур")&&!visibleButton(mc,"Сохранить"));
                mc.setScreen(new com.talhanation.recruits.client.gui.worldmap.WorldMapScreen());
                check("legacy Recruits map entry also opens Xaero",mc.screen instanceof GuiMap);
                check("legacy map entry cannot edit borders",!visibleButton(mc,"Изменить контур")&&!visibleButton(mc,"Сохранить"));
                capture(mc,"12-normal-map.png");finish(mc);
            }
            default->throw new IllegalStateException("Unknown stage "+stage);
        }
    }
    private static boolean visibleButton(Minecraft mc,String text){return mc.screen.children().stream().anyMatch(c->c instanceof AbstractWidget w&&w.visible&&w.getMessage().getString().equals(text));}
    private static void button(Minecraft mc,String text){
        if(mc.screen instanceof OperationsScreen){
            try{
                var hostField=OperationsScreen.class.getDeclaredField("widgets");hostField.setAccessible(true);
                var viewportField=OperationsScreen.class.getDeclaredField("viewport");viewportField.setAccessible(true);
                var host=(dev.frontier.industry.client.ui.LdlibWidgetHost)hostField.get(mc.screen);
                var viewport=(dev.frontier.industry.client.ui.UiViewport)viewportField.get(mc.screen);
                var widget=host.find(text);
                if(widget!=null&&widget.isVisible()){
                    if(widget.isActive())pdaClick(mc,viewport.screenX(widget.getPositionX()+widget.getSizeWidth()*.5),
                        viewport.screenY(widget.getPositionY()+widget.getSizeHeight()*.5));
                    return;
                }
            }catch(ReflectiveOperationException failure){throw new IllegalStateException("PDA fixture widget access failed",failure);}
        }
        for(var child:mc.screen.children())if(child instanceof Button b&&b.visible&&b.getMessage().getString().equals(text)){
            if(b.active)click(b.getX()+b.getWidth()/2.0,b.getY()+b.getHeight()/2.0,0);return;
        }
        throw new AssertionError("Missing button: "+text);
    }
    private static void pdaClick(Minecraft mc,double x,double y)throws ReflectiveOperationException{
        // Pinned LDLib ClickData reads MouseHandler.activeButton, so direct
        // Screen events cannot represent a real click. Use the vanilla callbacks.
        var window=mc.getWindow();
        var move=mouseCallback("onMove","m_91561_",long.class,double.class,double.class);
        var press=mouseCallback("onPress","m_91530_",long.class,int.class,int.class,int.class);
        move.invoke(mc.mouseHandler,window.getWindow(),x*window.getScreenWidth()/mc.screen.width,
            y*window.getScreenHeight()/mc.screen.height);
        press.invoke(mc.mouseHandler,window.getWindow(),0,GLFW.GLFW_PRESS,0);
        press.invoke(mc.mouseHandler,window.getWindow(),0,GLFW.GLFW_RELEASE,0);
    }
    private static java.lang.reflect.Method mouseCallback(String development,String runtime,Class<?>... arguments)throws NoSuchMethodException{
        java.lang.reflect.Method method;
        try{method=net.minecraft.client.MouseHandler.class.getDeclaredMethod(runtime,arguments);}
        catch(NoSuchMethodException dev){method=net.minecraft.client.MouseHandler.class.getDeclaredMethod(development,arguments);}
        method.setAccessible(true);return method;
    }
    private static boolean preparePdaEditor(Minecraft mc){
        if(pdaPhase==0){
            check("normal map stays read-only before PDA",mc.screen instanceof GuiMap&&!visibleButton(mc,"Изменить контур")&&!visibleButton(mc,"Сохранить"));
            mc.setScreen(null);mc.gameMode.useItem(mc.player,net.minecraft.world.InteractionHand.MAIN_HAND);pdaPhase=1;next=ticks+15;return false;
        }
        if(pdaPhase==1){
            check("real tablet interaction opens PDA",mc.screen instanceof OperationsScreen);
            button(mc,"Страна");capture(mc,"00-pda-country.png");button(mc,"Границы на карте");pdaPhase=2;next=ticks+15;return false;
        }
        if(pdaPhase==2){
            check("PDA borders button opens Xaero",mc.screen instanceof GuiMap);
            check("PDA opens ready-to-draw contour editor",visibleButton(mc,"Сохранить")&&visibleButton(mc,"Отмена"));
            mc.screen.onClose();check("closing PDA map returns to PDA",mc.screen instanceof OperationsScreen);
            button(mc,"Границы на карте");pdaPhase=3;next=ticks+12;return false;
        }
        return true;
    }
    private static void restart(Minecraft mc){
        switch(stage){
            case 1->{
                if(!preparePdaEditor(mc))return;
                check("map reopens after restart",mc.screen instanceof GuiMap);
                capture(mc,"08-block-contour.png");
                var input=new net.minecraft.client.gui.components.EditBox(mc.font,screenWidth(mc)-130,45,100,20,net.minecraft.network.chat.Component.literal("Native coordinate input"));
                mc.screen.setFocused(input);
                check("native text input keeps Backspace during contour edit",!MinecraftForge.EVENT_BUS.post(new ScreenEvent.KeyPressed.Pre(mc.screen,GLFW.GLFW_KEY_BACKSPACE,0,0)));
                mc.screen.setFocused(null);MinecraftForge.EVENT_BUS.post(new ScreenEvent.KeyPressed.Pre(mc.screen,GLFW.GLFW_KEY_ESCAPE,0,0));
                zoomBefore=((XaeroMapAccess)mc.screen).frontier$projection().scale();
                double x=mc.screen.width*.7,y=mc.screen.height*.55;
                var wheel=new ScreenEvent.MouseScrolled.Pre(mc.screen,x,y,4);
                check("native zoom wheel outside country panel is available",!MinecraftForge.EVENT_BUS.post(wheel));
                mc.screen.mouseScrolled(x,y,4);
                advance(20);
            }
            case 2->{
                var map=(XaeroMapAccess)mc.screen;Point corner=map.frontier$projection().screen(saved.get(3));
                check("actual native map zoom increased",map.frontier$projection().scale()>zoomBefore*1.5);
                map.frontier$pan(mc.screen.width*.65-corner.x(),mc.screen.height*.53-corner.z());advance(15);
            }
            case 3->{capture(mc,"09-block-detail.png");((XaeroMapAccess)mc.screen).frontier$pan(37.25,-23.5);advance(12);}
            case 4->{capture(mc,"10-block-panned.png");click(40,102,0);advance(10);}
            case 5->{
                capture(mc,"11-block-draft.png");
                check("block rendering preserves saved control points",BorderCache.get(MapChecks.FACTION).points().equals(saved));
                check("block rendering preserves native claims",nativeChunks==claimChunks());finish(mc);
            }
            default->throw new IllegalStateException("Unknown restart stage "+stage);
        }
    }
    private static void guest(Minecraft mc){
        switch(stage){
            case 0->{if(BorderCache.get(MapChecks.FACTION)==null)return;saved=BorderCache.get(MapChecks.FACTION).points();check("peer received synchronized contour",saved.size()==5);BorderNet.save(MapChecks.FACTION,List.of(new Point(0,0),new Point(100,0),new Point(0,100)));FlagNet.sendToServer(MapChecks.FACTION,FlagDefinition.solid(2));advance(25);}
            case 1->{var result=BorderCache.takeResult();check("foreign real client cannot edit host country",result!=null&&!result.accepted());check("peer sees original border after denied write",BorderCache.get(MapChecks.FACTION).points().equals(saved));check("foreign flag write cannot replace host flag",!FlagDefinition.solid(2).equals(FlagCache.flag(MapChecks.FACTION)));
                mc.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,net.minecraft.world.item.ItemStack.EMPTY);mc.gameMode.handleCreativeModeItemAdd(net.minecraft.world.item.ItemStack.EMPTY,36);advance(12);}
            case 2->{BorderNet.save("map_guest",List.of(new Point(0,0),new Point(100,0),new Point(0,100)));advance(25);}
            case 3->{var result=BorderCache.takeResult();check("leader cannot change own border without PDA",result!=null&&!result.accepted()&&BorderCache.get("map_guest")==null);
                var tablet=new net.minecraft.world.item.ItemStack(dev.frontier.industry.IndustryContent.preferredTablet());mc.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,tablet);mc.gameMode.handleCreativeModeItemAdd(tablet,36);MapChecks.write("peer-progress.json","{\"denied\":true}");advance(1);}
            case 4->{if(!expectedFlag().equals(FlagCache.flag(MapChecks.FACTION)))return;check("saved flag broadcast reaches second real client",true);if(BorderCache.get(MapChecks.FACTION)!=null)return;check("reset broadcast reached second real client",true);MapChecks.write("peer-progress.json","{\"denied\":true,\"reset\":true}");finish(mc);}
            default->throw new IllegalStateException("Unknown guest stage");
        }
    }
    private static FlagDefinition expectedFlag(){return FlagPresets.build(FlagPreset.SUN_DISC,14,0,11);}
    private static int screenWidth(Minecraft mc){return mc.screen.width;}
    private static int claimChunks(){return ClientManager.recruitsClaims==null?0:ClientManager.recruitsClaims.stream().filter(c->MapChecks.FACTION.equals(c.getOwnerFactionStringID())).mapToInt(c->c.getClaimedChunks().size()).sum();}
    private static boolean peerContains(String field){try{return Files.readString(Path.of(System.getProperty("frontier.mapPeerReport"))).contains("\""+field+"\":true");}catch(Exception notReady){return false;}}
    private static void click(double x,double y,int button){var mc=Minecraft.getInstance();var event=new ScreenEvent.MouseButtonPressed.Pre(mc.screen,x,y,button);if(!MinecraftForge.EVENT_BUS.post(event))mc.screen.mouseClicked(x,y,button);var release=new ScreenEvent.MouseButtonReleased.Pre(mc.screen,x,y,button);if(!MinecraftForge.EVENT_BUS.post(release))mc.screen.mouseReleased(x,y,button);}
    private static void advance(int delay){stage++;next=ticks+delay;LogUtils.getLogger().info("MAP_ACCEPTANCE stage {} at tick {}",stage,ticks);}
    private static void capture(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),message->LogUtils.getLogger().info("MAP_ACCEPTANCE screenshot {}",message.getString()));}
    private static void check(String name,boolean pass){RESULTS.put(name,pass);LogUtils.getLogger().info("MAP_ACCEPTANCE {}: {}",pass?"PASS":"FAIL",name);if(!pass)throw new AssertionError(name);}
    private static void finish(Minecraft mc){stopping=true;MapChecks.write(Boolean.getBoolean("frontier.mapRestart")?"acceptance-restart.json":"acceptance-results.json",new GsonBuilder().setPrettyPrinting().create().toJson(RESULTS));mc.stop();}
}
