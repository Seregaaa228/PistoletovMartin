package dev.frontier.nations.map.client;

import com.talhanation.recruits.client.ClientManager;
import com.talhanation.recruits.client.gui.worldmap.WorldMapScreen;
import com.talhanation.recruits.world.RecruitsClaim;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/** A political overview; the original Recruits screen remains the border editor. */
final class PoliticalMapScreen extends Screen {
    private static final int BACKGROUND = 0xFF17232B;
    private static final int UNCLAIMED = 0xFF293F49;
    private static final int PANEL = 0xFF202E36;
    private static final int TEXT = 0xFFE9E8D8;
    private static final int MUTED = 0xFFA6B5BC;
    private static final int BORDER = 0xFFE2DDC8;
    private static final double MIN_ZOOM = 0.5;
    private static final double MAX_ZOOM = 32;

    private PoliticalMapSnapshot snapshot;
    private double centerX;
    private double centerZ;
    private double pixelsPerChunk = 4;
    private ChunkPos selected;
    private int mapRight;
    private int mapBottom;
    private int snapshotTicks;
    private int countryScroll;
    private boolean positioned;
    private boolean panning;

    PoliticalMapScreen() {
        super(Component.literal("Политическая карта"));
    }

    @Override
    protected void init() {
        mapRight = width - Math.min(184, Math.max(112, width / 3)) - 16;
        mapBottom = height - 63;
        snapshot = PoliticalMapSnapshot.capture();
        if (!positioned) {
            if (snapshot.cells.isEmpty()) centerOnPlayer(); else fitCountries();
            positioned = true;
        }
        addRenderableWidget(Button.builder(Component.literal("Все страны"), b -> fitCountries())
                .bounds(8, height - 24, 66, 20).build());
        addRenderableWidget(Button.builder(Component.literal("К себе"), b -> centerOnPlayer())
                .bounds(78, height - 24, 54, 20).build());
        Button edit = addRenderableWidget(Button.builder(Component.literal("Границы [R]"), b -> openNativeEditor())
                .bounds(width - 144, height - 24, 136, 20).build());
        edit.active = inOverworld();
        addRenderableWidget(Button.builder(Component.literal("X"), b -> onClose())
                .bounds(width - 28, 9, 20, 20).build());
    }

    @Override
    public void tick() {
        // Recruits owns all synchronization. Rebuild only a bounded local projection once per second.
        if (++snapshotTicks >= 20) {
            snapshotTicks = 0;
            snapshot = PoliticalMapSnapshot.capture();
            countryScroll = Mth.clamp(countryScroll, 0, Math.max(0, snapshot.countries.size() - visibleCountries()));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        graphics.drawString(font, "Политическая карта", 10, 10, TEXT, false);
        drawClipped(graphics, "Верхний мир | стран: " + snapshot.countries.size() + " | чанков: "
                + snapshot.cells.size(), 10, 26, width - 20, MUTED);
        graphics.fill(8, 44, mapRight, mapBottom, UNCLAIMED);
        graphics.fill(mapRight + 8, 44, width - 8, mapBottom, PANEL);
        graphics.enableScissor(8, 44, mapRight, mapBottom);
        drawGrid(graphics);
        drawCountries(graphics);
        drawSelection(graphics, selected, 0xFFFFDD7A);
        if (insideMap(mouseX, mouseY)) drawSelection(graphics, chunkAt(mouseX, mouseY), 0x99FFFFFF);
        if (inOverworld() && minecraft.player != null) {
            int x = screenX(minecraft.player.getX() / 16);
            int y = screenZ(minecraft.player.getZ() / 16);
            graphics.fill(x - 3, y - 1, x + 4, y + 2, 0xFF111820);
            graphics.fill(x - 1, y - 3, x + 2, y + 4, 0xFF111820);
            graphics.hLine(x - 2, x + 2, y, 0xFFFFFFFF);
            graphics.vLine(x, y - 2, y + 2, 0xFFFFFFFF);
        }
        if (snapshot.cells.isEmpty()) {
            graphics.drawCenteredString(font, "Земли пока не получены", (8 + mapRight) / 2,
                    (44 + mapBottom) / 2 - 10, TEXT);
            graphics.drawCenteredString(font, "R: открыть редактор", (8 + mapRight) / 2,
                    (44 + mapBottom) / 2 + 4, MUTED);
        }
        graphics.disableScissor();
        drawSidebar(graphics);
        // Keep footer help in its own column even at the default 854x480 window/GUI scale.
        drawClipped(graphics, "ЛКМ: выбор | ПКМ: сдвиг | Колесо: масштаб", 8, height - 55,
                mapRight - 8, MUTED);
        String status = !inOverworld() ? "Правка доступна в Верхнем мире."
                : snapshot.truncated ? "Лимит показа. Полная карта: R."
                : "Только просмотр. Изменить границы: R.";
        drawClipped(graphics, status, 8, height - 42, mapRight - 8, 0xFFD4C9A9);
        drawClipped(graphics, "F: все страны", mapRight + 15, height - 55, width - mapRight - 30, MUTED);
        drawClipped(graphics, "Home: к себе", mapRight + 15, height - 42, width - mapRight - 30, MUTED);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (insideMap(mouseX, mouseY)) {
            ChunkPos pos = chunkAt(mouseX, mouseY);
            PoliticalMapSnapshot.Cell cell = snapshot.cells.get(pos.toLong());
            PoliticalMapSnapshot.Country country = cell == null ? null : snapshot.country(cell.claim.owner());
            String text = (country == null ? "Свободная земля" : country.name()) + " | чанк " + pos.x + ", " + pos.z;
            graphics.renderTooltip(font, Component.literal(text), mouseX, mouseY);
        }
    }

    private void drawGrid(GuiGraphics g) {
        int step = pixelsPerChunk >= 8 ? 1 : pixelsPerChunk >= 2 ? 8 : 32;
        double left = worldX(8);
        double right = worldX(mapRight);
        double top = worldZ(44);
        double bottom = worldZ(mapBottom);
        for (double x = Math.floor(left / step) * step; x <= right; x += step) {
            g.vLine(screenX(x), 44, mapBottom, 0x253F6977);
        }
        for (double z = Math.floor(top / step) * step; z <= bottom; z += step) {
            g.hLine(8, mapRight, screenZ(z), 0x253F6977);
        }
    }

    private void drawCountries(GuiGraphics g) {
        for (PoliticalMapSnapshot.Cell cell : snapshot.cells.values()) {
            int x = screenX(cell.x);
            int z = screenZ(cell.z);
            int right = Math.max(x + 1, screenX(cell.x + 1));
            int bottom = Math.max(z + 1, screenZ(cell.z + 1));
            if (right < 8 || x > mapRight || bottom < 44 || z > mapBottom) continue;
            g.fill(x, z, right, bottom, cell.color);
            int borderColor = cell.claim.underSiege() ? 0xFFFF8171 : BORDER;
            if ((cell.edges & 1) != 0) g.vLine(x, z, bottom, borderColor);
            if ((cell.edges & 2) != 0) g.vLine(right, z, bottom, borderColor);
            if ((cell.edges & 4) != 0) g.hLine(x, right, z, borderColor);
            if ((cell.edges & 8) != 0) g.hLine(x, right, bottom, borderColor);
        }
        for (PoliticalMapSnapshot.Country country : snapshot.countries) {
            int x = screenX(country.x() + 0.5);
            int y = screenZ(country.z() + 0.5);
            if (x < 16 || x > mapRight - 8 || y < 50 || y > mapBottom - 12) continue;
            String label = font.plainSubstrByWidth(country.name(), Math.max(30, (mapRight - 8) / 2));
            int textWidth = font.width(label);
            g.fill(x - textWidth / 2 - 3, y - 5, x + textWidth / 2 + 3, y + 6, 0xAA152027);
            g.drawCenteredString(font, label, x, y - 3, TEXT);
        }
    }

    private void drawSelection(GuiGraphics g, ChunkPos pos, int color) {
        if (pos == null) return;
        int x = screenX(pos.x);
        int z = screenZ(pos.z);
        int right = Math.max(x + 2, screenX(pos.x + 1));
        int bottom = Math.max(z + 2, screenZ(pos.z + 1));
        g.hLine(x, right, z, color);
        g.hLine(x, right, bottom, color);
        g.vLine(x, z, bottom, color);
        g.vLine(right, z, bottom, color);
    }

    private void drawSidebar(GuiGraphics g) {
        int x = mapRight + 15;
        int available = width - x - 15;
        g.drawString(font, "Страны", x, 50, TEXT, false);
        int count = visibleCountries();
        for (int row = 0; row < count && row + countryScroll < snapshot.countries.size(); row++) {
            PoliticalMapSnapshot.Country country = snapshot.countries.get(row + countryScroll);
            int y = 66 + row * 18;
            g.fill(x, y, x + 7, y + 7, country.color());
            drawClipped(g, country.name(), x + 12, y, available - 12, TEXT);
            drawClipped(g, "Чанков: " + country.chunks(), x + 12, y + 9, available - 12, MUTED);
        }
        int infoY = Math.max(88, mapBottom - 71);
        g.hLine(x, width - 16, infoY - 5, 0xFF46535A);
        if (selected == null) {
            drawClipped(g, "Выбери чанк", x, infoY, available, TEXT);
            drawClipped(g, "Клик по стране", x, infoY + 14, available, MUTED);
            drawClipped(g, "Список: колесо", x, infoY + 28, available, MUTED);
            return;
        }
        PoliticalMapSnapshot.Cell cell = snapshot.cells.get(selected.toLong());
        drawClipped(g, "Чанк " + selected.x + ", " + selected.z, x, infoY, available, TEXT);
        if (cell == null) {
            drawClipped(g, "Свободная земля", x, infoY + 14, available, MUTED);
            drawClipped(g, "R: правка границ", x, infoY + 28, available, MUTED);
        } else {
            PoliticalMapSnapshot.Country country = snapshot.country(cell.claim.owner());
            drawClipped(g, country == null ? "Неизвестная страна" : country.name(), x, infoY + 14, available, TEXT);
            drawClipped(g, cell.claim.name() == null ? "Территория" : cell.claim.name(), x, infoY + 28, available, MUTED);
            String relation = "Нейтралитет";
            if (ClientManager.ownFaction != null) {
                if (cell.claim.owner().equals(ClientManager.ownFaction.getStringID())) relation = "Твоя страна";
                else {
                    var nativeRelation = ClientManager.getRelation(ClientManager.ownFaction.getStringID(), cell.claim.owner());
                    if (nativeRelation != null) relation = switch (nativeRelation) {
                        case ALLY -> "Союзник";
                        case ENEMY -> "Война";
                        case NEUTRAL -> "Нейтралитет";
                    };
                }
            }
            drawClipped(g, cell.claim.underSiege() ? "Осада | ОЗ " + cell.claim.health() : relation,
                    x, infoY + 42, available, cell.claim.underSiege() ? 0xFFFF8171 : MUTED);
        }
    }

    private int visibleCountries() {
        return Math.max(1, (mapBottom - 148) / 18);
    }

    private void drawClipped(GuiGraphics g, String text, int x, int y, int maxWidth, int color) {
        g.drawString(font, font.plainSubstrByWidth(text, Math.max(0, maxWidth)), x, y, color, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (insideMap(mouseX, mouseY)) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT || button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                panning = true;
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                selected = chunkAt(mouseX, mouseY);
                return true;
            }
        }
        if (button == 0 && mouseX > mapRight + 8 && mouseX < width - 8 && mouseY >= 64) {
            int row = (int) ((mouseY - 64) / 18);
            if (row < visibleCountries() && row + countryScroll < snapshot.countries.size()) {
                var country = snapshot.countries.get(row + countryScroll);
                centerX = country.x() + 0.5;
                centerZ = country.z() + 0.5;
                selected = new ChunkPos(country.x(), country.z());
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (panning) {
            centerX = Mth.clamp(centerX - deltaX / pixelsPerChunk, -1_875_000, 1_875_000);
            centerZ = Mth.clamp(centerZ - deltaY / pixelsPerChunk, -1_875_000, 1_875_000);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (panning) {
            panning = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (insideMap(mouseX, mouseY)) {
            double anchorX = worldX(mouseX);
            double anchorZ = worldZ(mouseY);
            pixelsPerChunk = Mth.clamp(pixelsPerChunk * Math.pow(1.2, amount), MIN_ZOOM, MAX_ZOOM);
            centerX += anchorX - worldX(mouseX);
            centerZ += anchorZ - worldZ(mouseY);
            return true;
        }
        if (mouseX > mapRight && mouseY >= 44 && mouseY < mapBottom) {
            countryScroll = Mth.clamp(countryScroll - (int) Math.signum(amount), 0,
                    Math.max(0, snapshot.countries.size() - visibleCountries()));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (PoliticalMapClient.matchesOpenKey(keyCode, scanCode)) { onClose(); return true; }
        if (keyCode == GLFW.GLFW_KEY_R) { openNativeEditor(); return true; }
        if (keyCode == GLFW.GLFW_KEY_F) { fitCountries(); return true; }
        if (keyCode == GLFW.GLFW_KEY_HOME) { centerOnPlayer(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void openNativeEditor() {
        if (minecraft == null || minecraft.player == null || !inOverworld()) return;
        WorldMapScreen nativeScreen = new WorldMapScreen();
        minecraft.setScreen(nativeScreen);
        if (selected != null) {
            nativeScreen.setSelectedChunk(selected);
            // Recruits exposes its screen camera through centerOnClaim rather than centerOnChunk.
            // This unregistered DTO is a view anchor only: it has no chunks or owner and is never
            // put in ClientManager, a claim manager, saved data, or a network message.
            RecruitsClaim viewAnchor = RecruitsClaim.fromNetwork(new UUID(0, 0), "", null);
            viewAnchor.setCenter(selected);
            nativeScreen.centerOnClaim(viewAnchor);
        }
    }

    private void centerOnPlayer() {
        if (minecraft != null && minecraft.player != null && inOverworld()) {
            centerX = minecraft.player.getX() / 16;
            centerZ = minecraft.player.getZ() / 16;
        } else {
            centerX = centerZ = 0;
        }
    }

    private void fitCountries() {
        if (snapshot == null || snapshot.cells.isEmpty()) { centerOnPlayer(); return; }
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (PoliticalMapSnapshot.Cell cell : snapshot.cells.values()) {
            minX = Math.min(minX, cell.x);
            minZ = Math.min(minZ, cell.z);
            maxX = Math.max(maxX, cell.x + 1);
            maxZ = Math.max(maxZ, cell.z + 1);
        }
        centerX = (minX + (double) maxX) / 2;
        centerZ = (minZ + (double) maxZ) / 2;
        pixelsPerChunk = Mth.clamp(Math.min((mapRight - 40.0) / Math.max(1, maxX - minX),
                (mapBottom - 76.0) / Math.max(1, maxZ - minZ)), MIN_ZOOM, MAX_ZOOM);
    }

    private boolean inOverworld() {
        return minecraft != null && minecraft.level != null && minecraft.level.dimension().equals(Level.OVERWORLD);
    }

    private boolean insideMap(double x, double y) { return x >= 8 && x < mapRight && y >= 44 && y < mapBottom; }
    private double worldX(double x) { return centerX + (x - (8 + mapRight) / 2.0) / pixelsPerChunk; }
    private double worldZ(double z) { return centerZ + (z - (44 + mapBottom) / 2.0) / pixelsPerChunk; }
    private int screenX(double x) { return Mth.floor((8 + mapRight) / 2.0 + (x - centerX) * pixelsPerChunk); }
    private int screenZ(double z) { return Mth.floor((44 + mapBottom) / 2.0 + (z - centerZ) * pixelsPerChunk); }
    private ChunkPos chunkAt(double x, double z) { return new ChunkPos(Mth.floor(worldX(x)), Mth.floor(worldZ(z))); }

    @Override
    public boolean isPauseScreen() { return false; }
}
