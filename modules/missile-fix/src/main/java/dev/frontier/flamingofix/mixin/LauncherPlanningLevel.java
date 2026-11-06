package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.LauncherPlanning;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The level a launcher is planning in, for the terrain-aware cruise profile during its tick. */
@Mixin(targets = "dev.sbw.deepstrike.game.LauncherBlockEntity", remap = false)
public abstract class LauncherPlanningLevel {
    @Inject(method = "serverTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Ldev/sbw/deepstrike/game/LauncherBlockEntity;)V",
            at = @At("HEAD"), remap = false, require = 1)
    private static void flamingo$enter(Level level, BlockPos pos, BlockState state, @Coerce Object launcher, CallbackInfo callback) {
        LauncherPlanning.enter(level instanceof ServerLevel server ? server : null);
    }

    @Inject(method = "serverTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Ldev/sbw/deepstrike/game/LauncherBlockEntity;)V",
            at = @At("RETURN"), remap = false, require = 1)
    private static void flamingo$leave(Level level, BlockPos pos, BlockState state, @Coerce Object launcher, CallbackInfo callback) {
        LauncherPlanning.leave();
    }
}
