package dev.frontier.arcade.station;

import dev.frontier.arcade.control.ControlSessions.DroneEntry;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** A read-only server snapshot. No inventory slots or portable native monitor are exposed here. */
public final class StationMenu extends AbstractContainerMenu {
    public static final int MAX_ENTRIES = 64;
    private final BlockPos station;
    private final Level stationLevel;
    private final List<DroneEntry> entries;

    public StationMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        this(id, inventory, buffer.readBlockPos(), readEntries(buffer));
    }

    public StationMenu(int id, Inventory inventory, BlockPos station, List<DroneEntry> entries) {
        super(StationContent.MENU.get(), id);
        this.station = station.immutable();
        this.stationLevel = inventory.player.level();
        this.entries = boundedSnapshot(entries);
    }

    public BlockPos stationPosition() { return station; }
    public List<DroneEntry> entries() { return entries; }
    public boolean contains(UUID id) { return entries.stream().anyMatch(entry -> entry.id().equals(id)); }

    public static List<DroneEntry> boundedSnapshot(List<DroneEntry> entries) {
        return entries.stream().limit(MAX_ENTRIES).map(entry -> new DroneEntry(entry.id(),
            boundedText(entry.name(), 64), boundedText(entry.kind(), 32),
            Double.isFinite(entry.distance()) ? Math.max(0, Math.min(30_000_000, entry.distance())) : 0,
            Math.max(-1, Math.min(100, entry.battery())), Math.max(0, Math.min(100, entry.signal())), entry.online())).toList();
    }

    private static String boundedText(String text, int length) { return text.length() > length ? text.substring(0, length) : text; }

    public static void writeSnapshot(FriendlyByteBuf buffer, BlockPos station, List<DroneEntry> entries) {
        var snapshot = boundedSnapshot(entries);
        buffer.writeBlockPos(station);
        buffer.writeVarInt(snapshot.size());
        for (var entry : snapshot) {
            buffer.writeUUID(entry.id()); buffer.writeUtf(entry.name(), 64); buffer.writeUtf(entry.kind(), 32);
            buffer.writeDouble(entry.distance()); buffer.writeVarInt(entry.battery()); buffer.writeVarInt(entry.signal()); buffer.writeBoolean(entry.online());
        }
    }

    private static List<DroneEntry> readEntries(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Invalid station entry count");
        var entries = new java.util.ArrayList<DroneEntry>(count);
        for (int i = 0; i < count; i++) entries.add(new DroneEntry(buffer.readUUID(), buffer.readUtf(64), buffer.readUtf(32),
            buffer.readDouble(), buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean()));
        return entries;
    }

    @Override public boolean stillValid(Player player) {
        return player.level() == stationLevel && (player.level().isClientSide
            || player.level().getBlockEntity(station) instanceof StationBlockEntity entity && entity.canUse(player));
    }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
}
