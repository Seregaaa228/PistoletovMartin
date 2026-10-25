package dev.frontier.industry.integrationtests;

import com.google.gson.JsonParser;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import dev.frontier.industry.client.FactoryScreen;
import dev.frontier.industry.client.OperationsScreen;
import dev.frontier.industry.client.ui.LdlibWidgetHost;
import dev.frontier.industry.client.ui.UiViewport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

/** Explicitly installed test-only client; no annotation scanning or production entry point. */
public final class Pis1SmokeClient {
    private static boolean initialized, launching, capturing, failed, stopped, configured, orderClicked, readyAnnounced, benchmarkConfigured;
    private static int titleTicks, stageTicks, readyTicks, benchmarkPauseEpoch;
    private static double benchmarkX, benchmarkY, benchmarkZ;
    private static String stage = "";
    private Pis1SmokeClient() {}
    public static void init() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.addListener(Pis1SmokeClient::tick);
    }
    private static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Boolean.getBoolean("frontier.pis1Smoke") || stopped) return;
        var mc = Minecraft.getInstance();
        try {
            if (!launching && mc.screen != null && mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) {
                mc.options.onboardAccessibility = false; mc.setScreen(new TitleScreen());
            }
            if (!launching && mc.screen instanceof TitleScreen && mc.getOverlay() == null && ++titleTicks >= 3) {
                launching = true; launch(mc); return;
            }
            if (Boolean.getBoolean("frontier.pis1Stop")) { stopped = true; mc.stop(); return; }
            if (failed || mc.player == null || mc.level == null || mc.getOverlay() != null) return;
            if (Boolean.getBoolean("frontier.pis1BenchmarkActive") && !benchmarkConfigured) {
                benchmarkConfigured = true;
                mc.options.framerateLimit().set(60);
                mc.options.enableVsync().set(false);
                mc.options.renderDistance().set(8);
                mc.options.guiScale().set(2);
                mc.resizeDisplay();
                benchmarkX = Double.parseDouble(System.getProperty("frontier.pis1CameraX"));
                benchmarkY = Double.parseDouble(System.getProperty("frontier.pis1CameraY"));
                benchmarkZ = Double.parseDouble(System.getProperty("frontier.pis1CameraZ"));
                mc.mouseHandler.releaseMouse();
                System.setProperty("frontier.pis1BenchmarkSettings", "1280x720;fpsLimit60;vsyncfalse;renderDistance8;guiScale2");
            }
            if (Boolean.getBoolean("frontier.pis1BenchmarkActive")) {
                if (mc.screen != null) {
                    mc.setScreen(null);
                    System.setProperty("frontier.pis1BenchmarkPauseEpoch", Integer.toString(++benchmarkPauseEpoch));
                }
                // A benchmark camera must not drift from NPC collisions or
                // incidental physical mouse movement between the two profiles.
                mc.player.setPos(benchmarkX, benchmarkY, benchmarkZ);
                mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                mc.player.setYRot(0); mc.player.setXRot(5);
                mc.player.yRotO = 0; mc.player.xRotO = 5;
            }
            System.setProperty("frontier.pis1ClientReady", "true");
            if (!readyAnnounced) {
                readyAnnounced = true;
                System.out.println("PIS1_CLIENT_READY player=" + mc.player.getGameProfile().getName());
            }
            if (++readyTicks > 20000) { fail("Client observation exceeded 20000 ticks"); return; }
            String requested = System.getProperty("frontier.pis1UiStage", "");
            if (!requested.equals(stage)) {
                stage = requested; stageTicks = 0; capturing = configured = orderClicked = false;
                if (stage.isBlank()) return;
                String[] split = stage.split("@", 2);
                if (!split[0].matches("[a-z][a-z0-9-]{0,47}")) throw new IllegalArgumentException("Invalid UI stage name");
                int scale = split.length == 1 || split[1].equals("auto") ? 0 : Integer.parseInt(split[1]);
                if (scale != 0 && scale != 2 && scale != 3) throw new IllegalArgumentException("Unsupported screenshot GUI scale");
                mc.options.guiScale().set(scale); mc.resizeDisplay();
            }
            if (stage.isBlank() || stage.equals(System.getProperty("frontier.pis1UiDone", ""))) return;
            stageTicks++;
            String kind = stage.split("@", 2)[0];
            boolean correctScreen;
            if (kind.startsWith("world-")) {
                if (!configured) { mc.setScreen(null); configured = true; }
                correctScreen = mc.screen == null;
                if (kind.startsWith("world-jade-")) correctScreen &= UiDriver.observeJade(mc);
            } else if (Boolean.getBoolean("frontier.pis1BenchmarkOnly")) {
                fail("UI stages are unavailable in the baseline-only benchmark"); return;
            } else correctScreen = UiDriver.tick(mc, kind);
            if (stageTicks >= 32 && configured && correctScreen && !capturing) {
                capturing = true;
                String capturedStage = stage;
                String filename = "pis1-" + capturedStage.replace('@', '-') + ".png";
                Screenshot.grab(mc.gameDirectory, filename, mc.getMainRenderTarget(), message -> {
                    System.out.println("PIS1_UI_SCREENSHOT " + filename + " " + message.getString());
                    Path screenshot = mc.gameDirectory.toPath().resolve("screenshots").resolve(filename);
                    if (!Files.isRegularFile(screenshot)) fail("Screenshot was not written: " + filename);
                    else {
                        System.setProperty("frontier.pis1UiDone", capturedStage);
                        if (capturedStage.startsWith("guide@")) mc.execute(() -> {
                            if (mc.screen != null && mc.screen.getClass().getName().startsWith("vazkii.patchouli.")) mc.setScreen(null);
                        });
                    }
                });
            }
            if (stageTicks > 600 && !stage.equals(System.getProperty("frontier.pis1UiDone", ""))) fail("UI did not finish requested stage " + stage);
        } catch (Exception error) { fail(error.toString()); error.printStackTrace(); }
    }
    private static void launch(Minecraft mc) throws Exception {
        String runId = System.getProperty("frontier.pis1RunId", "");
        if (!runId.matches("[A-Za-z0-9_-]{8,64}")) throw new IllegalStateException("Missing or invalid PIS1 runId");
        Path directory = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        var profile = JsonParser.parseString(Files.readString(directory.resolve("test-profile.json"))).getAsJsonObject();
        if (!profile.has("worldsCopied") || profile.get("worldsCopied").getAsBoolean()
                || !runId.equals(profile.get("runId").getAsString())) throw new IllegalStateException("Refusing a non-matching test profile");
        String server = System.getProperty("frontier.pis1Server", "").strip();
        if (!server.isEmpty()) {
            ServerAddress address = loopbackServer(server);
            String host = address.getHost();
            String endpoint = (host.contains(":") ? "[" + host + "]" : host) + ":" + address.getPort();
            ConnectScreen.startConnecting(mc.screen, mc, address, new ServerData("PIS1 local test", endpoint, false), false);
            return; // Network mode must never inspect or create a local save.
        }
        String folder = "Pis1-Test-" + runId;
        Path world = directory.resolve("saves").resolve(folder), marker = directory.resolve("pis1-" + runId + ".marker");
        if (Boolean.getBoolean("frontier.pis1Restart")) {
            if (!Files.isRegularFile(world.resolve("level.dat")) || !Files.isRegularFile(marker)
                    || !Files.readString(marker).equals(runId)) throw new IllegalStateException("Restart requires this exact dedicated test world");
            mc.createWorldOpenFlows().loadLevel(mc.screen, folder);
        } else {
            if (Files.exists(world) || Files.exists(marker)) throw new IllegalStateException("Fresh test world already exists");
            Files.writeString(marker, runId, StandardOpenOption.CREATE_NEW);
            var settings = new LevelSettings(folder, GameType.CREATIVE, false, Difficulty.NORMAL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(folder, settings, new WorldOptions(20261025L, true, false), WorldPresets::createNormalWorldDimensions);
        }
    }
    /** Literal loopback only; localhost is canonicalized before Minecraft can perform DNS/SRV lookup. */
    private static ServerAddress loopbackServer(String server) {
        int separator = server.lastIndexOf(':');
        if (separator < 1) throw new IllegalArgumentException("PIS1 server must include a loopback host and port");
        String host = server.substring(0, separator), portText = server.substring(separator + 1);
        if (!portText.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid PIS1 server port");
        int port = Integer.parseInt(portText);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid PIS1 server port");
        if (host.equalsIgnoreCase("localhost")) host = "127.0.0.1";
        else if (host.equals("[::1]") || host.equals("::1")) host = "::1";
        else {
            if (!host.matches("127\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}"))
                throw new IllegalArgumentException("PIS1 server must be a literal loopback address");
            String[] octets = host.split("\\.");
            for (int i = 0; i < octets.length; i++) {
                int octet = Integer.parseInt(octets[i]);
                if (octet > 255) throw new IllegalArgumentException("Invalid PIS1 loopback address");
                octets[i] = Integer.toString(octet);
            }
            host = String.join(".", octets);
        }
        return new ServerAddress(host, port);
    }
    /** Never loaded for baseline worlds/benchmarks: baseline has no LDLib or new Industry UI classes. */
    private static final class UiDriver {
        private static boolean literalChecked;
        private static boolean observeJade(Minecraft mc) {
            mc.player.setPos(Double.parseDouble(System.getProperty("frontier.pis1JadeCameraX")),
                Double.parseDouble(System.getProperty("frontier.pis1JadeCameraY")),
                Double.parseDouble(System.getProperty("frontier.pis1JadeCameraZ")));
            mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            float yaw = Float.parseFloat(System.getProperty("frontier.pis1JadeCameraYaw"));
            float pitch = Float.parseFloat(System.getProperty("frontier.pis1JadeCameraPitch"));
            mc.player.setYRot(yaw); mc.player.yRotO = yaw; mc.player.setXRot(pitch); mc.player.xRotO = pitch;
            var expected = new net.minecraft.core.BlockPos(Integer.parseInt(System.getProperty("frontier.pis1JadeTargetX")),
                Integer.parseInt(System.getProperty("frontier.pis1JadeTargetY")), Integer.parseInt(System.getProperty("frontier.pis1JadeTargetZ")));
            if (!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit) || !hit.getBlockPos().equals(expected)
                || !(snownee.jade.impl.ObjectDataCenter.get() instanceof snownee.jade.api.BlockAccessor accessor)
                || !accessor.getPosition().equals(expected)) return false;
            var wire = accessor.getServerData();
            if (!wire.contains("FrontierIndustry", 10)) return false;
            var state = wire.getCompound("FrontierIndustry");
            if (!state.contains("powered", 1) || !state.contains("energy", 3) || state.getString("status").isBlank()
                || state.getBoolean("powered") != Boolean.parseBoolean(System.getProperty("frontier.pis1JadeExpectedPowered"))) return false;
            if (!stage.equals(System.getProperty("frontier.pis1JadeObservedStage", ""))) {
                System.setProperty("frontier.pis1JadeObservedStage", stage);
                System.out.println("PIS1_JADE_CLIENT " + stage + " actual server payload " + state);
            }
            return true;
        }
        private static boolean tick(Minecraft mc, String kind) throws ReflectiveOperationException {
            if (!literalChecked) { checkLiteralLabels(); literalChecked = true; }
            boolean factory = kind.startsWith("factory");
            if (stageTicks == 3 && !factory) OperationsScreen.open();
            if (stageTicks >= 7 && !configured && !factory && mc.screen instanceof OperationsScreen) {
                String tab = switch (kind) {
                    case "country" -> "Страна"; case "army" -> "Армия";
                    case "production" -> "Производство"; case "repair" -> "Ремонт";
                    case "guide" -> "Справочник";
                    default -> throw new IllegalArgumentException("Unknown UI stage " + stage);
                };
                click(mc, tab);
                if (!kind.equals("guide")) {
                    var selected = OperationsScreen.class.getDeclaredField("tab"); selected.setAccessible(true);
                    int expected = switch (kind) { case "country" -> 0; case "army" -> 1; case "production" -> 2; case "repair" -> 3; default -> -1; };
                    if (selected.getInt(mc.screen) != expected) throw new IllegalStateException("Actual mouse input did not select " + kind);
                }
                configured = true;
            }
            if (factory && stageTicks >= 9 && !configured && mc.screen instanceof FactoryScreen) {
                if (kind.equals("factory")) {
                    var viewport = UiViewport.fit(mc.screen.width, mc.screen.height, 356, 268);
                    // Reproduce the confirmed long-tooltip/JEI overlap at the
                    // right edge of the first product row at every GUI scale.
                    movePointer(mc, viewport.screenX((mc.screen.width - 356) / 2 + 330),
                        viewport.screenY((mc.screen.height - 268) / 2 + 99));
                } else if (kind.equals("factory-search") || kind.equals("factory-order")) {
                    var field = (TextFieldWidget)host(mc).find("product_search");
                    clickWidget(mc, field);
                    field.setCurrentString(""); mc.screen.keyPressed(69, 0, 0); mc.screen.charTyped('e', 0);
                    System.setProperty("frontier.pis1SearchGuard", Boolean.toString(mc.screen instanceof FactoryScreen && field.getCurrentString().equals("e")));
                    field.setCurrentString("");
                    for (char c : System.getProperty("frontier.pis1RecipeId", "minecraft:iron_sword").toCharArray()) mc.screen.charTyped(c, 0);
                } else if (kind.equals("factory-storage")) click(mc, "Склад");
                else if (kind.equals("factory-management")) click(mc, "Управление");
                configured = true;
            }
            if (kind.equals("factory-order") && configured && !orderClicked && stageTicks >= 15 && mc.screen instanceof FactoryScreen) {
                click(mc, "В очередь"); orderClicked = true; System.setProperty("frontier.pis1OrderClicked", "true");
            }
            return factory ? mc.screen instanceof FactoryScreen
                : kind.equals("guide") ? mc.screen != null && mc.screen.getClass().getName().startsWith("vazkii.patchouli.")
                : mc.screen instanceof OperationsScreen;
        }
        private static void checkLiteralLabels() throws ReflectiveOperationException {
            java.util.concurrent.atomic.AtomicReference<String> value = new java.util.concurrent.atomic.AtomicReference<>("Генерация 128 FE/т · 100%");
            var widget = dev.frontier.industry.client.ui.IndustryWidgets.text(0, 0, 180, 18, value::get, -1);
            var texture = (com.lowdragmc.lowdraglib.gui.texture.TextTexture)widget.getImage();
            texture.updateTick();
            if (!texture.text.equals(value.get())) throw new IllegalStateException("Literal status percent was formatted: " + texture.text);
            value.set("Ремонт: 50% · %s · %n · %%");
            texture.updateText(value.get());
            if (!texture.text.equals(value.get())) throw new IllegalStateException("Literal updated percent was formatted: " + texture.text);
            texture.setWidth(50);
            if (!texture.text.equals(value.get()) || texture.getLines() <= 1) throw new IllegalStateException("Literal wrapping changed the label");
            var button = new dev.frontier.industry.client.ui.IndustryWidgets.ActionButton(0, 0, 100, 18, "Ремонт 100%", () -> {});
            var field = button.getClass().getDeclaredField("label"); field.setAccessible(true);
            var label = (com.lowdragmc.lowdraglib.gui.texture.TextTexture)field.get(button);
            if (!label.text.equals("Ремонт 100%")) throw new IllegalStateException("Literal button percent was formatted");
            button.message("Готово: 75% · %1$s");
            if (!label.text.equals("Готово: 75% · %1$s")) throw new IllegalStateException("Literal updated button was formatted");
            System.setProperty("frontier.pis1LiteralText", "true");
            System.out.println("PIS1_LITERAL_TEXT 5 status/button percent and wrapping checks PASS");
        }
        private static LdlibWidgetHost host(Minecraft mc) throws ReflectiveOperationException {
            var field = mc.screen.getClass().getDeclaredField("widgets"); field.setAccessible(true);
            return (LdlibWidgetHost)field.get(mc.screen);
        }
        private static void click(Minecraft mc, String id) throws ReflectiveOperationException { clickWidget(mc, host(mc).find(id)); }
        private static void clickWidget(Minecraft mc, Widget widget) throws ReflectiveOperationException {
            if (widget == null) throw new IllegalStateException("Missing widget for " + stage);
            boolean factory = mc.screen instanceof FactoryScreen;
            var viewport = UiViewport.fit(mc.screen.width, mc.screen.height, factory ? 356 : 560, factory ? 268 : 342);
            double x = viewport.screenX(widget.getPositionX() + widget.getSizeWidth() / 2.0);
            double y = viewport.screenY(widget.getPositionY() + widget.getSizeHeight() / 2.0);
            // LDLib ClickData obtains the active button from Minecraft's
            // MouseHandler. Exercise the real callback route, including Forge
            // input hooks, instead of bypassing it with Screen.mouseClicked.
            var window = mc.getWindow();
            var press = mouseCallback("onPress", "m_91530_", long.class, int.class, int.class, int.class);
            movePointer(mc, x, y);
            press.invoke(mc.mouseHandler, window.getWindow(), 0, 1, 0);
            press.invoke(mc.mouseHandler, window.getWindow(), 0, 0, 0);
        }
        private static void movePointer(Minecraft mc, double x, double y) throws ReflectiveOperationException {
            var window = mc.getWindow();
            var move = mouseCallback("onMove", "m_91561_", long.class, double.class, double.class);
            move.invoke(mc.mouseHandler, window.getWindow(), x * window.getScreenWidth() / mc.screen.width,
                y * window.getScreenHeight() / mc.screen.height);
        }
        private static java.lang.reflect.Method mouseCallback(String development, String runtime, Class<?>... parameters) throws NoSuchMethodException {
            // Exact official/SRG aliases from the pinned 1.20.1 mappings:
            // onScroll shares onMove's signature, so signature alone is unsafe.
            java.lang.reflect.Method method;
            try { method = net.minecraft.client.MouseHandler.class.getDeclaredMethod(runtime, parameters); }
            catch (NoSuchMethodException developmentEnvironment) {
                method = net.minecraft.client.MouseHandler.class.getDeclaredMethod(development, parameters);
            }
            method.setAccessible(true); return method;
        }
    }
    private static void fail(String reason) {
        failed = true; System.setProperty("frontier.pis1UiFailure", reason); System.err.println("PIS1_UI_FAIL " + reason);
    }
}
