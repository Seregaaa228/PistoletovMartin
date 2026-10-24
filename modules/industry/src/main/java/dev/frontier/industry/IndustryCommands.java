package dev.frontier.industry;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;

public final class IndustryCommands {
    public static void init() {
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> {
            event.getDispatcher().register(Commands.literal("industryfactory").executes(c -> {
                var player = c.getSource().getPlayerOrException();
                var id = IndustryEngine.nearestOwned(player, 16);
                if (id != null) IndustryMenus.open(player, id);
                else player.sendSystemMessage(Component.literal("Поставь комплект завода/электростанции на свободной площадке. Планшет: Shift+ПКМ соединяет станцию → подстанцию → завод; ПКМ по NPC выбирает рабочего. В интерфейсе выбери роль и назначь. Уголь и материалы положи во входные слоты; ремкомплект удерживай90с рядом с повреждённым объектом."));
                return 1;
            }));
            event.getDispatcher().register(Commands.literal("industrykit").requires(s -> s.hasPermission(2)).executes(c -> {
                var p = c.getSource().getPlayerOrException();
                for (SiteType type : SiteType.values()) give(p, new ItemStack(IndustryContent.deployer(type)));
                give(p, new ItemStack(IndustryContent.preferredTablet())); give(p, new ItemStack(IndustryContent.REPAIR_KIT.get(), 16));
                for (var item : new net.minecraft.world.item.Item[]{Items.COAL, Items.EMERALD, Items.IRON_INGOT, Items.COPPER_INGOT, Items.REDSTONE, Items.GUNPOWDER, Items.GLASS}) give(p, new ItemStack(item, 64));
                p.sendSystemMessage(Component.literal("Промышленный набор получен. Заводы требуют свободную площадку; производство — электросеть, рабочих и реальные материалы."));
                return 1;
            }));
        });
    }
    private static void give(net.minecraft.server.level.ServerPlayer p, ItemStack stack) { if (!p.getInventory().add(stack)) p.drop(stack, false); }
}
