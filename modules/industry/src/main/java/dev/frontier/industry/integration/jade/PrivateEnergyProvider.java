package dev.frontier.industry.integration.jade;

import dev.frontier.industry.energy.ControllerBlockEntity;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.Identifiers;
import snownee.jade.api.view.IServerExtensionProvider;
import snownee.jade.api.view.ViewGroup;

/** Generic FE introspection has no player authorization. Only our filtered payload may publish it. */
public enum PrivateEnergyProvider implements IServerExtensionProvider<ControllerBlockEntity, CompoundTag>, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    @Override public List<ViewGroup<CompoundTag>> getGroups(ServerPlayer player, ServerLevel world,
                                                          ControllerBlockEntity target, boolean showDetails) {
        // Empty, rather than null, stops the normal universal extension fallback.
        return List.of();
    }

    @Override public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        scrub(data);
    }

    public static void scrub(CompoundTag data) {
        data.remove("JadeEnergyStorage");
        data.remove("JadeEnergyStorageUid");
    }

    @Override public ResourceLocation getUid() {
        // Jade 11.13.3 sorts inherited data providers stably by this UID's shared
        // priority. Its base BlockEntity provider runs first, then this controller
        // scrubber, even when an operator changes the tooltip sort configuration.
        return Identifiers.UNIVERSAL_ENERGY_STORAGE;
    }
}
