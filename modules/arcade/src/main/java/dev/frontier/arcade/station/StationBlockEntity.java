package dev.frontier.arcade.station;

import dev.frontier.arcade.control.ControlSessions;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

/** Only its placing owner may open or send commands through this physical station. */
public final class StationBlockEntity extends BlockEntity {
    private UUID owner;

    public StationBlockEntity(BlockPos pos, BlockState state) { super(StationContent.TYPE.get(), pos, state); }
    public UUID ownerId() { return owner; }
    public boolean ownedBy(Player player) { return player != null && player.getUUID().equals(owner); }
    public void setOwner(UUID id) { owner = id; setChanged(); }

    public boolean canUse(Player player) {
        return level != null && !isRemoved() && player.isAlive() && !player.isSpectator()
            && player.level() == level && level.getBlockEntity(worldPosition) == this
            && getBlockState().is(StationContent.GROUND_STATION.get()) && ownedBy(player)
            && player.distanceToSqr(Vec3.atCenterOf(worldPosition)) <= 36;
    }

    public void open(ServerPlayer player) {
        if (!canUse(player)) return;
        var entries = StationMenu.boundedSnapshot(ControlSessions.ownedRecon(player, worldPosition));
        var provider = new SimpleMenuProvider((id, inventory, ignored) -> new StationMenu(id, inventory, worldPosition, entries),
            Component.translatable("block.frontier_arcade_vehicles.ground_station"));
        NetworkHooks.openScreen(player, provider, buffer -> StationMenu.writeSnapshot(buffer, worldPosition, entries));
    }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (owner != null) tag.putUUID("OwnerUUID", owner);
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        owner = tag.hasUUID("OwnerUUID") ? tag.getUUID("OwnerUUID") : null;
    }
}
