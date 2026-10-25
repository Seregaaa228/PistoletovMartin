package dev.frontier.industry.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.talhanation.recruits.client.gui.faction.FactionMainScreen;
import com.talhanation.recruits.client.gui.diplomacy.DiplomacyTeamListScreen;
import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.IndustryMod;
import dev.frontier.industry.OperationsNet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** The player's one Russian entry point. Native screens and server packets keep their own authority. */
@Mod.EventBusSubscriber(modid = IndustryMod.ID, value = Dist.CLIENT)
public final class OperationsScreen extends Screen {
    private static JsonObject state = new JsonObject();
    private static boolean session;
    private static int lastTab;
    private int tab, page, ticks, hireCount = 1, listY;
    private int x, y, w, h, contentX, contentW;
    private String selectedSystem = "", targetX = "", targetY = "", targetZ = "";
    private int salvo = 1;
    private EditBox missileX, missileY, missileZ;
    private boolean cameraPage, industryKits;
    private static final java.util.Map<String, ItemStack> ITEM_ICONS = new java.util.LinkedHashMap<>();
    private final List<Text> labels = new ArrayList<>();
    private final List<Icon> icons = new ArrayList<>();
    private static final int WHITE = 0xEAF1F4, GRAY = 0xA7BAC2, GREEN = 0x87D6B1, GOLD = 0xF2CE80;
    private record Text(String value, int x, int y, int color, int width) {}
    private record Icon(ItemStack item, int x, int y) {}
    public OperationsScreen() { super(Component.literal("КПК · Штаб")); tab = lastTab; }
    public static void receive(JsonObject data, boolean open, boolean camera) {
        if (data.has("transportError")) state.add("message", data.get("message"));
        else state = data;
        var mc = Minecraft.getInstance();
        if (camera && mc.screen instanceof OperationsScreen) { mc.setScreen(null); return; }
        if (open) { if (mc.screen == null || mc.screen instanceof OperationsScreen) open(); return; }
        if (mc.screen instanceof OperationsScreen screen && !(screen.getFocused() instanceof EditBox)) screen.rebuild();
    }
    public static void open() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.isAlive() || mc.player.isSpectator() || !holdingPda()) return;
        if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        session = true; mc.setScreen(new OperationsScreen()); OperationsNet.request("refresh", "", 0);
    }
    @SubscribeEvent public static void login(ClientPlayerNetworkEvent.LoggingIn event){resetSession();}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){resetSession();}
    private static void resetSession(){state=new JsonObject();session=false;lastTab=0;ITEM_ICONS.clear();OperationsViewBuffer.clear();}
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
        w = Math.min(560, width - 16); h = Math.min(342, height - 16);
        x = (width - w) / 2; y = (height - h) / 2; contentX = x + 112; contentW = w - 126;
        rebuild();
    }
    private void rebuild() {
        if (minecraft == null || font == null) return;
        if (missileX != null) { targetX = missileX.getValue(); targetY = missileY.getValue(); targetZ = missileZ.getValue(); }
        clearWidgets(); labels.clear(); icons.clear(); missileX = missileY = missileZ = null;
        String[] tabs = {"Сводка", "Страна", "Бойцы", "Заводы", "ПВО и ракеты", "Техника"};
        for (int index = 0; index < tabs.length; index++) {
            final int target = index;
            button(x + 10, y + 52 + index * 24, 90, tabs[index], () -> { tab = lastTab = target; page = 0; rebuild(); }).active = target != tab;
        }
        button(x + w - 28, y + 9, 18, "×", this::onClose);
        label(tabs[tab], contentX, y + 52, WHITE, contentW);
        listY = y + 75;
        switch (tab) {
            case 0 -> overview(); case 1 -> country(); case 2 -> army(); case 3 -> industry(); case 4 -> weapons(); case 5 -> vehicles();
        }
    }
    private void overview() {
        JsonObject country = object(state, "country");
        line("Страна: " + text(country, "name", "Без страны"), WHITE);
        line("Армия: " + text(state, "armyCount", "0") + "  ·  Рабочие: " + text(state, "workers", "0"), WHITE);
        var sites = rows(state, "sites");
        long problems = sites.stream().filter(s -> number(s, "health", 100) < 75 || (text(s, "type", "").contains("FACTORY") && !bool(s, "powered"))).count();
        line("Объектов: " + sites.size() + "  ·  Требуют внимания: " + problems, problems > 0 ? GOLD : GREEN);
        line("Изумруды при себе: " + text(state, "emeralds", "0"), GOLD);
        gap(8);
        line("1. Страна — поселение, границы и дипломатия.", GRAY);
        line("2. Бойцы — нанять, собрать отряд, дать приказ.", GRAY);
        line("3. Заводы — выбрать изделие и запустить выпуск.", GRAY);
        line("4. ПВО и ракеты — готовые системы из модов.", GRAY);
        if (listY < y + h - 52) line("ПКМ КПК по воздуху всегда открывает этот штаб.", GREEN);
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
        twoButtons("Границы на карте", this::countryBorders, "Отношения", () -> minecraft.setScreen(new DiplomacyTeamListScreen(this, false)));
        if (listY < y + h - 50) fullButton("Руководство по государствам", () -> send("guide", "", 0));
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
            button(contentX + contentW - 74, listY + 2, 74, "Управлять", () -> send("open_site", id, 0)).active = bool(row, "sameDimension") && bool(row, "loaded");
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
                String coordinates = missileX.getValue() + "," + missileY.getValue() + "," + missileZ.getValue();
                send("pvo_launch", id + "|" + coordinates, salvo); setFocused(null);
            });
        } else fullButton("Карта радаров и маршрутов", this::nativeTerminal);
    }
    private EditBox coordinate(int bx, int by, int bw, String value) {
        EditBox field = new EditBox(font, bx, by, bw, 18, Component.literal("Координата цели"));
        field.setMaxLength(10); field.setFilter(s -> s.matches("-?[0-9]*")); field.setValue(value); addRenderableWidget(field); return field;
    }
    private void nativeTerminal() {
        try { Class.forName("dev.sbw.deepstrike.client.CommandTabletScreen").getMethod("open").invoke(null); }
        catch (ReflectiveOperationException | LinkageError unavailable) { send("defense", "terminal", 0); }
    }
    private void countryBorders(){
        // Nations is optional for Industry. This explicit client API avoids a circular dependency.
        try{Class.forName("dev.frontier.nations.map.xaero.CountryMapClient").getMethod("openEditor",Screen.class).invoke(null,this);}
        catch(ReflectiveOperationException|LinkageError unavailable){
            com.mojang.logging.LogUtils.getLogger().error("Unable to open Xaero country editor from PDA",unavailable);
            if(minecraft.player!=null)minecraft.player.displayClientMessage(Component.literal("Редактор границ Xaero недоступен. Проверьте Frontier Nations"),false);
        }
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
        var items = rows(shop, "items"); int perPage = Math.max(1, (y + h - 56 - listY) / 35);
        for (var row : slice(items, perPage)) {
            String id = text(row, "id", "");
            icons.add(new Icon(itemIcon(row), contentX, listY + 2));
            label(text(row, "name", id), contentX + 22, listY, WHITE, contentW - 110);
            label(text(row, "cost", "") + " изумр.", contentX + 22, listY + 13, GRAY, contentW - 110);
            button(contentX + contentW - 78,listY,78,bool(row,"locked")?"Закрыто":"Купить",()->send("shop",id,1)).active=!bool(row,"locked"); gap(35);
        }
        pager(items.size(), perPage);
    }
    private void pager(int count, int perPage) {
        int pages = Math.max(1, (count + perPage - 1) / perPage); page = Math.min(page, pages - 1);
        if (pages <= 1) return;
        int bottom = y + h - 48;
        button(contentX, bottom, 28, "‹", () -> { page = Math.max(0, page - 1); rebuild(); }).active = page > 0;
        label((page + 1) + " / " + pages, contentX + 40, bottom + 6, GRAY, 80);
        button(contentX + contentW - 28, bottom, 28, "›", () -> { page = Math.min(pages - 1, page + 1); rebuild(); }).active = page + 1 < pages;
    }
    private List<JsonObject> slice(List<JsonObject> all, int size) {
        page = Math.min(page, Math.max(0, (all.size() - 1) / size));
        return all.subList(Math.min(all.size(), page * size), Math.min(all.size(), (page + 1) * size));
    }
    private void send(String action, String arg, int value) { OperationsNet.request(action, arg, value); }
    private Button button(int bx, int by, int bw, String title, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(title), b -> action.run()).bounds(bx, by, bw, 20).build());
    }
    private void fullButton(String title, Runnable action) { button(contentX, listY, contentW, title, action); gap(25); }
    private void twoButtons(String a, Runnable first, String b, Runnable second) {
        int half = (contentW - 6) / 2; button(contentX, listY, half, a, first); button(contentX + half + 6, listY, half, b, second); gap(25);
    }
    private void line(String s, int color) { if (listY < y + h - 32) label(s, contentX, listY, color, contentW); gap(16); }
    private void gap(int amount) { listY += amount; }
    private void label(String s, int tx, int ty, int color, int max) { labels.add(new Text(s, tx, ty, color, Math.max(10, max))); }
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
        if (++ticks % 20 == 0) OperationsNet.request("refresh", "", 0);
    }
    @Override public void render(GuiGraphics graphics, int mx, int my, float partial) {
        renderBackground(graphics);
        graphics.fill(x, y, x + w, y + h, 0xFF16272D);
        graphics.fill(x + 1, y + 1, x + w - 1, y + 40, 0xFF24434A);
        graphics.fill(x + 106, y + 43, x + 107, y + h - 26, 0xFF38515A);
        graphics.fill(x + 1, y + h - 25, x + w - 1, y + h - 1, 0xFF0E1D22);
        graphics.renderItem(new ItemStack(IndustryContent.preferredTablet()), x + 12, y + 12);
        graphics.drawString(font, "КПК · ПОЛЕВОЙ ШТАБ", x + 36, y + 10, WHITE, false);
        graphics.drawString(font, text(object(state, "country"), "name", "Frontier Nations"), x + 36, y + 24, GREEN, false);
        for (Text text : labels) graphics.drawString(font, font.plainSubstrByWidth(text.value, text.width), text.x, text.y, text.color, false);
        for (Icon icon : icons) {
            graphics.renderItem(icon.item, icon.x, icon.y);
            // SBW draws its native aircraft/container icons in Forge's item decorator.
            graphics.renderItemDecorations(font, icon.item, icon.x, icon.y);
        }
        String message = text(state, "message", "Получение данных…");
        graphics.drawString(font, font.plainSubstrByWidth(message, w - 20), x + 10, y + h - 16, GOLD, false);
        super.render(graphics, mx, my, partial);
    }
    @Override public boolean isPauseScreen() { return false; }
}
