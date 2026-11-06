package dev.frontier.industry.buildings;

import dev.frontier.industry.*;
import dev.frontier.industry.engine.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** A physical access point to the factory's authoritative output stock, never a copied inventory. */
public final class OutputWarehouse extends Block {
    public OutputWarehouse(){super(Properties.of().strength(3,8).sound(SoundType.METAL).noLootTable());}
    @Override public void onPlace(BlockState state,Level level,BlockPos pos,BlockState oldState,boolean moving){
        super.onPlace(state,level,pos,oldState,moving);
        if(!(level instanceof net.minecraft.server.level.ServerLevel server)||oldState.is(state.getBlock())||!state.is(FunctionalBuildings.CONTAINER.get()))return;
        var data=IndustryData.get(server.getServer());var id=IndustryEngine.siteAt(server,pos);var site=id==null?null:data.sites.get(id);
        if(site!=null&&site.type.isFactory()){site.containerInventory(relativeKey(site,pos));data.setDirty();}
    }
    public static boolean accessPoint(BlockState state){return state.getBlock() instanceof OutputWarehouse||state.getBlock() instanceof ProcessEquipment p&&java.util.Set.of("campus_cargo_crate","campus_storage_rack").contains(p.kind);}
    public static boolean allowed(ServerPlayer player,IndustryData.Site site,BlockPos terminal){
        return site!=null&&site.type.isFactory()&&IndustryData.get(player.server).sites.get(site.id)==site
            &&player.isAlive()&&!player.isSpectator()&&IndustryEngine.owns(player,site.id)
            &&site.id.equals(IndustryEngine.siteAt(player.serverLevel(),terminal))
            &&player.distanceToSqr(terminal.getX()+.5,terminal.getY()+.5,terminal.getZ()+.5)<=64
            &&player.serverLevel().hasChunkAt(terminal)&&accessPoint(player.serverLevel().getBlockState(terminal));
    }
    @Override public InteractionResult use(BlockState state,Level level,BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit){
        return open(level,pos,player);
    }
    public static InteractionResult open(Level level,BlockPos pos,Player player){
        if(level.isClientSide)return InteractionResult.SUCCESS;
        if(player instanceof ServerPlayer server){
            var id=IndustryEngine.siteAt(server.serverLevel(),pos);var site=id==null?null:IndustryData.get(server.server).sites.get(id);
            if(allowed(server,site,pos))server.openMenu(new SimpleMenuProvider((window,inventory,p)->menu(window,server,site,pos),Component.translatable("frontier_industry.warehouse.title")));
            else server.displayClientMessage(Component.translatable("message.frontier_industry.denied_terminal"),true);
        }
        return InteractionResult.CONSUME;
    }
    private static final java.util.Set<String> CONTAINER_KINDS = java.util.Set.of("campus_cargo_crate", "campus_storage_rack");
    private static boolean physicalContainer(BlockState state){
        return state.is(FunctionalBuildings.CONTAINER.get()) || state.getBlock() instanceof ProcessEquipment p && CONTAINER_KINDS.contains(p.kind);
    }
    private static BlockPos containerPosition(IndustryData.Site site,long key){
        return site.origin.offset(BuildingOrientation.rotate(BlockPos.of(key),site.blueprintVersion));
    }
    private static boolean present(net.minecraft.server.level.ServerLevel level,IndustryData.Site site,long key){
        BlockPos pos=containerPosition(site,key);return level!=null&&level.hasChunkAt(pos)&&physicalContainer(level.getBlockState(pos));
    }
    public static long relativeKey(IndustryData.Site site, BlockPos worldPos) {
        BlockPos local = worldPos.subtract(site.origin);
        BlockPos unrotated = BuildingOrientation.rotate(local, 6 + 100 * ((4 - BuildingOrientation.quarter(site.blueprintVersion)) % 4));
        return unrotated.asLong();
    }
    public static net.minecraftforge.items.ItemStackHandler inventory(IndustryData.Site site, BlockPos worldPos) {
        ensureInventories(site);
        return site.containerInventory(relativeKey(site, worldPos));
    }
    /** Container keys per building type and blueprint version: the blueprint map is costly to build and fixed at runtime. */
    private static final java.util.Map<String, long[]> CONTAINER_KEYS = new java.util.concurrent.ConcurrentHashMap<>();
    private static long[] containerKeys(IndustryData.Site site) {
        return CONTAINER_KEYS.computeIfAbsent(site.type.name() + "#" + site.blueprintVersion, key -> {
            var keys = new java.util.ArrayList<Long>();
            for (var entry : dev.frontier.industry.engine.Blueprint.blocks(site.type, BlockPos.ZERO, site.blueprintVersion).entrySet()) {
                Block block = entry.getValue().getBlock();
                boolean physical = block == FunctionalBuildings.CONTAINER.get()
                    || block instanceof ProcessEquipment equipment && CONTAINER_KINDS.contains(equipment.kind);
                if (physical) keys.add(BuildingOrientation.rotate(entry.getKey(), 6 + 100 * ((4 - BuildingOrientation.quarter(site.blueprintVersion)) % 4)).asLong());
            }
            return keys.stream().mapToLong(Long::longValue).toArray();
        });
    }
    public static void ensureInventories(IndustryData.Site site) {
        for (long key : containerKeys(site)) site.containerInventory(key);
    }
    public static boolean putOutputs(IndustryData.Site site, ItemStack output) {
        ensureInventories(site);
        if (site.containerInventories.isEmpty()) return StockTransactions.putOutputs(site.inventory, output);
        return StockTransactions.distributedOutputs(new java.util.ArrayList<>(site.containerInventories.values()), output, true);
    }
    public static boolean outputFits(IndustryData.Site site, ItemStack output) {
        ensureInventories(site);
        if (site.containerInventories.isEmpty()) return StockTransactions.outputFits(site.inventory, output);
        return StockTransactions.distributedOutputs(new java.util.ArrayList<>(site.containerInventories.values()), output, false);
    }
    public static boolean putOutputs(net.minecraft.server.level.ServerLevel level,IndustryData.Site site,ItemStack output){
        ensureInventories(site);
        if(site.containerInventories.isEmpty())return StockTransactions.putOutputs(site.inventory,output);
        var inventories=new java.util.ArrayList<net.minecraftforge.items.ItemStackHandler>();
        for(var entry:site.containerInventories.entrySet())if(present(level,site,entry.getKey()))inventories.add(entry.getValue());
        return StockTransactions.distributedOutputs(inventories,output,true);
    }
    public static boolean outputFits(net.minecraft.server.level.ServerLevel level,IndustryData.Site site,ItemStack output){
        ensureInventories(site);
        if(site.containerInventories.isEmpty())return StockTransactions.outputFits(site.inventory,output);
        var inventories=new java.util.ArrayList<net.minecraftforge.items.ItemStackHandler>();
        for(var entry:site.containerInventories.entrySet())if(present(level,site,entry.getKey()))inventories.add(entry.getValue());
        return StockTransactions.distributedOutputs(inventories,output,false);
    }
    public static Container storage(ServerPlayer player,IndustryData.Site site,BlockPos terminal){return new OutputStock(player,site,terminal.immutable());}
    public static ChestMenu menu(int id,ServerPlayer player,IndustryData.Site site,BlockPos terminal){
        if(player.serverLevel().getBlockState(terminal).is(FunctionalBuildings.WAREHOUSE.get()))
            return new WarehouseOverviewMenu(id,player,site,terminal);
        return new OutputMenu(id,player,storage(player,site,terminal));
    }
    /** Five physical containers per page, plus page controls in the last row. */
    private static final class WarehouseOverviewMenu extends ChestMenu {
        private final OverviewStock overview;
        WarehouseOverviewMenu(int id,ServerPlayer player,IndustryData.Site site,BlockPos terminal){
            this(id,player,new OverviewStock(player,site,terminal));
        }
        private WarehouseOverviewMenu(int id,ServerPlayer player,OverviewStock stock){
            super(MenuType.GENERIC_9x6,id,player.getInventory(),stock,6);overview=stock;
            for(int i=0;i<54;i++){
                final int index=i;var old=slots.get(i);
                var replacement=new Slot(stock,i,old.x,old.y){
                    @Override public boolean mayPlace(ItemStack stack){return false;}
                    @Override public boolean mayPickup(Player p){return index<45&&stock.stillValid(p);}
                };replacement.index=old.index;slots.set(i,replacement);
            }
        }
        @Override public void clicked(int slot,int button,ClickType type,Player player){
            if(!stillValid(player))return;
            if(slot==45||slot==53){overview.changePage(slot==45?-1:1);broadcastChanges();return;}
            if(slot>=45&&slot<54)return;
            super.clicked(slot,button,type,player);
        }
        @Override public ItemStack quickMoveStack(Player player,int slot){
            return stillValid(player)&&slot<45?super.quickMoveStack(player,slot):ItemStack.EMPTY;
        }
    }
    private static final class OverviewStock implements Container {
        final ServerPlayer owner;final IndustryData.Site site;final BlockPos terminal;
        final java.util.List<Long> keys;final int legacyRows;int page;
        OverviewStock(ServerPlayer player,IndustryData.Site site,BlockPos terminal){
            owner=player;this.site=site;this.terminal=terminal.immutable();ensureInventories(site);
            keys=new java.util.ArrayList<>();
            for(var entry:site.containerInventories.entrySet()){
                boolean containsItems=false;for(int i=0;i<entry.getValue().getSlots();i++)containsItems|=!entry.getValue().getStackInSlot(i).isEmpty();
                // Keep contents of removed/unloaded containers recoverable; do not present absent empty cells as capacity.
                if(containsItems||present(player.serverLevel(),site,entry.getKey()))keys.add(entry.getKey());
            }
            boolean legacy=false;for(int i=IndustryRules.INPUTS;i<IndustryRules.SLOTS;i++)legacy|=!site.inventory.getStackInSlot(i).isEmpty();
            legacyRows=legacy?2:0;
        }
        int pages(){return Math.max(1,(keys.size()+legacyRows+4)/5);}
        void changePage(int amount){page=Math.max(0,Math.min(pages()-1,page+amount));}
        private net.minecraftforge.items.ItemStackHandler handler(int slot){
            int i=page*5+slot/9-legacyRows;return slot>=0&&slot<45&&i>=0&&i<keys.size()?site.containerInventories.get(keys.get(i)):null;
        }
        private int legacySlot(int slot){int row=page*5+slot/9;int i=IndustryRules.INPUTS+row*9+slot%9;return slot>=0&&slot<45&&row<legacyRows&&i<IndustryRules.SLOTS?i:-1;}
        public int getContainerSize(){return 54;}
        public boolean isEmpty(){for(int i=0;i<45;i++)if(!getItem(i).isEmpty())return false;return true;}
        public ItemStack getItem(int slot){
            if(!stillValid(owner))return ItemStack.EMPTY;
            if(slot==45||slot==53){var item=new ItemStack(Items.ARROW);item.setHoverName(Component.literal((slot==45?"Предыдущая":"Следующая")+" страница • "+(page+1)+"/"+pages()));return item;}
            int legacy=legacySlot(slot);if(legacy>=0)return site.inventory.getStackInSlot(legacy);
            var h=handler(slot);return h==null?ItemStack.EMPTY:h.getStackInSlot(slot%9);
        }
        public ItemStack removeItem(int slot,int count){if(count<=0||!stillValid(owner))return ItemStack.EMPTY;int legacy=legacySlot(slot);var h=legacy>=0?site.inventory:handler(slot);if(h==null)return ItemStack.EMPTY;var item=h.extractItem(legacy>=0?legacy:slot%9,count,false);if(!item.isEmpty())setChanged();return item;}
        public ItemStack removeItemNoUpdate(int slot){return removeItem(slot,64);}
        public void setItem(int slot,ItemStack stack){if(!stillValid(owner))return;int legacy=legacySlot(slot);var h=legacy>=0?site.inventory:handler(slot);if(h!=null){h.setStackInSlot(legacy>=0?legacy:slot%9,stack);setChanged();}}
        public void setChanged(){IndustryData.get(owner.server).setDirty();}
        public boolean stillValid(Player p){return p==owner&&allowed(owner,site,terminal)&&owner.serverLevel().getBlockState(terminal).is(FunctionalBuildings.WAREHOUSE.get());}
        public boolean canPlaceItem(int slot,ItemStack stack){return false;}
        public void clearContent(){}
    }
    private static final class OutputMenu extends ChestMenu {
        OutputMenu(int id,ServerPlayer player,Container stock){
            super(MenuType.GENERIC_9x2,id,player.getInventory(),stock,2);
            // Vanilla chest slots do not consult Container.canPlaceItem. Lock the server slots too.
            for(int i=0;i<18;i++){
                var previous=slots.get(i);var locked=new Slot(stock,i,previous.x,previous.y){
                    @Override public boolean mayPlace(ItemStack stack){return false;}
                    @Override public boolean mayPickup(Player p){return stock.stillValid(p);}
                };locked.index=previous.index;slots.set(i,locked);
            }
        }
        // Output stock is take-only: shift-clicks from the player's inventory would merge into it (merge pass skips mayPlace).
        @Override public ItemStack quickMoveStack(Player p,int slot){return stillValid(p)&&slot<18?super.quickMoveStack(p,slot):ItemStack.EMPTY;}
    }
    private static final class OutputStock implements Container {
        final ServerPlayer owner;final IndustryData.Site site;final BlockPos terminal;
        OutputStock(ServerPlayer p,IndustryData.Site s,BlockPos t){owner=p;site=s;terminal=t;}
        // The standard two-row chest screen has nine columns. Last three columns are unused.
        int mapped(int slot){return slot>=0&&slot<9?slot:-1;}
        public int getContainerSize(){return 18;}
        public boolean isEmpty(){for(int i=0;i<18;i++)if(!getItem(i).isEmpty())return false;return true;}
        private net.minecraftforge.items.ItemStackHandler handler(){return OutputWarehouse.inventory(site,terminal);}
        public ItemStack getItem(int slot){int i=mapped(slot);return i<0||!stillValid(owner)?ItemStack.EMPTY:handler().getStackInSlot(i);}
        public ItemStack removeItem(int slot,int count){int i=mapped(slot);if(i<0||count<=0||!stillValid(owner))return ItemStack.EMPTY;var moved=handler().extractItem(i,count,false);if(!moved.isEmpty())setChanged();return moved;}
        public ItemStack removeItemNoUpdate(int slot){return removeItem(slot,64);}
        public void setItem(int slot,ItemStack stack){int i=mapped(slot);if(i>=0&&stillValid(owner)){handler().setStackInSlot(i,stack);setChanged();}}
        public void setChanged(){IndustryData.get(owner.server).setDirty();}
        public boolean stillValid(Player p){
            if(p!=owner||!allowed(owner,site,terminal))return false;
            if(CONTAINER_KINDS.stream().anyMatch(kind->terminalState().getBlock() instanceof ProcessEquipment equipment&&equipment.kind.equals(kind)))return true;
            return terminalState().getBlock() instanceof OutputWarehouse;
        }
        private BlockState terminalState(){return owner.serverLevel().getBlockState(terminal);}
        public boolean canPlaceItem(int slot,ItemStack stack){return false;}
        public void clearContent(){/* Closing/destroying an access point must not delete paid production. */}
    }
}
