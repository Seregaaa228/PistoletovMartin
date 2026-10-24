package dev.frontier.industry.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.frontier.industry.*;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/** Choose an actual item and produce it. Inventory and specialist controls have their own pages. */
public final class FactoryScreen extends AbstractContainerScreen<IndustryMenus.FactoryMenu> {
    private static final int INK = 0xEAF1F8, MUTED = 0xA2B0BF, GREEN = 0x8AD5AE, GOLD = 0xF0CA82, RED = 0xF09A91;
    private static final int PRODUCTION = 0, STORAGE = 1, MANAGEMENT = 2, RESEARCH = 3, CONSTRUCTION = 4, ROW_Y = 90, ROW_HEIGHT = 21;
    private final FactoryUiState ui = new FactoryUiState();
    private EditBox query;
    private Button produce, supply;
    private int page, refreshTicks, role, researchIndex, researchOffset;
    private boolean advanced;
    private String filter = "", selectedWorker = "";
    private JsonObject cachedView;
    private final Map<String, ItemStack> iconCache = new LinkedHashMap<>();
    private List<Product> catalog = List.of(), filtered = List.of();
    private record Material(ItemStack icon, String name, int count, int available) { }
    private record Product(String id, String name, ItemStack icon, List<Material> materials, int seconds, int fe, int outputCount, String denial) { }

    public FactoryScreen(IndustryMenus.FactoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 356; imageHeight = 244; inventoryLabelY = 131;
        page = menu.type==SiteType.RESEARCH_CENTER?RESEARCH:menu.type.isFactory() ? PRODUCTION : MANAGEMENT; role = menu.type.isFactory() ? 0 : 1;
    }
    @Override protected void init() { super.init(); readView(); rebuild(); }
    private void rebuild() {
        if(constructing()&&page!=STORAGE)page=CONSTRUCTION;
        clearWidgets(); query = null; produce = null; supply = null; menu.showStorage(page == STORAGE);
        button(288, 7, 56, "← КПК", OperationsScreen::open);
        if (menu.type==SiteType.RESEARCH_CENTER){
            tab(12,106,constructing()?"Стройка":"Исследования",constructing()?CONSTRUCTION:RESEARCH);tab(123,93,"Склад",STORAGE);tab(221,123,"Управление",MANAGEMENT);
        } else if (menu.type.isFactory()) {
            tab(12,106,constructing()?"Стройка":"Производство",constructing()?CONSTRUCTION:PRODUCTION); tab(123, 93, "Склад", STORAGE); tab(221, 123, "Управление", MANAGEMENT);
        } else { tab(12,163,constructing()?"Стройка":"Управление",constructing()?CONSTRUCTION:MANAGEMENT); tab(181, 163, "Склад", STORAGE); }
        if(page==CONSTRUCTION){
            button(12,102,332,"Назначить строителя / нанять за 4 изумруда",()->send("construction_staff","",1));
            button(12,146,158,"Ремонт этапов",()->send("repair","",1));
            button(184,146,160,"Склад ремнаборов",()->changePage(STORAGE));
            button(12,204,332,"Отменить строительство",()->send("construction_cancel","",1));
        } else if(page==RESEARCH){
            button(12,66,40,"↑",()->{researchOffset=Math.max(0,researchOffset-4);rebuild();});
            button(304,66,40,"↓",()->{researchOffset=Math.min(4,researchOffset+4);rebuild();});
            button(12,198,158,"+ Учёный",()->send("research_staff","",1));
            button(184,198,160,"Внести материалы",()->send("research_supply",selectedTechnology(),1));
            button(12,220,332,"Начать исследование",()->send("research_start",selectedTechnology(),1));
        } else if (page == PRODUCTION) {
            query = new EditBox(font, leftPos + 12, topPos + 66, 308, 18, Component.literal("Поиск изделия"));
            query.setMaxLength(128); query.setHint(Component.literal("Поиск по названию…")); query.setValue(filter);
            query.setResponder(text -> { filter = text; ui.resetScroll(); filterProducts(); }); addRenderableWidget(query);
            button(324, 66, 20, "×", () -> query.setValue(""));
            button(12, 198, 22, "−", () -> ui.quantity(ui.quantity() - 1));
            button(86, 198, 22, "+", () -> ui.quantity(ui.quantity() + 1));
            button(116, 198, 48, "×1", () -> ui.quantity(1));
            button(169, 198, 48, "×8", () -> ui.quantity(8));
            button(222, 198, 48, "×64", () -> ui.quantity(64));
            button(276, 198, 32, "↑", () -> ui.scroll(-4, filtered.size()));
            button(312, 198, 32, "↓", () -> ui.scroll(4, filtered.size()));
            produce = button(12, 220, 332, "Произвести", this::order);
        } else if (page == STORAGE) {
            if (menu.type.isFactory()) {
                button(12, 222, 158, "Забрать продукцию", () -> send("collect", "", 0));
                supply = button(176, 222, 168, "Материалы из инвентаря →", () -> {
                    if (!ui.selected().isBlank()) send("supply", ui.selected(), ui.quantity());
                });
                supply.active = !ui.selected().isBlank();
            } else if (menu.type == SiteType.POWER_STATION) button(176, 222, 168, "Уголь из инвентаря →", () -> send("fuel", "", 0));
        } else if (advanced) {
            button(12, 68, 332, "← Простое управление", () -> { advanced = false; rebuild(); });
            button(12, 114, 158, "Выбрать рабочего →", this::nextWorker);
            button(184, 114, 160, roleLabel(), () -> { role = (role + 1) % 5; rebuild(); });
            button(12, 140, 158, "Назначить объекту", () -> send("assign", selectedWorker, role));
            button(184, 140, 160, "Снять с объекта", () -> send("unassign", selectedWorker, 0));
            button(12, 166, 158, "Уволить / вернуть бойца", () -> send("dismiss", selectedWorker, 0));
            button(184, 166, 160, "Отменить первый заказ", () -> send("cancel", "", 0));
        } else {
            if (menu.type.isFactory()) {
                button(12, 92, 158, "+ Рабочий", () -> send("staff", "", 1));
                button(184, 92, 160, "+ Ремонтник", () -> send("repair_staff", "", 1));
            } else button(12,92,332,menu.type==SiteType.RESEARCH_CENTER?"+ Учёный":"+ Электрик для сети и ремонта",()->send("staff","",1));
            button(12, 137, 158, "Починить объект", () -> send("repair", "", 1));
            if (menu.type.isFactory()) button(184, 137, 160, "Забрать продукцию", () -> send("collect", "", 0));
            else button(184, 137, 160, "Склад и ремнаборы", () -> changePage(STORAGE));
            if (menu.type == SiteType.POWER_STATION) button(12, 182, 332, "Загрузить уголь из инвентаря", () -> send("fuel", "", 0));
            else if (menu.type.isFactory()) button(12, 182, 332, "Открыть склад материалов", () -> changePage(STORAGE));
            button(12, 220, 332, "Рабочие поимённо / дополнительные действия…", () -> { advanced = true; chooseWorker(); rebuild(); });
        }
    }
    private void tab(int x, int width, String title, int target) { button(x, 43, width, title, () -> changePage(target)).active = page != target; }
    private void changePage(int target) { if (query != null) filter = query.getValue(); page = target; rebuild(); }
    private Button button(int x, int y, int width, String label, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(leftPos + x, topPos + y, width, 18).build());
    }
    private void send(String action, String arg, int value) { IndustryNet.request(menu.site, action, arg, value); }
    private void order() { if (!ui.selected().isBlank()) { send("order", ui.selected(), ui.quantity()); setFocused(null); if (query != null) query.setFocused(false); } }
    @Override protected void containerTick() {
        super.containerTick(); readView();
        if (++refreshTicks % 20 == 0) send("refresh", "", 0);
        if (supply != null) supply.active = !ui.selected().isBlank();
        if (produce != null) {
            Product item = selected(); produce.active = item != null && item.denial().isEmpty() && !constructing();
            produce.setMessage(Component.literal(item == null ? "Выберите изделие" : !item.denial().isEmpty()?font.plainSubstrByWidth(item.denial(),320):"Произвести: " + font.plainSubstrByWidth(item.name(), 210) + " ×" + (ui.quantity() * item.outputCount())));
        }
    }
    private void readView() {
        JsonObject view = IndustryClient.state(menu.site);
        boolean oldConstructing=constructing();
        if (cachedView == view) return;
        cachedView = view;
        List<Product> rows = new ArrayList<>();
        for (var entry : array(view, "catalog")) if (entry.isJsonObject()) {
            JsonObject row = entry.getAsJsonObject(); ItemStack icon = icon(row);
            String name = text(row, "name", "Изделие").replace('_', ' ');
            if (!icon.isEmpty()) {
                String nativeName = icon.getHoverName().getString();
                if (!nativeName.startsWith("item.") && !nativeName.startsWith("block.") && !nativeName.isBlank()) name = nativeName;
            }
            List<Material> costs = new ArrayList<>();
            for (var raw : array(row, "materials")) if (raw.isJsonObject()) {
                JsonObject cost = raw.getAsJsonObject(); ItemStack item = item(text(cost, "item", "minecraft:air"));
                costs.add(new Material(item, item.isEmpty() ? text(cost, "name", "Материал") : item.getHoverName().getString(), number(cost, "count", 0), number(cost, "available", 0)));
            }
            rows.add(new Product(text(row, "id", ""), name, icon, List.copyOf(costs), number(row, "seconds", 0), number(row, "fe", 0), Math.max(1, number(row, "outputCount", 1)),text(row,"denial","")));
        }
        catalog = List.copyOf(rows); filterProducts(); chooseWorker();
        if(constructing()!=oldConstructing){page=constructing()?CONSTRUCTION:menu.type==SiteType.RESEARCH_CENTER?RESEARCH:menu.type.isFactory()?PRODUCTION:MANAGEMENT;if(minecraft!=null&&font!=null)rebuild();}
    }
    private void filterProducts() {
        String needle = IndustryCatalog.normalize(filter);
        filtered = catalog.stream().filter(p -> needle.isBlank() || IndustryCatalog.normalize(p.name()).contains(needle) || IndustryCatalog.normalize(p.id()).contains(needle)).toList();
        ui.reconcile(filtered.stream().map(Product::id).toList());
    }
    private Product selected() { return filtered.stream().filter(p -> p.id().equals(ui.selected())).findFirst().orElse(null); }
    private List<JsonObject> workers() {
        List<JsonObject> rows = new ArrayList<>();
        for (var entry : array(state(), "workersAvailable")) if (entry.isJsonObject()) rows.add(entry.getAsJsonObject());
        return rows;
    }
    private void chooseWorker() { var rows = workers(); if (rows.stream().noneMatch(r -> text(r, "id", "").equals(selectedWorker))) selectedWorker = rows.isEmpty() ? "" : text(rows.get(0), "id", ""); }
    private void nextWorker() { var rows = workers(); if (rows.isEmpty()) return; int index = -1; for (int i = 0; i < rows.size(); i++) if (text(rows.get(i), "id", "").equals(selectedWorker)) index = i; selectedWorker = text(rows.get((index + 1) % rows.size()), "id", ""); }
    private String roleLabel() { return new String[]{"Роль: производство", "Роль: электрик", "Роль: ремонт завода", "Роль: учёный", "Роль: строитель"}[role]; }
    private JsonObject state() { return cachedView == null ? new JsonObject() : cachedView; }
    private static String text(JsonObject object, String key, String fallback) { try { return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback; } catch (RuntimeException ignored) { return fallback; } }
    private static int number(JsonObject object, String key, int fallback) { try { return object.has(key) ? object.get(key).getAsInt() : fallback; } catch (RuntimeException ignored) { return fallback; } }
    private static JsonArray array(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray(); }
    private static ItemStack item(String id) { try { var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id)); return item == null ? ItemStack.EMPTY : new ItemStack(item); } catch (RuntimeException ignored) { return ItemStack.EMPTY; } }
    private ItemStack icon(JsonObject row) {
        String nbt = text(row, "icon", ""), id = text(row, "iconItem", "minecraft:paper");
        String key = nbt.isBlank() ? id : nbt;
        ItemStack known = iconCache.get(key);
        if (known != null) return known;
        ItemStack decoded = ItemStack.EMPTY;
        if (!nbt.isBlank() && nbt.length() <= 4096) try { decoded = ItemStack.of(TagParser.parseTag(nbt)); } catch (Exception ignored) { }
        if (decoded.isEmpty()) decoded = item(id);
        if (iconCache.size() >= 1024) iconCache.clear();
        iconCache.put(key, decoded); return decoded;
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF17212B);
        g.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + 40, 0xFF253746);
        int total = number(state(), "total", 0), progress = number(state(), "progress", 0);
        g.fill(leftPos + 12, topPos + 36, leftPos + 344, topPos + 38, 0xFF405261);
        if (total > 0) g.fill(leftPos + 12, topPos + 36, leftPos + 12 + Math.max(0, Math.min(332, (int)(332L * progress / total))), topPos + 38, 0xFF80CEA9);
        if (page == PRODUCTION) for (int row = 0; row < FactoryUiState.VISIBLE_ROWS; row++) {
            int index = ui.offset() + row; if (index >= filtered.size()) break;
            Product product = filtered.get(index); int y = topPos + ROW_Y + row * ROW_HEIGHT;
            boolean chosen = product.id().equals(ui.selected()), hover = mouseX >= leftPos + 12 && mouseX < leftPos + 344 && mouseY >= y && mouseY < y + 20;
            g.fill(leftPos + 12, y, leftPos + 344, y + 20, chosen ? 0xFF3B6257 : hover ? 0xFF344959 : 0xFF24333F);
            if (chosen) g.fill(leftPos + 12, y, leftPos + 14, y + 20, 0xFF8AD5AE);
        }
        if (page == STORAGE) for (var slot : menu.slots) {
            g.fill(leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, 0xFF536272);
            g.fill(leftPos + slot.x, topPos + slot.y, leftPos + slot.x + 16, topPos + slot.y + 16, 0xFF293743);
        }
    }
    private void label(GuiGraphics g, String value, int x, int y, int color, int width) { g.drawString(font, font.plainSubstrByWidth(value, width), x, y, color, false); }
    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {
        label(g, text(state(), "title", menu.type.title()), 12, 8, INK, 268);
        int total = number(state(), "total", 0), progress = number(state(), "progress", 0);
        String status = text(state(), "status", "Получение состояния…");
        if (total > 0 && !constructing()) status = Math.max(0, Math.min(100, (int)(100L * progress / total))) + "% · " + status;
        label(g, status, 12, 23, INK, 225);
        boolean powered = text(state(), "powered", "false").equals("true");
        label(g, number(state(), "health", 0) + "% · " + (powered ? "ток +" : "ток −"), 245, 23, powered ? GREEN : GOLD, 99);
        if(page==CONSTRUCTION){
            label(g,"Фундамент → стены → крыша → оборудование",12,76,INK,332);
            label(g,"Таймер идёт, пока живой строитель работает рядом.",12,126,MUTED,332);
            label(g,"Повреждения требуют ремонта только готовых этапов.",12,176,MUTED,332);
            label(g,"После начала работ комплект не возвращается.",12,228,GOLD,332);
        }else if(page==RESEARCH)renderResearch(g);
        else if (page == PRODUCTION) renderProducts(g);
        else if (page == STORAGE) {
            label(g, "Материалы и ремнаборы", 16, 70, MUTED, 200); label(g, "Готовая продукция", 232, 70, GREEN, 112);
            label(g, "Инвентарь · Shift-клик для переноса", 97, 130, MUTED, 247);
        } else if (advanced) renderAdvanced(g);
        else {
            label(g, menu.type.isFactory() ? "Рабочие: " + number(state(), "workers", 0) + "   Ремонтники: " + number(state(), "repairWorkers", 0)
                : "Электрики на объекте: " + number(state(), "repairWorkers", 0), 12, 74, INK, 332);
            label(g, "Сначала свободный рабочий; новый — 8 изумрудов.", 12, 116, MUTED, 332);
            int repair = number(state(), "repairRemaining", 0);
            label(g, repair > 0 ? "Ремонт: ещё " + ((repair + 19) / 20) + " с; нужен ремнабор на складе." : "Ремонт: 1 ремнабор + ремонтник · 90 секунд.", 12, 161, MUTED, 332);
            label(g, menu.type == SiteType.POWER_STATION ? "Топливо: " + number(state(), "fuel", 0) + " угля · запас " + (number(state(), "fuelTicks", 0) / 20) + " с" : menu.type.isFactory() ? "Материалы для выпуска берутся со склада объекта." : "Электрик обслуживает и ремонтирует этот участок сети.", 12, 206, MUTED, 332);
        }
    }
    private void renderProducts(GuiGraphics g) {
        if (filtered.isEmpty()) label(g, catalog.isEmpty() ? "Каталог загружается…" : "По этому названию ничего не найдено.", 18, 114, MUTED, 320);
        for (int row = 0; row < FactoryUiState.VISIBLE_ROWS && ui.offset() + row < filtered.size(); row++) {
            Product product = filtered.get(ui.offset() + row); int y = ROW_Y + row * ROW_HEIGHT;
            g.renderItem(product.icon(), 17, y + 2); g.renderItemDecorations(font, product.icon(), 17, y + 2);
            label(g,(product.denial().isEmpty()?"":"[закрыто] ")+product.name(),38,y+6,product.denial().isEmpty()?INK:GOLD,232);
            label(g, product.seconds() + " с", 285, y + 6, MUTED, 52);
        }
        Product product = selected();
        if (product != null) for (int i = 0; i < Math.min(4, product.materials().size()); i++) {
            Material cost = product.materials().get(i); int x = 12 + i * 83, needed = cost.count() * ui.quantity();
            g.renderItem(cost.icon(), x, 177);
            label(g, cost.available() + "/" + needed, x + 19, 181, cost.available() >= needed ? GREEN : RED, 63);
        }
        label(g, "×" + ui.quantity(), 43, 203, INK, 39);
    }
    private boolean constructing(){return state().has("construction")&&state().getAsJsonObject("construction").has("active")&&state().getAsJsonObject("construction").get("active").getAsBoolean();}
    private JsonObject research(){return state().has("research")?state().getAsJsonObject("research"):new JsonObject();}
    private String selectedTechnology(){var rows=array(research(),"technologies");return rows.size()>researchIndex?text(rows.get(researchIndex).getAsJsonObject(),"id",""):"";}
    private void renderResearch(GuiGraphics g){
        var data=research();var rows=array(data,"technologies");
        label(g,text(data,"country","Технологии страны"),60,70,INK,240);
        for(int i=0;i<4&&i+researchOffset<rows.size();i++){
            int index=i+researchOffset;var row=rows.get(index).getAsJsonObject();int y=90+i*21;
            boolean complete=text(row,"complete","false").equals("true");
            if(index==researchIndex)g.fill(12,y,344,y+20,0xFF365746);
            label(g,(complete?"✓ ":"")+text(row,"name",""),17,y+5,complete?GREEN:INK,260);
            label(g,number(row,"seconds",0)+" с",284,y+5,MUTED,58);
        }
        if(researchIndex<rows.size()){
            var row=rows.get(researchIndex).getAsJsonObject();String info=text(row,"available","false").equals("true")?number(row,"emeralds",0)+" изумр. + "+number(row,"redstone",0)+" редстоуна":"Нужно: "+text(row,"requires","");
            if(text(row,"complete","false").equals("true"))info="Технология уже изучена";
            label(g,info,12,181,GOLD,332);
        }
    }
    private void renderAdvanced(GuiGraphics g) {
        String workerName = "Нет доступных рабочих"; int transferred = 0;
        for (var worker : workers()) {
            if (text(worker, "transferred", "false").equals("true")) transferred++;
            if (text(worker, "id", "").equals(selectedWorker)) workerName = text(worker, "name", "Рабочий") + (text(worker, "loaded", "false").equals("true") ? "" : " · далеко");
        }
        label(g, workerName, 12, 96, GOLD, 332);
        label(g, "Переведено из армии: " + transferred + "/7 · назначение: 64 блока.", 12, 194, MUTED, 332);
        label(g, "Возврат бойца сохраняет его снаряжение и происхождение.", 12, 210, MUTED, 332);
        label(g, "Роль должна подходить выбранному объекту.", 12, 226, MUTED, 332);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (query != null && query.isFocused()) {
            if (key == 256) { query.setFocused(false); setFocused(null); return true; }
            if (key == 257 || key == 335) { order(); return true; }
            if (key == 264 || key == 265) { ui.scroll(key == 264 ? 1 : -1, filtered.size()); return true; }
            // Inventory hotkeys (including E) are text while the search field has focus.
            query.keyPressed(key, scan, modifiers); return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if(page==RESEARCH&&button==0&&x>=leftPos+12&&x<leftPos+344&&y>=topPos+90&&y<topPos+174){
            researchIndex=Math.min(7,researchOffset+(int)(y-topPos-90)/21);rebuild();return true;
        }
        if (page == PRODUCTION && button == 0 && x >= leftPos + 12 && x < leftPos + 344 && y >= topPos + ROW_Y && y < topPos + ROW_Y + 4 * ROW_HEIGHT) {
            int row = (int)(y - topPos - ROW_Y) / ROW_HEIGHT, index = ui.offset() + row;
            if (index < filtered.size()) { ui.select(filtered.get(index).id()); if (query != null) query.setFocused(false); setFocused(null); return true; }
        }
        return super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double amount) {
        if (page == PRODUCTION && x >= leftPos + 12 && x < leftPos + 344 && y >= topPos + ROW_Y && y < topPos + ROW_Y + 84) { ui.scroll(amount < 0 ? 1 : -1, filtered.size()); return true; }
        return super.mouseScrolled(x, y, amount);
    }
    @Override public void removed() { menu.showStorage(false); super.removed(); }
    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        readView(); renderBackground(g); super.render(g, mx, my, partial); renderTooltip(g, mx, my);
        if (page != PRODUCTION) return;
        int x = mx - leftPos, y = my - topPos;
        Product product = null;
        if (x >= 12 && x < 344 && y >= ROW_Y && y < ROW_Y + 84) {
            int index = ui.offset() + (y - ROW_Y) / ROW_HEIGHT;
            if (index < filtered.size()) product = filtered.get(index);
        }
        if (product != null) {
            List<Component> lines = new ArrayList<>(); lines.add(Component.literal(product.name()));
            if(!product.denial().isEmpty())lines.add(Component.literal(product.denial()));
            lines.add(Component.literal(product.outputCount() + " шт. за партию · " + product.seconds() + " с · " + product.fe() + " FE/тик"));
            for (Material material : product.materials()) lines.add(Component.literal(material.name() + ": " + material.count() + " на партию"));
            lines.add(Component.literal("Щёлкни по изделию, затем «Произвести»."));
            g.renderComponentTooltip(font, lines, mx, my);
        } else if (x >= 12 && x < 344 && y >= 176 && y < 194 && selected() != null) {
            int index = (x - 12) / 83; var materials = selected().materials();
            if (index < materials.size()) { Material cost = materials.get(index); g.renderComponentTooltip(font, List.of(Component.literal(cost.name()), Component.literal("На складе: " + cost.available() + " · Нужно: " + cost.count() * ui.quantity()), Component.literal("Красное число — добавь материал на вкладке «Склад».")), mx, my); }
        }
    }
}
