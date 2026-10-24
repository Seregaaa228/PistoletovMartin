package dev.frontier.industry.smoke;

import dev.frontier.industry.*;
import dev.frontier.industry.client.*;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = IndustryMod.ID, value = Dist.CLIENT)
public final class RedesignSmokeClient {
    private static int age, wait, doneTicks;
    private static boolean opened, ordered;
    private static String previous = "";
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Boolean.getBoolean("frontier.redesignSmoke")) return;
        Minecraft mc = Minecraft.getInstance();
        if (!opened && mc.screen != null && mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) { mc.options.onboardAccessibility = false; mc.setScreen(new TitleScreen()); }
        if (!opened && mc.screen instanceof TitleScreen && mc.getOverlay() == null && ++wait > 3) { opened = true; mc.createWorldOpenFlows().loadLevel(mc.screen, "Industry-Redesign"); }
        if (mc.player == null || mc.level == null) return;
        String stage = System.getProperty("frontier.redesign.ui", "");
        if (!stage.equals(previous)) { previous = stage; age = 0; }
        age++;
        if (age == 4 && !stage.isBlank()) {
            switch (stage) {
                case "hub" -> System.setProperty("frontier.redesign.hubVisible", String.valueOf(mc.screen instanceof OperationsScreen));
                case "country", "army", "industry", "shop" -> {
                    if (!(mc.screen instanceof OperationsScreen)) OperationsScreen.open();
                    String name = stage.equals("country") ? "Страна" : stage.equals("army") ? "Бойцы" : stage.equals("shop") ? "Техника" : "Заводы";
                    mc.screen.children().stream().filter(w -> w instanceof Button b && b.getMessage().getString().equals(name)).map(w -> (Button)w).findFirst().orElseThrow().onPress();
                    if (stage.equals("shop")) System.setProperty("frontier.redesign.shopVisible", String.valueOf(mc.screen instanceof OperationsScreen));
                }
                case "icons" -> mc.setScreen(new Icons());
                case "buildings" -> mc.setScreen(null);
                case "factory" -> {
                    if (mc.screen instanceof FactoryScreen screen) {
                        System.setProperty("frontier.redesign.factoryVisible", "true");
                        EditBox input = screen.children().stream().filter(w -> w instanceof EditBox).map(w -> (EditBox)w).findFirst().orElseThrow();
                        screen.setFocused(input); input.setFocused(true); input.setValue(""); screen.keyPressed(69, 0, 0); screen.charTyped('e', 0);
                        System.setProperty("frontier.redesign.searchGuard", String.valueOf(mc.screen == screen && input.getValue().equals("e")));
                        input.setValue("minecraft:iron_sword");
                    }
                }
            }
        }
        if (age == 12 && stage.equals("shop") && !Boolean.getBoolean("frontier.redesignReload") && mc.screen instanceof OperationsScreen screen) press(screen, "Купить");
        if (age == 10 && stage.equals("factory") && mc.screen instanceof FactoryScreen screen) press(screen, "Склад");
        if (age == 15 && stage.equals("factory") && mc.screen instanceof FactoryScreen screen) press(screen, "Материалы из инвентаря →");
        if (age == 21 && stage.equals("factory") && mc.screen instanceof FactoryScreen screen) press(screen, "Производство");
        if (age == 27 && stage.equals("factory") && !ordered && mc.screen instanceof FactoryScreen screen) {
            screen.children().stream().filter(w -> w instanceof Button b && b.getMessage().getString().startsWith("Произвести")).map(w -> (Button)w).findFirst().orElseThrow().onPress(); ordered = true;
        }
        if (age == (stage.equals("factory") ? 40 : 28) && !stage.isBlank()) {
            Screenshot.grab(mc.gameDirectory, "redesign-" + stage + ".png", mc.getMainRenderTarget(), c -> System.out.println("REDESIGN_SCREENSHOT " + c.getString()));
            System.setProperty("frontier.redesign.uiDone", stage);
        }
        if (Boolean.getBoolean("frontier.redesign.done") && ++doneTicks > 45) mc.stop();
    }
    private static void press(Screen screen, String label) {
        screen.children().stream().filter(w -> w instanceof Button b && b.getMessage().getString().equals(label)).map(w -> (Button)w).findFirst().orElseThrow().onPress();
    }
    private static final class Icons extends Screen {
        Icons() { super(Component.literal("Промышленность")); }
        @Override public void render(GuiGraphics g, int mx, int my, float partial) {
            renderBackground(g); int sx = (width - 440) / 2, sy = (height - 200) / 2;
            g.fill(sx - 12, sy - 12, sx + 452, sy + 212, 0xFF172C31);
            g.drawCenteredString(font, "НОВЫЕ КОМПЛЕКТЫ И КПК", width / 2, sy, 0xEAF1F4);
            for (SiteType type : SiteType.values()) {
                int col = type.ordinal() % 3, row = type.ordinal() / 3, x = sx + col * 146 + 48, y = sy + 32 + row * 70;
                g.pose().pushPose(); g.pose().translate(x, y, 0); g.pose().scale(2.4f, 2.4f, 2.4f); g.renderItem(new ItemStack(IndustryContent.deployer(type)), 0, 0); g.pose().popPose();
                g.drawCenteredString(font, type.title(), x + 20, y + 42, 0xD3E2E4);
            }
            g.renderItem(new ItemStack(IndustryContent.preferredTablet()), sx + 135, sy + 181); g.renderItem(new ItemStack(IndustryContent.REPAIR_KIT.get()), sx + 285, sy + 181);
            g.drawString(font, "КПК", sx + 158, sy + 185, 0xEAF1F4); g.drawString(font, "Ремнабор", sx + 308, sy + 185, 0xEAF1F4);
        }
        @Override public boolean isPauseScreen() { return false; }
    }
}
