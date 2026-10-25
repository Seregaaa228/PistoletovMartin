package dev.frontier.nations.map.xaero;

import dev.frontier.nations.flag.FlagLeadership;
import dev.frontier.nations.map.border.CountryEditorAccess;
import com.talhanation.recruits.client.ClientManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.gui.GuiMap;

/** Client entry point shared with the optional Industry PDA, without a module dependency. */
public final class CountryMapClient {
    private CountryMapClient(){}
    /** Called by the PDA's borders button; permission belongs to this map instance only. */
    public static void openEditor(Screen pda){
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null||mc.level==null||pda==null||mc.screen!=pda)return;
        if(!FlagLeadership.isLocalLeader()){message("Изменять контур может только лидер страны");return;}
        if(!Level.OVERWORLD.equals(mc.level.dimension())){message("Контуры стран доступны в Верхнем мире");return;}
        if(!CountryEditorAccess.hasPda(mc.player)){message("Для изменения контура держите КПК в руке");return;}
        MapProcessor processor=processor();if(processor==null)return;
        var world=processor.getMapWorld();var previousCustom=world.getCustomDimensionId();var previousFuture=world.getFutureDimensionId();
        processor.getMapWorld().setCustomDimensionId(Level.OVERWORLD);
        processor.getMapWorld().setFutureDimensionId(Level.OVERWORLD);
        mc.setScreen(new EditorScreen(pda,processor,previousCustom,previousFuture,ClientManager.ownFaction.getStringID()));
    }
    public static void openViewer(){
        Minecraft mc=Minecraft.getInstance();GuiMap map=viewer(mc.screen);if(map!=null)mc.setScreen(map);
    }
    static GuiMap viewer(Screen parent){
        Minecraft mc=Minecraft.getInstance();if(mc.player==null||mc.level==null)return null;
        MapProcessor processor=processor();return processor==null?null:new GuiMap(parent,parent,processor,mc.player);
    }
    static boolean isEditor(GuiMap map){return map instanceof EditorScreen;}
    static boolean canEdit(GuiMap map){
        return map instanceof EditorScreen editor&&!editor.restored&&CountryEditorAccess.hasPda(Minecraft.getInstance().player)
            &&ClientManager.ownFaction!=null&&editor.faction.equals(ClientManager.ownFaction.getStringID());
    }
    static void leaving(GuiMap map,Screen next){
        if(map instanceof EditorScreen editor&&next!=map&&(!(next instanceof xaero.map.gui.ScreenBase)||next instanceof GuiMap)
            &&!(next instanceof dev.frontier.nations.flag.FlagEditorScreen))editor.restoreDimension();
    }
    private static MapProcessor processor(){
        WorldMapSession session=WorldMapSession.getCurrentSession();
        if(session==null||session.getMapProcessor()==null||session.getMapProcessor().getMapWorld()==null){message("Карта Xaero ещё загружается. Попробуйте снова");return null;}
        return session.getMapProcessor();
    }
    private static void message(String text){var player=Minecraft.getInstance().player;if(player!=null)player.displayClientMessage(Component.literal(text),false);}
    private static final class EditorScreen extends GuiMap {
        private final MapProcessor processor;
        private final ResourceKey<Level> previousCustom,previousFuture;
        private final String faction;
        private boolean restored;
        EditorScreen(Screen pda,MapProcessor processor,ResourceKey<Level> previousCustom,ResourceKey<Level> previousFuture,String faction){
            super(pda,pda,processor,Minecraft.getInstance().player);this.processor=processor;this.previousCustom=previousCustom;this.previousFuture=previousFuture;this.faction=faction;
        }
        void restoreDimension(){
            if(restored)return;restored=true;
            var world=processor.getMapWorld();if(world!=null){world.setCustomDimensionId(previousCustom);world.setFutureDimensionId(previousFuture);}
        }
    }
}
