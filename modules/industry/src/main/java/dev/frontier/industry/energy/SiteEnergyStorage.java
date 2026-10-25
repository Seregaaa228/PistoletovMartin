package dev.frontier.industry.energy;

import dev.frontier.industry.engine.IndustryData;
import dev.frontier.industry.engine.IndustryData.Site;
import dev.frontier.industry.engine.IndustryRules;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.energy.IEnergyStorage;

/** A live view, never a second EnergyStorage or a second serialized balance. */
public final class SiteEnergyStorage implements IEnergyStorage {
    private final ControllerBlockEntity controller;
    private final Direction side;
    private final int generation;

    SiteEnergyStorage(ControllerBlockEntity controller, Direction side) {
        this.controller = controller;
        this.side = side;
        generation = controller.capabilityGeneration();
    }

    private Site resolve() {
        if (!controller.validGeneration(generation) || controller.isRemoved() || !(controller.getLevel() instanceof ServerLevel level)
            || !level.hasChunkAt(controller.getBlockPos())) return null;
        Site site = IndustryEnergy.controllerAt(level, controller.getBlockPos());
        if (site == null || !IndustryEnergy.available(level, IndustryData.get(level.getServer()), site)) return null;
        return site;
    }

    @Override public int receiveEnergy(int maximum, boolean simulate) {
        Site site = resolve();
        if (site == null || !EnergyRole.of(site.type).receives(side)) return 0;
        var level = (ServerLevel) controller.getLevel();
        return IndustryEnergy.receive(IndustryData.get(level.getServer()), site, Math.min(maximum, IndustryRules.GENERATION), simulate);
    }

    @Override public int extractEnergy(int maximum, boolean simulate) {
        Site site = resolve();
        if (site == null || !EnergyRole.of(site.type).extracts(side)) return 0;
        var level = (ServerLevel) controller.getLevel();
        return IndustryEnergy.extract(IndustryData.get(level.getServer()), site, Math.min(maximum, IndustryRules.GENERATION), simulate);
    }

    @Override public int getEnergyStored() {
        Site site = resolve();
        return site == null ? 0 : IndustryEnergy.stored(site);
    }

    @Override public int getMaxEnergyStored() { return IndustryRules.BUFFER; }

    @Override public boolean canExtract() {
        Site site = resolve();
        return site != null && EnergyRole.of(site.type).extracts(side);
    }

    @Override public boolean canReceive() {
        Site site = resolve();
        return site != null && EnergyRole.of(site.type).receives(side);
    }
}
