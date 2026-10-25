package dev.frontier.industry.client;

import com.google.gson.JsonObject;
import com.talhanation.recruits.client.gui.faction.FactionMainScreen;
import com.talhanation.recruits.client.gui.diplomacy.DiplomacyTeamListScreen;
import com.talhanation.recruits.client.gui.worldmap.WorldMapScreen;
import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.IndustryMod;
import dev.frontier.industry.OperationsNet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import dev.frontier.industry.client.ui.IndustryWidgets;
import dev.frontier.industry.client.ui.LdlibWidgetHost;
import dev.frontier.industry.client.ui.UiViewport;
import dev.frontier.industry.client.ui.UiPage;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** The player's one Russian entry point. Native screens and server packets keep their own authority. */
@Mod.EventBusSubscriber(modid = IndustryMod.ID, value = Dist.CLIENT)
public final class OperationsScreen extends Screen {
    private static JsonObject state = new JsonObject();
    private static boolean session;
    private static int lastTab;
    private int tab, page, ticks, hireCount = 1, listY, armyTab;
    private LdlibWidgetHost widgets;
    private UiViewport viewport;
    private int x, y, w, h, contentX, contentW;
    private String selectedSystem = "", targetX = "", targetY = "", targetZ = "";
    private int salvo = 1;
    private TextFieldWidget missileX, missileY, missileZ;
    private boolean cameraPage, industryKits;
    private static final java.util.Map<String, ItemStack> ITEM_ICONS = new java.util.LinkedHashMap<>();
    private final List<Icon> icons = new ArrayList<>();
    private static final int WHITE = 0xEAF1F4, GRAY = 0xA7BAC2, GREEN = 0x87D6B1, GOLD = 0xF2CE80;
    private record Icon(ItemStack item, int x, int y) {}
    public OperationsScreen() { super(Component.literal("КПК · Штаб")); tab = Math.max(0, Math.min(3, lastTab)); }
    public static void receive(JsonObject data, boolean open, boolean camera) {
        state = data;
        var mc = Minecraft.getInstance();
        if (camera && mc.screen instanceof OperationsScreen) { mc.setScreen(null); return; }
        if (open) { open(); return; }
        if (mc.screen instanceof OperationsScreen screen && !screen.widgets.hasTextFocus()) screen.rebuild();
    }
    public static void open() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        session = true; mc.setScreen(new OperationsScreen()); OperationsNet.request("refresh", "", 0);
    }
    @SubscribeEvent public static void nativeReturn(ScreenEvent.Init.Post event) {
        var mc = Minecraft.getInstance();
        if (!session || mc.player == null || !holdingPda()) return;
        String name = event.getScreen().getClass().getName();
        if (name.startsWith("com.talhanation.recruits.client.gui.") || name.startsWith("com.diego.recruitsvillages.client.")
                || name.startsWith("dev.sbw.deepstrike.client.")) {
            event.addListener(Button.builder(Component.literal("← КПК"), b -> open()).bounds(5, 5, 58, 20).build());
        }
    }
    private static boolean holdingPda() {
        var p = Minecraft.getInstance().player;
        return p != null && (IndustryContent.isTablet(p.getMainHandItem()) || IndustryContent.isTablet(p.getOffhandItem()));
    }
    @Override protected void init() {
        w = 560; h = 342;
        x = (width - w) / 2; y = (height - h) / 2; contentX = x + 120; contentW = w - 134;
        viewport = UiViewport.fit(width, height, w, h);
        widgets = new LdlibWidgetHost(width, height);
        rebuild();
    }
    private void rebuild() {
        if (minecraft == null || font == null) return;
        if (missileX != null) { targetX = missileX.getCurrentString(); targetY = missileY.getCurrentString(); targetZ = missileZ.getCurrentString(); }
        widgets.clear(); icons.clear(); missileX = missileY = missileZ = null;
        String[] tabs = {"Страна", "Армия", "Производство", "Ремонт"};
        for (int index = 0; index < tabs.length; index++) {
            final int target = index;
            button(x + 10, y + 52 + index * 24, 102, tabs[index], () -> { tab = lastTab = target; page = 0; rebuild(); }).enabled(target != tab, "Открытый раздел");
        }
        button(x + w - 122, y + 9, 88, "Справочник", IndustryGuide::open);
        button(x + w - 28, y + 9, 18, "×", this::onClose);
        label(tabs[tab], contentX, y + 52, WHITE, contentW);
        listY = y + 75;
        switch (tab) {
            case 0 -> country(); case 1 -> armySection(); case 2 -> industry(); case 3 -> repair();
        }
        label("Объектов: " + rows(state, "sites").size(), x + 10, y + 163, GRAY, 102);
        label("Рабочих: " + text(state, "workers", "0"), x + 10, y + 179, GRAY, 102);
        label("Армия: " + text(state, "armyCount", "0"), x + 10, y + 195, GRAY, 102);
        widgets.add(IndustryWidgets.text(x + 10, y + h - 24, w - 20, 22,
            () -> text(state, "message", "Получение данных…"), GOLD));
    }
    private void country() {
        var c = object(state, "country");
        line(text(c, "name", "Без страны"), GOLD);
        if (bool(c, "member")) {
            line("Жителей: " + text(c, "civilians", "0") + "  ·  Гарнизон: " + text(c, "soldiers", "0"), GRAY);
            line("Казна: " + text(c, "treasury", "0") + "  ·  Еда: " + text(c, "food", "0"), GRAY);
        } else line("Вступи в поселение рядом или создай свою страну.", GRAY);
        gap(5);
        fullButton("Моя страна / создать страну", () -> minecraft.setScreen(new FactionMainScreen(minecraft.player)));
        twoButtons("Поселение", () -> send("village", "", 0), "Вступить здесь", () -> send("join", "", 0));
        twoButtons("Границы на карте", () -> minecraft.setScreen(new WorldMapScreen()), "Отношения", () -> minecraft.setScreen(new DiplomacyTeamListScreen(this, false)));
        if (listY < y + h - 50) fullButton("Руководство по государствам", () -> send("guide", "", 0));
    }
    private void armySection() {
        String[] sections = {"Бойцы", "ПВО и ракеты", "Техника и БПЛА"};
        int part = (contentW - 8) / 3;
        for (int index = 0; index < sections.length; index++) {
            final int target = index;
            button(contentX + index * (part + 4), listY, part, sections[index], () -> {
                armyTab = target; selectedSystem = ""; page = 0; rebuild();
            }).enabled(armyTab != index, "Открытый раздел");
        }
        gap(26);
        switch (armyTab) { case 0 -> army(); case 1 -> weapons(); case 2 -> vehicles(); }
    }
    private void repair() {
        line("Сеть и заводы ремонтируются отдельными бригадами.", GRAY);
        var all = rows(state, "sites").stream().filter(row -> number(row, "health", 100) < 100
            || bool(object(row, "repairState"), "requested")).toList();
        if (all.isEmpty()) {
            line("Повреждённых объектов нет.", GREEN);
            line("Ремнабор и ремонтник назначаются в управлении объектом.", GRAY);
            return;
        }
        int perPage = Math.max(1, (y + h - 55 - listY) / 61);
        for (var row : slice(all, perPage)) {
            String id = text(row, "id", ""); var repair = object(row, "repairState");
            label(text(row, "title", "Объект") + " · " + number(row, "health", 0) + "%", contentX, listY, WHITE, contentW - 85);
            label(text(repair, "reason", text(row, "status", "Открой объект для ремонта")), contentX, listY + 14, GOLD, contentW - 85);
            int progress = number(repair, "progressTicks", 0), total = number(repair, "totalTicks", 1800);
            label("Бригада: " + number(repair, "assignedWorkers", number(row, "repairWorkers", 0)) + " · "
                + (bool(repair, "requested") ? "Осталось: " : "Работа: ")
                + Math.max(0, (total - progress + 19) / 20) + " с", contentX, listY + 28, GRAY, contentW - 85);
            button(contentX + contentW - 78, listY + 3, 78, "К объекту", () -> send("open_site", id, 0))
                .enabled(bool(row, "sameDimension") && bool(row, "loaded"), siteAccessReason(row));
            widgets.add(IndustryWidgets.progress(contentX, listY + 44, contentW, 3, () -> total <= 0 ? 0 : progress / (double)total));
            gap(61);
        }
        pager(all.size(), perPage);
    }
    private static String siteAccessReason(JsonObject row) {
        return !bool(row, "sameDimension") ? "Объект в другом измерении."
            : !bool(row, "loaded") ? "Чанки объекта выгружены. Подойди к объекту."
            : "Управление доступно рядом с объектом, до 12 блоков.";
    }
    private void army() {
        line("Армия: " + text(state, "armyCount", "0") + "  ·  С тобой рядом: " + rows(state, "soldiers").size(), WHITE);
        twoButtons("Все: за мной", () -> send("follow", "all", 0), "Все: стоять", () -> send("hold", "all", 0));
        twoButtons("Группы и приказы", () -> send("commands", "", 0), "Найм: ×" + hireCount, () -> { hireCount = hireCount == 1 ? 3 : hireCount == 3 ? 7 : 1; rebuild(); });
        var options = rows(state, "hireTypes");
        int room = Math.max(1, (y + h - 52 - listY) / 25);
        int perPage = Math.max(1, Math.min(4, room - 1));
        label("Нанять бойцов из своей страны", contentX, listY + 1, GOLD, contentW); gap(15);
        for (var row : slice(options, perPage)) {
            icons.add(new Icon(new ItemStack(Items.IRON_SWORD), contentX, listY + 2));
            String name = text(row, "name", "Боец"), id = text(row, "id", "");
            label(name, contentX + 21, listY + 1, WHITE, contentW - 105);
            label(number(row, "cost", 0) * hireCount + " изумр.", contentX + 21, listY + 12, GRAY, contentW - 105);
            button(contentX + contentW - 76, listY, 76, "Нанять ×" + hireCount, () -> send("hire_soldier", id, hireCount));
            gap(25);
        }
        pager(options.size(), perPage);
    }
    private void industry() {
        line("Рабочих: " + text(state, "workers", "0") + "  ·  Из армии: " + text(state, "transferred", "0") + "/7", GRAY);
        twoButtons("+ Строитель · 4 изумр.",()->send("hire_builder","",1),industryKits?"← Мои объекты":"Комплекты зданий",()->{industryKits=!industryKits;page=0;rebuild();});
        if(industryKits){buildingKits();return;}
        var all = rows(state, "sites");
        if (all.isEmpty()) {
            line("Найми строителя и выбери комплект здания.", GOLD);
            line("Комплекты покупаются за материалы кнопкой выше.", GRAY);
            line("Сеть подключается при постройке автоматически.", GRAY); return;
        }
        int perPage = Math.max(1, (y + h - 54 - listY) / 47);
        for (var row : slice(all, perPage)) {
            String id = text(row, "id", ""), type = text(row, "type", "");
            ItemStack item;
            try { item = new ItemStack(IndustryContent.deployer(dev.frontier.industry.SiteType.valueOf(type))); } catch (Exception e) { item = new ItemStack(Items.CRAFTING_TABLE); }
            icons.add(new Icon(item, contentX, listY + 3));
            label(text(row, "title", "Объект"), contentX + 22, listY, WHITE, contentW - 103);
            label(text(row, "status", "") + " · " + text(row, "health", "0") + "%", contentX + 22, listY + 12, bool(row, "powered") ? GREEN : GOLD, contentW - 103);
            label(text(row, "x", "") + ", " + text(row, "y", "") + ", " + text(row, "z", "") + " · " + (bool(row, "sameDimension") ? text(row, "distance", "?") + " м" : "другое измерение"), contentX + 22, listY + 24, GRAY, contentW - 25);
            button(contentX + contentW - 74, listY + 2, 74, "Управлять", () -> send("open_site", id, 0)).enabled(bool(row, "sameDimension") && bool(row, "loaded"), siteAccessReason(row));
            gap(47);
        }
        pager(all.size(), perPage);
    }
    private void buildingKits(){
        var all=rows(state,"buildingKits");int perPage=Math.max(1,(y+h-55-listY)/48);
        for(var row:slice(all,perPage)){
            String id=text(row,"id","");
            label(text(row,"name","")+" · "+text(row,"seconds","")+" с",contentX,listY,WHITE,contentW-86);
            label(text(row,"cost",""),contentX,listY+14,GRAY,contentW-86);
            button(contentX+contentW-78,listY+1,78,"Собрать",()->send("building_kit",id,1));gap(48);
        }pager(all.size(),perPage);
    }
    private void weapons() {
        JsonObject data = object(state, "weapons");
        var systems = rows(data, "systems");
        var selected = systems.stream().filter(s -> selectedSystem.equals(text(s, "id", ""))).findFirst().orElse(null);
        if (selected != null) { weaponDetails(selected); return; }
        selectedSystem = "";
        line(text(data, "summary", "Готовые ракеты, установки и противовоздушная оборона"), GRAY);
        if (systems.isEmpty()) {
            line(text(data, "message", "Разверни установку и радар, затем подключи их."), GOLD);
            if (bool(data, "available")) fullButton("Карта целей и маршрутов", this::nativeTerminal);
            return;
        }
        int perPage = Math.max(1, (y + h - 83 - listY) / 44);
        for (var row : slice(systems, perPage)) {
            String id = text(row, "id", "");
            label(text(row, "name", "Установка"), contentX, listY, WHITE, contentW - 84);
            label("Боезапас: " + text(row, "ammo", "—") + " · " + text(row, "status", bool(row, "active") ? "Дежурство" : "Ожидание"), contentX, listY + 13, GRAY, contentW - 84);
            button(contentX + contentW - 78, listY, 78, "Управлять", () -> { selectedSystem = id; rebuild(); }); gap(44);
        }
        if (listY < y + h - 54) fullButton("Карта целей и маршрутов", this::nativeTerminal);
        pager(systems.size(), perPage);
    }
    private void weaponDetails(JsonObject row) {
        String id = text(row, "id", ""), kind = text(row, "type", "").toLowerCase(java.util.Locale.ROOT);
        button(contentX + contentW - 78, y + 49, 78, "← Список", () -> { selectedSystem = ""; rebuild(); });
        line(text(row, "name", "Установка"), WHITE);
        line("Ракет: " + text(row, "ammo", "—") + " · Экипаж: " + (bool(row, "crew") ? "готов" : "нужен боец"), GOLD);
        twoButtons(bool(row, "crew") ? "Освободить бойца" : "Назначить бойца", () -> send(bool(row, "crew") ? "pvo_release" : "pvo_operator", id, 0), "Загрузить ракеты", () -> send("pvo_reload", id, 0));
        twoButtons("На дежурство", () -> send("pvo_active", id, 0), "Приостановить", () -> send("pvo_hold", id, 0));
        boolean launcher = id.startsWith("block:") || kind.contains("launcher") || kind.contains("peacekeeper") || kind.contains("ballistic");
        if (launcher) {
            if (targetX.isBlank() && minecraft.player != null) {
                net.minecraft.core.BlockPos target = minecraft.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                    ? hit.getBlockPos() : minecraft.player.blockPosition().offset(64, 0, 0);
                targetX = Integer.toString(target.getX()); targetY = Integer.toString(target.getY()); targetZ = Integer.toString(target.getZ());
            }
            int field = (contentW - 12) / 3;
            label("Цель X", contentX, listY, GRAY, field); label("Y", contentX + field + 6, listY, GRAY, field);
            label("Z", contentX + (field + 6) * 2, listY, GRAY, field); gap(12);
            missileX = coordinate(contentX, listY, field, targetX); missileY = coordinate(contentX + field + 6, listY, field, targetY);
            missileZ = coordinate(contentX + (field + 6) * 2, listY, field, targetZ); gap(22);
            button(contentX, listY, 78, "Залп: " + salvo, () -> { salvo = salvo % 3 + 1; rebuild(); });
            button(contentX + 84, listY, contentW - 84, "Запустить по координатам", () -> {
                String coordinates = missileX.getCurrentString() + "," + missileY.getCurrentString() + "," + missileZ.getCurrentString();
                send("pvo_launch", id + "|" + coordinates, salvo); widgets.clearFocus();
            });
        } else fullButton("Карта радаров и маршрутов", this::nativeTerminal);
    }
    private TextFieldWidget coordinate(int bx, int by, int bw, String value) {
        TextFieldWidget field = new TextFieldWidget(bx, by, bw, 18, null, null).setMaxStringLength(10);
        field.setValidator(input -> input.matches("-?[0-9]*") ? input : field.getCurrentString());
        field.setCurrentString(value); return widgets.add(field);
    }
    private void nativeTerminal() {
        try { Class.forName("dev.sbw.deepstrike.client.CommandTabletScreen").getMethod("open").invoke(null); }
        catch (ReflectiveOperationException | LinkageError unavailable) { send("defense", "terminal", 0); }
    }
    private void vehicles() {
        JsonObject shop = object(state, "shop");
        if (!bool(shop, "available")) {
            line("БМП — экипажи через раздел «Бойцы».", WHITE);
            line("Дроны, ракеты и техника производятся на заводах.", GRAY);
            fullButton("Открыть приказы экипажам", () -> send("commands", "", 0)); return;
        }
        line("Изумруды при себе: " + text(shop, "balance", "0"), GOLD);
        twoButtons("Магазин", () -> { cameraPage = false; page = 0; rebuild(); }, "Камеры БПЛА", () -> { cameraPage = true; page = 0; rebuild(); });
        if (cameraPage) {
            fullButton("Выключить камеру", () -> send("camera", "off", 1));
            var targets = rows(shop, "cameraTargets");
            if (targets.isEmpty()) { line("Рядом нет собственных БПЛА с доступной камерой.", GOLD); return; }
            int perPage = Math.max(1, (y + h - 53 - listY) / 28);
            for (var target : slice(targets, perPage)) {
                String id = text(target, "id", "");
                label(text(target, "name", "БПЛА") + " · " + (id.length() > 4 ? id.substring(id.length() - 4) : id), contentX, listY + 5, WHITE, contentW - 90);
                button(contentX + contentW - 84, listY, 84, "Смотреть", () -> send("camera", id, 1)); gap(28);
            }
            pager(targets.size(), perPage); return;
        }
        var items = rows(shop, "items"); int perPage = Math.max(1, (y + h - 56 - listY) / 48);
        for (var row : slice(items, perPage)) {
            String id = text(row, "id", "");
            icons.add(new Icon(itemIcon(row), contentX, listY + 2));
            label(text(row, "name", id), contentX + 22, listY, WHITE, contentW - 110);
            label(text(row, "cost", "") + " изумр.", contentX + 22, listY + 13, GRAY, contentW - 110);
            if (bool(row, "locked")) label(text(row, "denial", "Технология не изучена"), contentX + 22, listY + 27, GOLD, contentW - 22);
            button(contentX + contentW - 78,listY,78,bool(row,"locked")?"Закрыто":"Купить",()->send("shop",id,1))
                .enabled(!bool(row,"locked"),text(row,"denial","Технология не изучена")); gap(48);
        }
        pager(items.size(), perPage);
    }
    private void pager(int count, int perPage) {
        UiPage slice = UiPage.of(page, count, perPage); int pages = slice.pages(); page = slice.index();
        if (pages <= 1) return;
        int bottom = y + h - 48;
        button(contentX, bottom, 28, "‹", () -> { page = Math.max(0, page - 1); rebuild(); }).enabled(page > 0, "Первая страница");
        label((page + 1) + " / " + pages, contentX + 40, bottom + 6, GRAY, 80);
        button(contentX + contentW - 28, bottom, 28, "›", () -> { page = Math.min(pages - 1, page + 1); rebuild(); }).enabled(page + 1 < pages, "Последняя страница");
    }
    private List<JsonObject> slice(List<JsonObject> all, int size) {
        UiPage slice = UiPage.of(page, all.size(), size); page = slice.index();
        return all.subList(slice.from(), slice.to());
    }
    private void send(String action, String arg, int value) { OperationsNet.request(action, arg, value); }
    private IndustryWidgets.ActionButton button(int bx, int by, int bw, String title, Runnable action) {
        return widgets.add(new IndustryWidgets.ActionButton(bx, by, bw, 20, title, () -> widgets.afterInput(action)));
    }
    private void fullButton(String title, Runnable action) { button(contentX, listY, contentW, title, action); gap(25); }
    private void twoButtons(String a, Runnable first, String b, Runnable second) {
        int half = (contentW - 6) / 2; button(contentX, listY, half, a, first); button(contentX + half + 6, listY, half, b, second); gap(25);
    }
    private void line(String s, int color) { if (listY < y + h - 32) label(s, contentX, listY, color, contentW); gap(16); }
    private void gap(int amount) { listY += amount; }
    private void label(String s, int tx, int ty, int color, int max) {
        widgets.add(IndustryWidgets.text(tx, ty - 2, Math.max(10, max), 13, () -> s, color));
    }
    private static JsonObject object(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject(); }
    private static String text(JsonObject o, String key, String fallback) { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback; }
    private static int number(JsonObject o, String key, int fallback) { try { return o.get(key).getAsInt(); } catch (Exception e) { return fallback; } }
    private static boolean bool(JsonObject o, String key) { return "true".equals(text(o, key, "false")); }
    private static List<JsonObject> rows(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return List.of();
        return o.getAsJsonArray(key).asList().stream().filter(v -> v.isJsonObject()).map(v -> v.getAsJsonObject()).toList();
    }
    private static ItemStack itemIcon(JsonObject row) {
        String nbt = text(row, "icon", ""), item = text(row, "item", "minecraft:chest");
        String key = nbt.isBlank() ? item : nbt;
        ItemStack cached = ITEM_ICONS.get(key); if (cached != null) return cached;
        ItemStack result = ItemStack.EMPTY;
        if (!nbt.isBlank() && nbt.length() <= 8192) try { result = ItemStack.of(net.minecraft.nbt.TagParser.parseTag(nbt)); } catch (Exception ignored) { }
        if (result.isEmpty()) try {
            var found = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new ResourceLocation(item));
            if (found != null) result = new ItemStack(found);
        } catch (Exception ignored) { }
        if (ITEM_ICONS.size() > 256) ITEM_ICONS.clear(); ITEM_ICONS.put(key, result); return result;
    }
    @Override public void tick() {
        if (!holdingPda()) { onClose(); return; }
        widgets.tick();
        if (++ticks % 20 == 0) OperationsNet.request("refresh", "", 0);
    }
    @Override public boolean mouseClicked(double x, double y, int button) { return widgets.mouseClicked(viewport.logicalX(x), viewport.logicalY(y), button); }
    @Override public boolean mouseReleased(double x, double y, int button) { return widgets.mouseReleased(viewport.logicalX(x), viewport.logicalY(y), button); }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return widgets.mouseDragged(viewport.logicalX(x), viewport.logicalY(y), button, dx / viewport.scale(), dy / viewport.scale());
    }
    @Override public void mouseMoved(double x, double y) { widgets.mouseMoved(viewport.logicalX(x), viewport.logicalY(y)); }
    @Override public boolean mouseScrolled(double x, double y, double delta) { return widgets.mouseScrolled(viewport.logicalX(x), viewport.logicalY(y), delta); }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (widgets.hasTextFocus()) {
            if (key == 256) { widgets.clearFocus(); return true; }
            widgets.keyPressed(key, scan, modifiers); return true;
        }
        return widgets.keyPressed(key, scan, modifiers) || super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean charTyped(char value, int modifiers) { return widgets.charTyped(value, modifiers); }
    @Override public boolean keyReleased(int key, int scan, int modifiers) { return widgets.keyReleased(key, scan, modifiers); }
    public static void clearSession() { state = new JsonObject(); session = false; lastTab = 0; ITEM_ICONS.clear(); }
    @Override public void render(GuiGraphics graphics, int mx, int my, float partial) {
        renderBackground(graphics);
        int lx = (int)viewport.logicalX(mx), ly = (int)viewport.logicalY(my);
        graphics.pose().pushPose();
        graphics.pose().translate(viewport.centerX() * (1 - viewport.scale()), viewport.centerY() * (1 - viewport.scale()), 0);
        graphics.pose().scale((float)viewport.scale(), (float)viewport.scale(), 1);
        graphics.fill(x, y, x + w, y + h, 0xFF16272D);
        graphics.fill(x + 1, y + 1, x + w - 1, y + 40, 0xFF24434A);
        graphics.fill(x + 115, y + 43, x + 116, y + h - 26, 0xFF38515A);
        graphics.fill(x + 1, y + h - 25, x + w - 1, y + h - 1, 0xFF0E1D22);
        graphics.renderItem(new ItemStack(IndustryContent.preferredTablet()), x + 12, y + 12);
        graphics.drawString(font, "КПК · ПОЛЕВОЙ ШТАБ", x + 36, y + 10, WHITE, false);
        graphics.drawString(font, font.plainSubstrByWidth(text(object(state, "country"), "name", "Frontier Nations"), w - 180), x + 36, y + 24, GREEN, false);
        for (Icon icon : icons) {
            graphics.renderItem(icon.item, icon.x, icon.y);
            graphics.renderItemDecorations(font, icon.item, icon.x, icon.y);
        }
        widgets.draw(graphics, lx, ly, partial); widgets.tooltip(graphics, lx, ly, x, y, w, h);
        graphics.pose().popPose();
    }
    @Override public boolean isPauseScreen() { return false; }
}
