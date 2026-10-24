package dev.frontier.industry.research;

import dev.frontier.industry.IndustryMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=IndustryMod.ID)
public final class ResearchEvents {
    private ResearchEvents(){}
    @SubscribeEvent public static void countryCreated(com.talhanation.recruits.FactionEvent.Created e){ResearchData.get(e.getLevel().getServer()).created(e.getFaction().getStringID());}
    @SubscribeEvent public static void countryDisbanded(com.talhanation.recruits.FactionEvent.Disbanded e){ResearchData.get(e.getLevel().getServer()).disbanded(e.getFaction().getStringID());}
    @SubscribeEvent(priority=EventPriority.HIGHEST)public static void useBlock(PlayerInteractEvent.RightClickBlock e){
        if(e.getEntity() instanceof ServerPlayer p && ResearchPolicy.deployment(e.getItemStack())){
            String denial=ResearchPolicy.denial(p,e.getItemStack());if(!denial.isEmpty()){e.setCanceled(true);e.setCancellationResult(InteractionResult.FAIL);p.displayClientMessage(net.minecraft.network.chat.Component.literal(denial),true);}
        }
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)public static void useItem(PlayerInteractEvent.RightClickItem e){
        if(e.getEntity() instanceof ServerPlayer p && ResearchPolicy.deployment(e.getItemStack())){
            String denial=ResearchPolicy.denial(p,e.getItemStack());if(!denial.isEmpty()){e.setCanceled(true);e.setCancellationResult(InteractionResult.FAIL);p.displayClientMessage(net.minecraft.network.chat.Component.literal(denial),true);}
        }
    }
    @SubscribeEvent public static void started(ServerStartedEvent e){ResearchPolicy.reload();ResearchPolicy.filterRecipes(e.getServer());}
    @SubscribeEvent(priority=EventPriority.HIGHEST)public static void sync(OnDatapackSyncEvent e){ResearchPolicy.reload();ResearchPolicy.filterRecipes(e.getPlayerList().getServer());}
}
