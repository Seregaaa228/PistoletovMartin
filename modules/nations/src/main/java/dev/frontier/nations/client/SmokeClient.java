package dev.frontier.nations.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.frontier.nations.FrontierNations;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in local development smoke test. Dormant in normal launcher use. */
@Mod.EventBusSubscriber(modid=FrontierNations.ID,value=Dist.CLIENT)
public final class SmokeClient {
    private static final Logger LOG=LogUtils.getLogger();
    private static final String TEST_FOLDER="Frontier-Test";
    private static final String MAP_SCREEN="dev.frontier.nations.map.client.PoliticalMapScreen";
    private static final String NATIVE_MAP_SCREEN="com.talhanation.recruits.client.gui.worldmap.WorldMapScreen";
    private static final AtomicInteger completedScreenshots=new AtomicInteger();
    private static boolean started;
    private static boolean worldLaunchPending;
    private static boolean ready;
    private static boolean gameplayCaptured;
    private static boolean mapRequested;
    private static boolean mapCaptured;
    private static boolean mapFailureReported;
    private static boolean nativeRequested,nativeCaptured;
    private static boolean stopped;
    private static int titleTicks;
    private static int readyTicks;
    private static int mapOpenedAt=-1;
    private static int nativeOpenedAt=-1;

    private SmokeClient() {}

    @SubscribeEvent
    public static void open(ScreenEvent.Opening event) {
        if(!Boolean.getBoolean("frontier.smokeTest")||started||!(event.getNewScreen() instanceof TitleScreen))return;
        started=true;
        worldLaunchPending=true;
        LOG.info("FRONTIER_TEST: client smoke scheduled for {}",TEST_FOLDER);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if(!Boolean.getBoolean("frontier.smokeTest")||stopped||event.phase!=TickEvent.Phase.END)return;
        Minecraft mc=Minecraft.getInstance();
        if(!started&&mc.screen!=null&&mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) {
            mc.options.onboardAccessibility=false;
            mc.options.save();
            mc.setScreen(new TitleScreen());
            LOG.info("FRONTIER_TEST: completed first-run accessibility welcome with default settings");
        }
        if(!started)return;
        // Opening events precede setScreen's final assignment. Start from a later tick so
        // that the outer TitleScreen assignment cannot replace the loading screen.
        if(worldLaunchPending) {
            if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;
            if(++titleTicks<2)return;
            worldLaunchPending=false;
            openTestWorld(mc,mc.screen);
            return;
        }
        if(!ready) {
            if(mc.player==null||mc.level==null||mc.screen!=null||mc.getOverlay()!=null)return;
            ready=true;
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            LOG.info("FRONTIER_TEST: client world ready; starting 1200-tick observation");
        }
        if(mc.player==null||mc.level==null) {
            LOG.error("FRONTIER_TEST: client lost its test world before completion");
            stop(mc);
            return;
        }
        readyTicks++;
        if(readyTicks>=300&&!gameplayCaptured&&mc.screen==null&&mc.getOverlay()==null) {
            gameplayCaptured=true;
            capture(mc,"frontier-smoke-gameplay.png");
        }
        if(readyTicks>=310&&gameplayCaptured&&!mapRequested&&mc.screen==null) {
            mapRequested=true;
            // Exercise the same registered N-key path as a player; no access to the
            // package-private screen or changes to the political map implementation.
            KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_N));
            LOG.info("FRONTIER_TEST: requested political map through N key");
        }
        boolean politicalMap=mc.screen!=null&&MAP_SCREEN.equals(mc.screen.getClass().getName());
        if(politicalMap&&mapOpenedAt<0) {
            mapOpenedAt=readyTicks;
            LOG.info("FRONTIER_TEST: political map opened at observation tick {}",readyTicks);
        }
        if(politicalMap&&mapOpenedAt>=0&&readyTicks-mapOpenedAt>=40&&!mapCaptured) {
            mapCaptured=true;
            capture(mc,"frontier-smoke-political-map.png");
        }
        if(politicalMap&&mapCaptured&&readyTicks-mapOpenedAt>=60&&!nativeRequested) {
            nativeRequested=true;
            mc.screen.keyPressed(GLFW.GLFW_KEY_R,0,0);
            LOG.info("FRONTIER_TEST: requested native borders through political map R handler");
        }
        boolean nativeMap=mc.screen!=null&&NATIVE_MAP_SCREEN.equals(mc.screen.getClass().getName());
        if(nativeMap&&nativeOpenedAt<0) {
            nativeOpenedAt=readyTicks;
            LOG.info("FRONTIER_TEST: native borders editor opened");
        }
        if(nativeMap&&nativeOpenedAt>=0&&readyTicks-nativeOpenedAt>=40&&!nativeCaptured) {
            nativeCaptured=true;capture(mc,"frontier-smoke-native-map.png");
        }
        if(nativeMap&&nativeCaptured&&readyTicks-nativeOpenedAt>=60) {
            mc.screen.onClose();LOG.info("FRONTIER_TEST: native borders editor closed");
        }
        if(mapRequested&&mapOpenedAt<0&&readyTicks>=390&&!mapFailureReported) {
            mapFailureReported=true;
            LOG.error("FRONTIER_TEST: political map did not open after its N-key request; screen={}",
                    mc.screen==null?"none":mc.screen.getClass().getName());
        }
        if(readyTicks>=1200) {
            LOG.info("FRONTIER_TEST: client observation complete; gameplay={} map={} nativeEditor={} screenshotCallbacks={}",
                    gameplayCaptured,mapCaptured,nativeCaptured,completedScreenshots.get());
            stop(mc);
        }
    }

    private static void openTestWorld(Minecraft mc,Screen parent) {
        if(Files.exists(mc.gameDirectory.toPath().resolve("saves").resolve(TEST_FOLDER).resolve("level.dat"))) {
            LOG.info("FRONTIER_TEST: loading existing development test world {}",TEST_FOLDER);
            mc.createWorldOpenFlows().loadLevel(parent,TEST_FOLDER);
        } else {
            LOG.info("FRONTIER_TEST: creating development test world {} with seed 20261023",TEST_FOLDER);
            LevelSettings settings=new LevelSettings("Frontier Test",GameType.CREATIVE,false,Difficulty.NORMAL,true,
                    new GameRules(),WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(TEST_FOLDER,settings,new WorldOptions(20261023L,true,false),
                    WorldPresets::createNormalWorldDimensions);
        }
    }

    private static void capture(Minecraft mc,String name) {
        LOG.info("FRONTIER_TEST: capturing {} at observation tick {}",name,readyTicks);
        Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),message->{
            completedScreenshots.incrementAndGet();
            LOG.info("FRONTIER_TEST: screenshot {} result: {}",name,message.getString());
        });
    }

    private static void stop(Minecraft mc) {
        stopped=true;
        LOG.info("FRONTIER_TEST: stopping development client cleanly");
        mc.stop();
    }
}
