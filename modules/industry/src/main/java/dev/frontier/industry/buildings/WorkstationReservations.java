package dev.frontier.industry.buildings;

import dev.frontier.industry.architecture.FinalIndustryArchitecture;
import dev.frontier.industry.engine.IndustryData;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Future server-side NPC integration; one occupant per documented workstation, no AI spawned. */
public final class WorkstationReservations extends SavedData {
    private final Map<String,UUID> occupants=new HashMap<>();
    public static WorkstationReservations get(MinecraftServer s){return s.overworld().getDataStorage().computeIfAbsent(WorkstationReservations::load,WorkstationReservations::new,"frontier_workstations");}
    public boolean reserve(MinecraftServer server,UUID siteId,String stationId,UUID worker) {
        var site=IndustryData.get(server).sites.get(siteId);
        if(site==null||worker==null||site.constructing||!BuildingOrientation.applies(site.blueprintVersion)||FinalIndustryArchitecture.workplaces(site.type,site.blueprintVersion).stream().noneMatch(w->w.id().equals(stationId)))return false;
        String key=siteId+"/"+stationId;UUID current=occupants.get(key);if(current!=null&&!current.equals(worker))return false;
        occupants.put(key,worker);setDirty();return true;
    }
    public void release(UUID site,String station,UUID worker){if(occupants.remove(site+"/"+station,worker))setDirty();}
    public static WorkstationReservations load(CompoundTag root){var d=new WorkstationReservations();var list=root.getList("Reservations",Tag.TAG_COMPOUND);for(int i=0;i<Math.min(32768,list.size());i++){var t=list.getCompound(i);String key=t.getString("Station");if(key.length()<256&&t.hasUUID("Worker"))d.occupants.put(key,t.getUUID("Worker"));}return d;}
    @Override public CompoundTag save(CompoundTag root){var list=new ListTag();occupants.forEach((key,worker)->{var t=new CompoundTag();t.putString("Station",key);t.putUUID("Worker",worker);list.add(t);});root.put("Reservations",list);return root;}
}
