package dev.frontier.industry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class IndustryNet {
    private static final String VERSION = "3";
    private static final java.util.Map<UUID,Integer> LAST_ACTION=new java.util.HashMap<>(),LAST_REFRESH=new java.util.HashMap<>();
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(IndustryMod.ID, "industry"), () -> VERSION, VERSION::equals, VERSION::equals);
    private static final Set<String> ACTIONS = Set.of("refresh", "order", "hire", "assign", "unassign", "dismiss", "repair", "collect", "fuel", "cancel", "staff", "repair_staff", "supply", "construction_staff", "construction_cancel", "research_start", "research_staff", "research_supply");
    public static void init() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStoppedEvent e)->{LAST_ACTION.clear();LAST_REFRESH.clear();});
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e)->{LAST_ACTION.remove(e.getEntity().getUUID());LAST_REFRESH.remove(e.getEntity().getUUID());});
        CHANNEL.registerMessage(0, Action.class, Action::encode, Action::decode, Action::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, View.class, View::encode, View::decode, View::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    public static void request(UUID site, String action, String arg, int value) { CHANNEL.sendToServer(new Action(site, action, arg, value)); }
    public static void sendView(ServerPlayer p, UUID site) {
        if (!IndustryEngine.owns(p, site)) return;
        JsonObject v = IndustryEngine.snapshot(p, site);
        JsonArray recipes = new JsonArray();
        var inventory = IndustryEngine.inventory(p.serverLevel(), site);
        int iconBudget = 131072;
        SiteType type;
        try { type = SiteType.valueOf(v.get("type").getAsString()); } catch (Exception e) { type = null; }
        for (var r : IndustryCatalog.recipes()) if (r.factory() == type && IndustryCatalog.visible(r) && recipes.size() < 512) {
            JsonObject row = new JsonObject(); row.addProperty("id", r.id()); row.addProperty("name", r.title());
            String denial=dev.frontier.industry.research.ResearchPolicy.recipeDenial(p.server,p.getUUID(),r);
            row.addProperty("locked",!denial.isEmpty());row.addProperty("denial",denial);
            row.addProperty("seconds", r.ticks() / 20); row.addProperty("fe", r.fePerTick());
            ItemStack output = r.output();
            var outputId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(output.getItem());
            if (outputId != null) row.addProperty("iconItem", outputId.toString());
            ItemStack display = output.copy();
            if (display.hasTag() && display.getTag().contains("BlockEntityTag", 10)) {
                // Native container icons require EntityType only, not the aircraft inventory/energy state.
                // This copy is display-only; the server recipe retains its fully fuelled output NBT.
                display.getTagElement("BlockEntityTag").remove("Entity");
            }
            String icon = display.save(new net.minecraft.nbt.CompoundTag()).toString();
            // Preserve TaCZ/vehicle identity tags for native item rendering, with a bounded display-only payload.
            if (icon.length() <= 4096 && icon.length() <= iconBudget) { row.addProperty("icon", icon); iconBudget -= icon.length(); }
            row.addProperty("outputCount", output.getCount());
            JsonArray materials = new JsonArray();
            for (ItemStack stack : r.cost()) {
                JsonObject material = new JsonObject();
                var itemId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (itemId != null) material.addProperty("item", itemId.toString());
                material.addProperty("name", stack.getHoverName().getString()); material.addProperty("count", stack.getCount());
                int available = 0;
                if (inventory != null) for (int slot = 0; slot < Math.min(12, inventory.getSlots()); slot++) {
                    ItemStack stored = inventory.getStackInSlot(slot);
                    if (ItemStack.isSameItemSameTags(stack, stored)) available += stored.getCount();
                }
                material.addProperty("available", available); materials.add(material);
            }
            row.add("materials", materials);
            JsonArray cost = new JsonArray(); for (ItemStack stack : r.cost()) cost.add(stack.getCount() + " " + stack.getHoverName().getString()); row.add("cost", cost);
            recipes.add(row);
        }
        v.add("catalog", recipes);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new View(site, v.toString()));
    }
    public record Action(UUID site, String action, String arg, int value) {
        static void encode(Action p, FriendlyByteBuf b) { b.writeUUID(p.site); b.writeUtf(p.action, 64); b.writeUtf(p.arg, 128); b.writeVarInt(p.value); }
        static Action decode(FriendlyByteBuf b) { return new Action(b.readUUID(), b.readUtf(64), b.readUtf(128), b.readVarInt()); }
        static void handle(Action p, Supplier<NetworkEvent.Context> supplier) {
            var ctx = supplier.get(); ctx.enqueueWork(() -> {
                ServerPlayer player = ctx.getSender();
                if (player == null || !ACTIONS.contains(p.action) || p.value < 0 || p.value > 64) return;
                if (!(player.containerMenu instanceof IndustryMenus.FactoryMenu menu) || !menu.site.equals(p.site) || !menu.stillValid(player)) return;
                var rate=p.action.equals("refresh")?LAST_REFRESH:LAST_ACTION;int now=player.server.getTickCount();
                if(now-rate.getOrDefault(player.getUUID(),now-40)<(p.action.equals("refresh")?10:4))return;
                rate.put(player.getUUID(),now);
                String arg = p.arg;
                if (Set.of("assign", "unassign", "dismiss").contains(p.action) && arg.isBlank()) {
                    for (ItemStack stack : new ItemStack[]{player.getMainHandItem(), player.getOffhandItem()}) {
                        if (IndustryContent.isTablet(stack) && stack.hasTag() && stack.getTag().hasUUID("SelectedWorker")) {
                            arg = stack.getTag().getUUID("SelectedWorker").toString(); break;
                        }
                    }
                }
                if (!p.action.equals("refresh")) IndustryEngine.action(player, p.site, p.action, arg, p.value);
                sendView(player, p.site);
            }); ctx.setPacketHandled(true);
        }
    }
    public record View(UUID site, String json) {
        static void encode(View p, FriendlyByteBuf b) { b.writeUUID(p.site); b.writeUtf(p.json, 524288); }
        static View decode(FriendlyByteBuf b) { return new View(b.readUUID(), b.readUtf(524288)); }
        static void handle(View p, Supplier<NetworkEvent.Context> supplier) {
            var ctx = supplier.get(); ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                dev.frontier.industry.client.IndustryClient.view(p.site, JsonParser.parseString(p.json).getAsJsonObject())));
            ctx.setPacketHandled(true);
        }
    }
}
