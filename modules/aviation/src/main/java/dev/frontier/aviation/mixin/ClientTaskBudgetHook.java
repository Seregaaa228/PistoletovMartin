package dev.frontier.aviation.mixin;

import dev.frontier.aviation.client.ClientTaskBudget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The once-per-frame drain of the client task queue in Minecraft.runTick runs within
 * ClientTaskBudget while the player is in a world. Joining, respawning and changing
 * dimension (no player yet, or the terrain screen) keep vanilla's full drain so loading
 * is never slower.
 */
@Mixin(value = Minecraft.class, remap = false)
public abstract class ClientTaskBudgetHook {
    @Unique private final ClientTaskBudget aviation$taskBudget = new ClientTaskBudget();

    @Redirect(method = "m_91383_(Z)V", remap = false, require = 1,
            at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/client/Minecraft;m_18699_()V"))
    private void aviation$runTasksWithinBudget(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null || minecraft.screen instanceof ReceivingLevelScreen) {
            while (minecraft.pollTask()) { }
            return;
        }
        ClientTaskBudget.run(minecraft::pollTask, System::nanoTime,
            aviation$taskBudget.budget(minecraft.getPendingTasksCount()));
        aviation$taskBudget.finished(minecraft.getPendingTasksCount());
    }
}
