package dev.frontier.nations.map.xaero;

import dev.frontier.nations.flag.FlagCache;
import dev.frontier.nations.flag.FlagRenderer;
import dev.frontier.nations.flag.TabFlags;
import dev.frontier.nations.map.border.BorderCache;
import dev.frontier.nations.map.client.PoliticalMapSnapshot;
import dev.frontier.nations.map.client.PoliticalMapClient;
import dev.frontier.nations.map.geometry.BorderPolygon;
import dev.frontier.nations.map.geometry.BorderPolygon.Point;
import dev.frontier.nations.map.geometry.ChunkShape;
import dev.frontier.nations.map.geometry.MapProjection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import xaero.map.gui.GuiMap;

/** One exact layer in pinned Xaero's render pass; no guessed camera or parallel HUD. */
public final class XaeroCountryLayer {
    private static boolean enabled=true,initialized;
    private static PoliticalMapSnapshot snapshot;
    private static final Map<String,ChunkShape> SHAPES=new HashMap<>();
    private static long fingerprint=Long.MIN_VALUE;
    private static int ticks;
    private static CountryMapUi ui;
    private XaeroCountryLayer(){}
    public static void init(){
        if(initialized)return;initialized=true;
        MinecraftForge.EVENT_BUS.addListener(XaeroCountryLayer::tick);
        MinecraftForge.EVENT_BUS.addListener(XaeroCountryLayer::initScreen);
        MinecraftForge.EVENT_BUS.addListener(XaeroCountryLayer::press);
        MinecraftForge.EVENT_BUS.addListener(XaeroCountryLayer::release);
        MinecraftForge.EVENT_BUS.addListener(XaeroCountryLayer::drag);
        MinecraftForge.EVENT_BUS.addListener(XaeroCountryLayer::scroll);
        MinecraftForge.EVENT_BUS.addListener(XaeroCountryLayer::key);
        MinecraftForge.EVENT_BUS.addListener(XaeroCountryLayer::opening);
        MinecraftForge.EVENT_BUS.addListener(XaeroCountryLayer::logout);
        MinecraftForge.EVENT_BUS.addListener(XaeroCountryLayer::login);
    }
    public static boolean isEnabled(){return enabled;}
    public static void setEnabled(boolean value){enabled=value;}
    public static boolean toggle(){enabled=!enabled;return enabled;}
    public static boolean isXaeroWorldMapScreen(Screen screen){return screen instanceof GuiMap;}
    public static boolean overworld(GuiMap map){return map.getMapProcessor()!=null&&map.getMapProcessor().getMapWorld()!=null&&Level.OVERWORLD.equals(map.getMapProcessor().getMapWorld().getCurrentDimensionId());}
    static PoliticalMapSnapshot snapshot(){return snapshot;}
    private static void initScreen(ScreenEvent.Init.Post event){if(event.getScreen() instanceof GuiMap map){if(ui==null||ui.screen()!=map)ui=new CountryMapUi(map);ui.init(event);capture();}}
    private static void tick(TickEvent.ClientTickEvent event){
        if(event.phase!=TickEvent.Phase.END)return;
        if(Minecraft.getInstance().screen instanceof GuiMap&&++ticks>=20){ticks=0;capture();}
    }
    private static void capture(){
        if(Minecraft.getInstance().level==null)return;
        PoliticalMapSnapshot previous=snapshot;snapshot=PoliticalMapSnapshot.capture();long hash=1;
        for(var cell:snapshot.cells.values())hash=31*hash+cell.x*73428767L+cell.z*912931L+cell.claim.owner().hashCode()+cell.color;
        if(hash==fingerprint&&sameGeometry(previous,snapshot))return;fingerprint=hash;
        Map<String,List<ChunkShape.Chunk>> chunks=new HashMap<>();
        for(var cell:snapshot.cells.values())chunks.computeIfAbsent(cell.claim.owner(),k->new ArrayList<>()).add(new ChunkShape.Chunk(cell.x,cell.z));
        SHAPES.clear();chunks.forEach((owner,cells)->SHAPES.put(owner,ChunkShape.build(cells)));
    }
    private static boolean sameGeometry(PoliticalMapSnapshot a,PoliticalMapSnapshot b){
        if(a==null||a.cells.size()!=b.cells.size())return false;
        for(var entry:b.cells.entrySet()){
            var old=a.cells.get(entry.getKey());var cell=entry.getValue();
            if(old==null||old.color!=cell.color||!old.claim.owner().equals(cell.claim.owner()))return false;
        }
        return true;
    }
    public static void render(GuiMap map,GuiGraphics graphics,int mouseX,int mouseY){
        if(ui==null||ui.screen()!=map)return;
        boolean validDimension=overworld(map);
        ui.update(validDimension);
        if(GuiMap.hiddenUI){MapDrawing.pause();ui.resetGestures();return;}
        MapProjection view=((XaeroMapAccess)map).frontier$projection();
        MapDrawing.beginFrame();
        graphics.pose().pushPose();graphics.pose().translate(0,0,-160);
        graphics.enableScissor(0,38,map.width,map.height-34);
        if(enabled&&validDimension&&snapshot!=null){
            var occupied=new ArrayList<int[]>();
            for(var country:snapshot.countries){
                BorderPolygon custom=BorderCache.get(country.id());
                if(ui.isEditing(country.id()))custom=ui.preview();
                if(custom!=null)MapDrawing.blocks(graphics,custom,view,country.color(),map.width,map.height);
                else if(!ui.hasDraft(country.id())&&SHAPES.containsKey(country.id()))MapDrawing.chunks(graphics,SHAPES.get(country.id()),view,country.color(),map.width,map.height);
                Point anchor=custom==null?new Point(country.x()*16.0+8,country.z()*16.0+8):custom.labelPoint();
                if(view.scale()>=0.06&&(custom!=null||country.chunks()>0)&&!ui.hasDraft(country.id()))label(graphics,view.screen(anchor),country,occupied,map);
            }
        }
        if(validDimension)ui.renderDraft(graphics,view);
        graphics.disableScissor();graphics.pose().popPose();
        ui.renderPanel(graphics,mouseX,mouseY);
    }
    private static void label(GuiGraphics g,Point point,PoliticalMapSnapshot.Country c,List<int[]> occupied,GuiMap screen){
        var font=Minecraft.getInstance().font;String name=font.plainSubstrByWidth(c.name(),120);int w=font.width(name)+22,x=(int)point.x()-w/2,y=(int)point.z()-7;
        if(x<4||x+w>screen.width-4||y<40||y+15>screen.height-36||ui.intersectsPanel(x,y,w,15))return;
        for(int[] r:occupied)if(x<r[2]+4&&x+w>r[0]-4&&y<r[3]+3&&y+15>r[1]-3)return;
        occupied.add(new int[]{x,y,x+w,y+15});
        g.fill(x,y,x+w,y+15,0xD0171B20);FlagRenderer.draw(g,c.id(),c.color(),x+4,y+3,8,8);g.drawString(font,name,x+17,y+3,0xFFE7EBEF,false);
    }
    private static boolean current(Screen screen){return ui!=null&&ui.screen()==screen&&!GuiMap.hiddenUI;}
    private static void press(ScreenEvent.MouseButtonPressed.Pre e){if(current(e.getScreen())&&ui.press(e.getMouseX(),e.getMouseY(),e.getButton()))e.setCanceled(true);}
    private static void release(ScreenEvent.MouseButtonReleased.Pre e){if(current(e.getScreen())&&ui.release(e.getButton()))e.setCanceled(true);}
    private static void drag(ScreenEvent.MouseDragged.Pre e){if(current(e.getScreen())&&ui.drag(e.getMouseX(),e.getMouseY(),e.getMouseButton(),e.getDragX(),e.getDragY()))e.setCanceled(true);}
    private static void scroll(ScreenEvent.MouseScrolled.Pre e){if(current(e.getScreen())&&ui.scroll(e.getMouseX(),e.getMouseY(),e.getScrollDelta()))e.setCanceled(true);}
    private static void key(ScreenEvent.KeyPressed.Pre e){if(current(e.getScreen())&&ui.key(e.getKeyCode()))e.setCanceled(true);}
    private static void opening(ScreenEvent.Opening e){
        if(e.getNewScreen() instanceof com.talhanation.recruits.client.gui.worldmap.WorldMapScreen||PoliticalMapClient.isLegacyMap(e.getNewScreen())){
            Screen replacement=CountryMapClient.viewer(Minecraft.getInstance().screen);
            e.setNewScreen(replacement==null?Minecraft.getInstance().screen:replacement);
        }
        if(ui!=null&&e.getNewScreen()!=ui.screen()){
            MapDrawing.pause();
            CountryMapClient.leaving(ui.screen(),e.getNewScreen());
            ui.resetGestures();
            if(!(e.getNewScreen() instanceof dev.frontier.nations.flag.FlagEditorScreen)&&!(e.getNewScreen() instanceof xaero.map.gui.ScreenBase))ui=null;
        }
    }
    private static void login(ClientPlayerNetworkEvent.LoggingIn e){clear();}
    private static void logout(ClientPlayerNetworkEvent.LoggingOut e){clear();}
    private static void clear(){if(ui!=null)CountryMapClient.leaving(ui.screen(),null);snapshot=null;SHAPES.clear();MapDrawing.clear();fingerprint=Long.MIN_VALUE;ui=null;ticks=0;BorderCache.clear();FlagCache.replace(Map.of());TabFlags.clear();}
}
