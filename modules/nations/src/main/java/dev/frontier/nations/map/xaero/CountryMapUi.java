package dev.frontier.nations.map.xaero;

import com.talhanation.recruits.client.ClientManager;
import dev.frontier.nations.flag.FlagEditorScreen;
import dev.frontier.nations.flag.FlagLeadership;
import dev.frontier.nations.flag.FlagRenderer;
import dev.frontier.nations.map.border.BorderCache;
import dev.frontier.nations.map.border.BorderNet;
import dev.frontier.nations.map.geometry.BorderPolygon;
import dev.frontier.nations.map.geometry.BorderPolygon.Point;
import dev.frontier.nations.map.geometry.MapProjection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;
import xaero.map.gui.GuiMap;

/** One editing session per actual map screen; the rest of Xaero remains interactive. */
final class CountryMapUi {
    private final GuiMap screen;
    private final boolean editorAccess;
    private final List<MapButton> buttons=new ArrayList<>();
    private final List<Point> points=new ArrayList<>();
    private final Set<Integer> capturedButtons=new HashSet<>();
    private MapButton header,layer,flag,edit,save,cancel,undo,reset;
    private boolean collapsed,editing,pending,resetDraft,validDimension=true,middle,editorStarted;
    private String faction,error="",status="";
    private long statusUntil,pendingRequest;
    private BorderPolygon preview;
    private int dragging=-1,scroll,width;
    private static final int X=28,Y=46,ROW=15;
    CountryMapUi(GuiMap screen){this.screen=screen;editorAccess=CountryMapClient.isEditor(screen);}
    GuiMap screen(){return screen;}
    void init(ScreenEvent.Init.Post event){
        resetGestures();buttons.clear();width=screen.width<540?120:148;int half=(width-4)/2;
        header=button(X,Y,width,"Страны",()->{if(editorAccess)screen.onClose();else if(!editing)collapsed=!collapsed;},event);
        layer=button(X,Y+23,half,"Слой",XaeroCountryLayer::toggle,event);
        flag=button(X+half+4,Y+23,half,"Мой флаг",()->FlagEditorScreen.openFromMap(screen),event);
        edit=button(X,Y+46,width,"Изменить контур",this::start,event);
        save=button(X,Y+23,half,"Сохранить",this::save,event);
        cancel=button(X+half+4,Y+23,half,"Отмена",()->{if(pending)screen.onClose();else cancel();},event);
        undo=button(X,Y+46,half,"Назад",this::undo,event);
        reset=button(X+half+4,Y+46,half,"Сбросить",()->{dragging=-1;points.clear();resetDraft=true;rebuild();},event);
        update(XaeroCountryLayer.overworld(screen));
    }
    private MapButton button(int x,int y,int w,String text,Runnable action,ScreenEvent.Init.Post event){MapButton b=new MapButton(x,y,w,text,action);buttons.add(b);event.addListener(b);return b;}
    void update(boolean dimension){
        validDimension=dimension;
        boolean allowed=editorAccess&&CountryMapClient.canEdit(screen);
        if(allowed&&!editorStarted&&dimension&&FlagLeadership.isLocalLeader()){editorStarted=true;start();}
        if(editing&&(!allowed||!dimension||!FlagLeadership.isLocalLeader()||ClientManager.ownFaction==null||!faction.equals(ClientManager.ownFaction.getStringID())))cancel();
        var result=BorderCache.takeResult(pendingRequest);
        if(result!=null&&pending&&result.faction().equals(faction)){
            pending=false;pendingRequest=0;notice(result.message());if(result.accepted()){editing=false;points.clear();preview=null;resetDraft=false;}
        }
        if(header==null)return;
        int countries=XaeroCountryLayer.snapshot()==null?0:XaeroCountryLayer.snapshot().countries.size();
        header.setMessage(Component.literal(editorAccess?"← КПК"+(editing?" · "+points.size()+"/128":""):"Страны: "+countries+(collapsed?"  +":"  −")));
        header.active=editorAccess||!editing;header.visible=true;
        layer.setMessage(Component.literal(XaeroCountryLayer.isEnabled()?"Слой: вкл":"Слой: выкл"));
        layer.visible=flag.visible=!collapsed&&!editing;
        edit.visible=editorAccess&&!collapsed&&!editing;
        layer.active=validDimension;
        flag.active=validDimension&&ClientManager.ownFaction!=null;
        edit.active=allowed&&validDimension&&FlagLeadership.isLocalLeader()&&!BorderCache.hasPendingSave();
        save.visible=cancel.visible=undo.visible=reset.visible=editing;
        cancel.setMessage(Component.literal(pending?"← КПК":"Отмена"));
        save.active=!pending&&(preview!=null||resetDraft);
        cancel.active=true;undo.active=reset.active=!pending;
        undo.active&=!points.isEmpty();
    }
    private void start(){
        if(!editorAccess||!CountryMapClient.canEdit(screen)||!FlagLeadership.isLocalLeader()||ClientManager.ownFaction==null||!validDimension)return;
        if(BorderCache.hasPendingSave()){notice("Сначала дождитесь ответа на предыдущее сохранение");return;}
        faction=ClientManager.ownFaction.getStringID();points.clear();
        BorderPolygon existing=BorderCache.get(faction);if(existing!=null)points.addAll(existing.points());
        editing=true;pending=false;resetDraft=false;collapsed=false;status="";XaeroCountryLayer.setEnabled(true);rebuild();
    }
    private void cancel(){editing=false;pending=false;pendingRequest=0;middle=false;dragging=-1;points.clear();preview=null;resetDraft=false;}
    private void undo(){dragging=-1;if(!points.isEmpty()){points.remove(points.size()-1);resetDraft=false;rebuild();}}
    private void save(){
        if(pending||!CountryMapClient.canEdit(screen)||(!resetDraft&&preview==null))return;
        long request=BorderNet.save(faction,resetDraft?List.of():points);
        if(request==0){notice("Сохранение недоступно: дождитесь соединения или ответа сервера");return;}
        pendingRequest=request;pending=true;status="";
    }
    private void rebuild(){
        preview=null;error="";
        if(resetDraft)return;
        if(points.size()<3){error="Поставьте минимум 3 точки";return;}
        try{preview=new BorderPolygon(points);}catch(IllegalArgumentException invalid){error=invalid.getMessage();}
    }
    boolean isEditing(String id){return editing&&faction.equals(id)&&!resetDraft;}
    boolean hasDraft(String id){return editing&&faction.equals(id)&&!resetDraft;}
    BorderPolygon preview(){return preview;}
    private int rows(){return Math.max(1,Math.min(7,(screen.height-Y-115)/ROW));}
    private int bottom(){return collapsed?Y+20:editing?Math.min(screen.height-38,Y+183):Y+74+rows()*ROW+8;}
    boolean intersectsPanel(int x,int y,int w,int h){return x<X+width&&x+w>X&&y<bottom()&&y+h>Y;}
    private boolean inside(double x,double y){return x>=X&&x<X+width&&y>=Y&&y<bottom();}
    void renderPanel(GuiGraphics g,int mouseX,int mouseY){
        if(header==null)return;
        var font=Minecraft.getInstance().font;
        g.fill(X-2,Y-2,X+width+2,bottom()+2,0xE5171B20);g.fill(X-2,Y-2,X-1,bottom()+2,0xFF38414A);
        if(!collapsed){
            if(!editorAccess)g.drawWordWrap(font,Component.literal("Контур: КПК → Страна"),X+6,Y+49,width-12,0xFFAAB4BE);
            if(editing){
                String text=pending?"Ожидаем сервер. Выход в КПК не отменяет отправку.":resetDraft?"Вернётся контур владений. Нажмите «Сохранить».":error.isEmpty()?"Контур готов. Можно сохранить.":error;
                int y=Y+72;
                for(var line:font.split(Component.literal(text),width-12)){g.drawString(font,line,X+6,y,preview!=null||resetDraft?0xFFD9B86C:0xFFE5A49A,false);y+=10;}
                y+=5;
                for(String hint:List.of("ЛКМ: точка / двигать","ПКМ: удалить точку","СКМ: двигать карту","Колесо: масштаб","Владения не меняются")){
                    if(y+9>bottom())break;g.drawString(font,hint,X+6,y,0xFFAAB4BE,false);y+=11;
                }
            }else if(!validDimension){g.drawWordWrap(font,Component.literal("Контуры стран доступны в Верхнем мире"),X+6,Y+75,width-12,0xFFAAB4BE);}
            else{
                Component tooltip=null;
                var snap=XaeroCountryLayer.snapshot();int count=snap==null?0:snap.countries.size();
                scroll=Math.max(0,Math.min(scroll,Math.max(0,count-rows())));
                g.enableScissor(X,Y+71,X+width,bottom()-3);
                if(count==0)g.drawWordWrap(font,Component.literal("Страны появятся после синхронизации"),X+6,Y+75,width-12,0xFFAAB4BE);
                else for(int i=0;i<rows()&&i+scroll<count;i++){
                    var c=snap.countries.get(i+scroll);int y=Y+75+i*ROW;
                    FlagRenderer.draw(g,c.id(),c.color(),X+7,y,10,8);
                    String name=font.plainSubstrByWidth(c.name(),width-32);g.drawString(font,name,X+24,y,0xFFE7EBEF,false);
                    if(mouseX>=X&&mouseX<X+width&&mouseY>=y-1&&mouseY<y+12)tooltip=Component.literal(c.name()+" · владения: "+c.chunks()+" ч.");
                }
                g.disableScissor();
                if(tooltip!=null)g.renderTooltip(font,tooltip,mouseX,mouseY);
                if(count>rows()){int track=rows()*ROW;int thumb=Math.max(8,track*rows()/count);int top=Y+73+scroll*(track-thumb)/Math.max(1,count-rows());g.fill(X+width-3,top,X+width-1,top+thumb,0xFF71808D);}
            }
        }
        if(!status.isEmpty()&&System.nanoTime()<statusUntil){
            int y=screen.height-53;int w=Math.min(screen.width-20,font.width(status)+16);g.fill((screen.width-w)/2,y-4,(screen.width+w)/2,y+19,0xEE171B20);
            g.drawCenteredString(font,font.plainSubstrByWidth(status,w-12),screen.width/2,y+2,0xFFE7EBEF);
        }
    }
    void renderDraft(GuiGraphics g,MapProjection view){
        if(!editing||resetDraft)return;
        if(preview!=null)MapDrawing.blockOutline(g,preview,view,0xFFD9B86C,screen.width,screen.height);
        else{
            MapDrawing.outline(g,points,view,0xFF101418,4,points.size()>=3);
            MapDrawing.outline(g,points,view,0xFFE5A49A,1.5f,points.size()>=3);
        }
        for(int i=0;i<points.size();i++){
            Point p=view.screen(points.get(i));int x=(int)Math.round(p.x()),y=(int)Math.round(p.z());
            g.fill(x-4,y-4,x+5,y+5,0xED171B20);g.fill(x-2,y-2,x+3,y+3,i==dragging?0xFFFFFFFF:0xFFD9B86C);
        }
    }
    boolean press(double x,double y,int button){
        capturedButtons.remove(button);
        boolean handled=handlePress(x,y,button);
        if(handled&&Minecraft.getInstance().screen==screen)capturedButtons.add(button);
        return handled;
    }
    private boolean handlePress(double x,double y,int button){
        if(inside(x,y)){
            for(MapButton b:buttons)if(b.visible&&b.isMouseOver(x,y)){if(b.mouseClicked(x,y,button))screen.setFocused(b);break;}
            return true;
        }
        if(!editing||y<38||y>=screen.height-34)return false;
        // Xaero's zoom, dimensions, menus and other native controls keep their hitboxes.
        for(var child:screen.children()) {
            if(buttons.contains(child))continue;
            if(child instanceof AbstractWidget widget&&!widget.visible)continue;
            if(child.isMouseOver(x,y))return false;
        }
        if(pending)return true;
        if(button==2){middle=true;return true;}
        if(button!=0&&button!=1)return false;
        MapProjection view=((XaeroMapAccess)screen).frontier$projection();int nearest=nearest(x,y,view);
        if(button==1){if(nearest>=0){dragging=-1;points.remove(nearest);resetDraft=false;rebuild();}return true;}
        if(nearest>=0){dragging=nearest;return true;}
        if(points.size()>=BorderPolygon.MAX_POINTS){notice("Не больше 128 точек в контуре");return true;}
        Point world=view.world(new Point(x,y));points.add(new Point(Math.rint(world.x()),Math.rint(world.z())));resetDraft=false;rebuild();return true;
    }
    boolean release(int button){
        if(!capturedButtons.remove(button))return false;
        if(button==2)middle=false;
        if(button==0||button==1)dragging=-1;
        return true;
    }
    boolean drag(double x,double y,int button,double dx,double dy){
        if(!capturedButtons.contains(button))return false;
        if(!editing||pending)return true;
        if(button==2&&middle){((XaeroMapAccess)screen).frontier$pan(dx,dy);return true;}
        if(button==0&&dragging>=0&&dragging<points.size()){Point world=((XaeroMapAccess)screen).frontier$projection().world(new Point(x,y));points.set(dragging,new Point(Math.rint(world.x()),Math.rint(world.z())));rebuild();return true;}
        return button==0||button==1;
    }
    private int nearest(double x,double y,MapProjection view){int best=-1;double distance=64;for(int i=0;i<points.size();i++){double d=BorderPolygon.distance2(view.screen(points.get(i)),new Point(x,y));if(d<distance){distance=d;best=i;}}return best;}
    boolean scroll(double x,double y,double amount){if(!inside(x,y))return false;if(!editing&&!collapsed)scroll-=Integer.signum((int)Math.signum(amount));return true;}
    boolean key(int key){if(!editing||screen.getFocused() instanceof net.minecraft.client.gui.components.EditBox)return false;if(key==GLFW.GLFW_KEY_ESCAPE){if(pending)screen.onClose();else cancel();return true;}if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){save();return true;}if(key==GLFW.GLFW_KEY_BACKSPACE){if(!pending)undo();return true;}return false;}
    void resetGestures(){capturedButtons.clear();middle=false;dragging=-1;}
    private void notice(String text){status=text;statusUntil=System.nanoTime()+6_000_000_000L;}
    private static final class MapButton extends Button {
        MapButton(int x,int y,int width,String text,Runnable action){super(x,y,width,20,Component.literal(text),b->action.run(),DEFAULT_NARRATION);}
        @Override protected void renderWidget(GuiGraphics g,int mx,int my,float delta){
            int color=!active?0xFF7D8791:0xFFE7EBEF;
            g.fill(getX(),getY(),getX()+getWidth(),getY()+getHeight(),isHoveredOrFocused()&&active?0xFF34404A:0xFF242C34);
            g.fill(getX(),getY()+getHeight()-1,getX()+getWidth(),getY()+getHeight(),isFocused()?0xFFD9B86C:0xFF38414A);
            var font=Minecraft.getInstance().font;String text=font.plainSubstrByWidth(getMessage().getString(),getWidth()-8);
            g.drawCenteredString(font,text,getX()+getWidth()/2,getY()+6,color);
        }
    }
}
