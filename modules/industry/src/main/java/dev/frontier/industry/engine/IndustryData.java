package dev.frontier.industry.engine;

import dev.frontier.industry.SiteType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.items.ItemStackHandler;

/** All ownership, inventories, reserved costs and transfer custody live in one save transaction. */
public final class IndustryData extends SavedData {
    public final Map<UUID, Site> sites = new LinkedHashMap<>();
    public final Map<UUID, Worker> workers = new LinkedHashMap<>();
    public static IndustryData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(IndustryData::load, IndustryData::new, "frontier_industry");
    }
    public static IndustryData load(CompoundTag root) {
        IndustryData data = new IndustryData();
        ListTag sites = root.getList("Sites", Tag.TAG_COMPOUND);
        for (int n = 0; n < Math.min(sites.size(), 2048); n++) {
            try {
                CompoundTag tag = sites.getCompound(n);
                Site site = new Site(data, tag.getUUID("Id"), tag.getUUID("Owner"),
                    SiteType.valueOf(tag.getString("Type")), new ResourceLocation(tag.getString("Dimension")), BlockPos.of(tag.getLong("Origin")));
                // Missing tags are existing 0.2 buildings; never expand or reinterpret their footprint.
                site.blueprintVersion = Blueprint.savedVersion(tag.getInt("BlueprintVersion"));
                site.team = tag.getString("Team");
                site.constructing = tag.getBoolean("Constructing");
                site.constructionTicks = clamp(tag.getInt("ConstructionTicks"),0,72000);
                site.constructionDuration = clamp(tag.getInt("ConstructionDuration"),0,72000);
                site.constructionCursor = clamp(tag.getInt("ConstructionCursor"),0,100000);
                site.kitPaid = tag.getBoolean("ConstructionKitPaid");
                if (tag.hasUUID("Upstream")) site.upstream = tag.getUUID("Upstream");
                site.energy = clamp(tag.getInt("Energy"), 0, IndustryRules.BUFFER);
                site.burn = clamp(tag.getInt("Burn"), 0, 1600);
                site.repairRequested = tag.getBoolean("RepairRequested");
                site.repairProgress = clamp(tag.getInt("RepairProgress"), 0, IndustryRules.REPAIR_TICKS);
                site.repairKit = tag.getBoolean("RepairKit");
                // A damaged/older Size field must never change the 24-slot public contract.
                CompoundTag inventory = tag.getCompound("Inventory").copy();
                inventory.putInt("Size", IndustryRules.SLOTS);
                site.inventory.deserializeNBT(inventory);
                ListTag orders = tag.getList("Orders", Tag.TAG_COMPOUND);
                for (int j = 0; j < Math.min(orders.size(), IndustryRules.MAX_QUEUE); j++) site.orders.add(Order.load(orders.getCompound(j)));
                data.sites.put(site.id, site);
            } catch (IllegalArgumentException ignored) {
                // Invalid entries cannot grant entities, stock, power or blocks.
            }
        }
        ListTag workers = root.getList("Workers", Tag.TAG_COMPOUND);
        for (int n = 0; n < Math.min(workers.size(), 131072); n++) {
            try {
                CompoundTag tag = workers.getCompound(n);
                Worker worker = new Worker(tag.getUUID("Id"), tag.getUUID("Owner"), tag.getString("Role"), new ResourceLocation(tag.getString("Dimension")));
                if (tag.hasUUID("Site")) worker.site = tag.getUUID("Site");
                if (tag.hasUUID("SourceId")) worker.sourceId = tag.getUUID("SourceId");
                if (tag.contains("Source", Tag.TAG_COMPOUND)) worker.source = tag.getCompound("Source").copy();
                worker.dead = tag.getBoolean("Dead");
                worker.returned = tag.getBoolean("Returned");
                worker.returning = tag.getBoolean("Returning");
                worker.nativeDebited = tag.getBoolean("NativeDebited"); worker.nativeCredited = tag.getBoolean("NativeCredited");
                worker.nativeFactionDebited = tag.getBoolean("NativeFactionDebited"); worker.nativeGroupRemoved = tag.getBoolean("NativeGroupRemoved");
                worker.nativeTeam = tag.getString("NativeTeam");
                if (tag.hasUUID("NativeGroup")) worker.nativeGroup = tag.getUUID("NativeGroup");
                data.workers.put(worker.id, worker);
            } catch (IllegalArgumentException ignored) { }
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag root) {
        root.putInt("Version", 5);
        ListTag sitesTag = new ListTag();
        for (Site site : sites.values()) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", site.id); tag.putUUID("Owner", site.owner);
            tag.putString("Type", site.type.name()); tag.putString("Dimension", site.dimension.toString());
            tag.putInt("BlueprintVersion", site.blueprintVersion);
            tag.putBoolean("Constructing",site.constructing);
            tag.putInt("ConstructionTicks",site.constructionTicks);tag.putInt("ConstructionDuration",site.constructionDuration);
            tag.putInt("ConstructionCursor",site.constructionCursor);tag.putBoolean("ConstructionKitPaid",site.kitPaid);
            tag.putString("Team", site.team); tag.putLong("Origin", site.origin.asLong());
            if (site.upstream != null) tag.putUUID("Upstream", site.upstream);
            tag.putInt("Energy", site.energy); tag.putInt("Burn", site.burn);
            tag.putBoolean("RepairRequested", site.repairRequested); tag.putInt("RepairProgress", site.repairProgress); tag.putBoolean("RepairKit", site.repairKit);
            tag.put("Inventory", site.inventory.serializeNBT());
            ListTag orders = new ListTag();
            for (Order order : site.orders) orders.add(order.save());
            tag.put("Orders", orders); sitesTag.add(tag);
        }
        root.put("Sites", sitesTag);
        ListTag workersTag = new ListTag();
        for (Worker worker : workers.values()) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", worker.id); tag.putUUID("Owner", worker.owner); tag.putString("Role", worker.role);
            tag.putString("Dimension", worker.dimension.toString());
            if (worker.site != null) tag.putUUID("Site", worker.site);
            if (worker.sourceId != null) tag.putUUID("SourceId", worker.sourceId);
            if (worker.source != null) tag.put("Source", worker.source.copy());
            tag.putBoolean("Dead", worker.dead); tag.putBoolean("Returned", worker.returned); tag.putBoolean("Returning", worker.returning);
            tag.putBoolean("NativeDebited", worker.nativeDebited); tag.putBoolean("NativeCredited", worker.nativeCredited);
            tag.putBoolean("NativeFactionDebited", worker.nativeFactionDebited); tag.putBoolean("NativeGroupRemoved", worker.nativeGroupRemoved);
            tag.putString("NativeTeam", worker.nativeTeam); if (worker.nativeGroup != null) tag.putUUID("NativeGroup", worker.nativeGroup);
            workersTag.add(tag);
        }
        root.put("Workers", workersTag);
        return root;
    }
    public static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    public static final class Site {
        public final UUID id, owner;
        public final SiteType type;
        public final ResourceLocation dimension;
        public final BlockPos origin;
        public final ItemStackHandler inventory;
        public final Deque<Order> orders = new ArrayDeque<>();
        public String team = "", status = "Ожидание";
        public UUID upstream;
        public int energy, burn, matching, total, repairProgress, liveProduction;
        public int blueprintVersion = Blueprint.LEGACY_VERSION;
        public boolean constructing, kitPaid;
        public int constructionTicks, constructionDuration, constructionCursor;
        public boolean machineryIntact = true;
        public boolean loaded, controller, powered, repairRequested, repairKit;
        public Site(IndustryData data, UUID id, UUID owner, SiteType type, ResourceLocation dimension, BlockPos origin) {
            this.id = id; this.owner = owner; this.type = type; this.dimension = dimension; this.origin = origin.immutable();
            this.inventory = new ItemStackHandler(IndustryRules.SLOTS) {
                @Override protected void onContentsChanged(int slot) { data.setDirty(); }
                @Override public boolean isItemValid(int slot, ItemStack stack) { return slot < IndustryRules.INPUTS; }
                @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                    return slot < IndustryRules.INPUTS ? super.insertItem(slot, stack, simulate) : stack;
                }
            };
        }
        public boolean functional() { return !constructing && (blueprintVersion == Blueprint.LEGACY_VERSION || machineryIntact) && IndustryRules.functional(matching, total, controller, loaded); }
    }
    public static final class Order {
        public final String recipe;
        public int quantity, progress;
        public boolean reserved;
        public final List<ItemStack> paid = new ArrayList<>();
        public Order(String recipe, int quantity) { this.recipe = recipe; this.quantity = clamp(quantity, 1, 64); }
        CompoundTag save() {
            CompoundTag tag = new CompoundTag(); tag.putString("Recipe", recipe); tag.putInt("Quantity", quantity);
            tag.putInt("Progress", progress); tag.putBoolean("Reserved", reserved);
            ListTag costs = new ListTag(); for (ItemStack stack : paid) costs.add(stack.save(new CompoundTag()));
            tag.put("Paid", costs); return tag;
        }
        static Order load(CompoundTag tag) {
            Order order = new Order(tag.getString("Recipe"), tag.getInt("Quantity"));
            order.progress = clamp(tag.getInt("Progress"), 0, 72000); order.reserved = tag.getBoolean("Reserved");
            ListTag costs = tag.getList("Paid", Tag.TAG_COMPOUND);
            for (int n = 0; n < Math.min(costs.size(), 128); n++) { ItemStack stack = ItemStack.of(costs.getCompound(n)); if (!stack.isEmpty()) order.paid.add(stack); }
            return order;
        }
    }
    public static final class Worker {
        public final UUID id, owner;
        public String role;
        public ResourceLocation dimension;
        public UUID site, sourceId, nativeGroup;
        public CompoundTag source;
        public String nativeTeam = "";
        public boolean dead, returning, returned, nativeDebited, nativeCredited, nativeFactionDebited, nativeGroupRemoved;
        public Worker(UUID id, UUID owner, String role, ResourceLocation dimension) {
            this.id = id; this.owner = owner; this.role = role; this.dimension = dimension;
        }
        public boolean active() { return !dead && !returned && (!transferred() || nativeDebited && !nativeCredited); }
        public boolean transferred() { return sourceId != null && source != null; }
    }
}
