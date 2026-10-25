package dev.frontier.flamingofix;
import net.minecraftforge.fml.common.Mod;
@Mod("frontier_flamingo_launch_fix")
public final class FlamingoLaunchFix {
    public FlamingoLaunchFix() {
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.SERVER,
                MissileImpactConfig.SPEC,"frontier-missiles-server.toml");
    }
}
