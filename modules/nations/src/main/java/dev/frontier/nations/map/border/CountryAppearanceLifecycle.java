package dev.frontier.nations.map.border;

import com.talhanation.recruits.FactionEvent;
import com.talhanation.recruits.FactionEvents;
import dev.frontier.nations.flag.FlagNet;
import dev.frontier.nations.flag.NationFlags;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;

/** Drop cosmetic records when a native faction ends, or its ID is reused for a new faction. */
public final class CountryAppearanceLifecycle {
    private CountryAppearanceLifecycle(){}
    public static void init(){
        MinecraftForge.EVENT_BUS.addListener((FactionEvent.Disbanded event)->clear(event));
        MinecraftForge.EVENT_BUS.addListener((FactionEvent.Created event)->clear(event));
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, CountryAppearanceLifecycle::started);
    }
    private static void started(ServerStartedEvent event){
        // Pinned Recruits loads its complete Overworld team store synchronously in ServerStarting.
        // Wait for ServerStarted and the Nations bootstrap before pruning historical orphan IDs.
        var manager=FactionEvents.recruitsFactionManager;if(manager==null)return;
        var ids=new java.util.HashSet<String>();
        for(var faction:manager.getFactions())ids.add(faction.getStringID());
        NationFlags.get(event.getServer()).retainFactions(ids);
        NationBorders.get(event.getServer()).retainFactions(ids);
    }
    private static void clear(FactionEvent event){
        if(event.getLevel()==null||event.getFaction()==null)return;
        String id=event.getFaction().getStringID();if(id==null||id.isBlank())return;
        var server=event.getLevel().getServer();BorderNet.removeFaction(server,id);FlagNet.removeFaction(server,id);
    }
}
