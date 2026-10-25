package dev.frontier.industry.mixin.compat;

import dev.latvian.mods.kubejs.script.BindingsEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Avoids Rhino reflecting three client-only signatures exposed by pinned LDLib 1.0.52.a. */
@Mixin(targets = "com.lowdragmc.lowdraglib.kjs.LDLibKubeJSPlugin", remap = false)
public abstract class LDLibDedicatedBindingsMixin {
    @Redirect(method = "registerBindings(Ldev/latvian/mods/kubejs/script/BindingsEvent;)V",
        at = @At(value = "INVOKE", target = "Ldev/latvian/mods/kubejs/script/BindingsEvent;add(Ljava/lang/String;Ljava/lang/Object;)V"),
        require = 1, remap = false)
    private void frontier$registerSideSafeBinding(BindingsEvent event, String name, Object value) {
        // Class names remain strings: the compatibility layer never resolves a client UI type.
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER && value instanceof Class<?> type && switch (name) {
            case "PhantomFluidWidget" -> type.getName().equals("com.lowdragmc.lowdraglib.gui.widget.PhantomFluidWidget");
            case "SceneWidget" -> type.getName().equals("com.lowdragmc.lowdraglib.gui.widget.SceneWidget");
            case "CodeEditorWidget" -> type.getName().equals("com.lowdragmc.lowdraglib.gui.widget.codeeditor.CodeEditorWidget");
            default -> false;
        }) return;
        event.add(name, value);
    }
}
