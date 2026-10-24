package dev.frontier.nations;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** A durable one-time attempt marker, separate from the native country database. */
final class BootstrapState extends SavedData {
    boolean attempted;
    static BootstrapState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(BootstrapState::load,BootstrapState::new,"frontier_pack_bootstrap");
    }
    static BootstrapState load(CompoundTag tag) {
        BootstrapState state=new BootstrapState();state.attempted=tag.getBoolean("attempted");return state;
    }
    @Override public CompoundTag save(CompoundTag tag) {tag.putBoolean("attempted",attempted);return tag;}
}
