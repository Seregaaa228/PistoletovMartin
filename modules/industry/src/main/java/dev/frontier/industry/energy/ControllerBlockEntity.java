package dev.frontier.industry.energy;

import dev.frontier.industry.IndustryContent;
import java.util.EnumMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;

/** Stateless controller bridge. IndustryData remains the only persistent site owner. */
public final class ControllerBlockEntity extends BlockEntity {
    private final EnumMap<Direction, LazyOptional<IEnergyStorage>> ports = new EnumMap<>(Direction.class);
    private LazyOptional<IEnergyStorage> general;
    private int capabilityGeneration;
    private boolean capabilitiesActive;

    public ControllerBlockEntity(BlockPos pos, BlockState state) {
        super(IndustryContent.CONTROLLER_ENTITY.get(), pos, state);
        createPorts();
    }

    private void createPorts() {
        capabilityGeneration++;
        capabilitiesActive = true;
        general = LazyOptional.of(() -> new SiteEnergyStorage(this, null));
        for (Direction side : Direction.values()) ports.put(side, LazyOptional.of(() -> new SiteEnergyStorage(this, side)));
    }

    int capabilityGeneration() { return capabilityGeneration; }
    boolean validGeneration(int generation) { return capabilitiesActive && generation == capabilityGeneration; }

    @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
        if (capability == ForgeCapabilities.ENERGY && !isRemoved()) return (side == null ? general : ports.get(side)).cast();
        return super.getCapability(capability, side);
    }

    @Override public void invalidateCaps() {
        super.invalidateCaps();
        capabilitiesActive = false;
        general.invalidate();
        ports.values().forEach(LazyOptional::invalidate);
    }

    @Override public void reviveCaps() {
        super.reviveCaps();
        createPorts();
    }

    @Override public void onLoad() {
        super.onLoad();
        // Forge can reuse a loaded chunk's BE instance after onChunkUnloaded;
        // BlockEntity.clearRemoved alone does not revive its capabilities.
        if (!capabilitiesActive) reviveCaps();
    }
}
