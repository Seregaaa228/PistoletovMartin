package dev.frontier.industry.energy;

import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.IndustryEngine;
import dev.frontier.industry.SiteType;
import dev.frontier.industry.engine.Blueprint;
import dev.frontier.industry.engine.IndustryData;
import dev.frontier.industry.engine.IndustryData.Site;
import dev.frontier.industry.engine.IndustryRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** One old industry energy unit is one FE. The existing SavedData owns every balance. */
public final class IndustryEnergy {
    private IndustryEnergy() {}

    public static int receive(IndustryData data, Site site, int maximum, boolean simulate) {
        return EnergyLedger.receive(account(data, site), maximum, simulate);
    }

    public static int extract(IndustryData data, Site site, int maximum, boolean simulate) {
        return EnergyLedger.extract(account(data, site), maximum, simulate);
    }

    public static boolean consume(IndustryData data, Site site, int amount) {
        return EnergyLedger.consume(account(data, site), amount);
    }

    public static int transfer(IndustryData data, Site source, Site target, int maximum) {
        if (source == target) return 0;
        return EnergyLedger.transfer(account(data, source), account(data, target), maximum);
    }

    public static int stored(Site site) {
        return IndustryData.clamp(site.energy, 0, IndustryRules.BUFFER);
    }

    public static Site controllerAt(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return null;
        for (Site site : IndustryData.get(level.getServer()).sites.values()) {
            if (site.dimension.equals(level.dimension().location())
                && Blueprint.controller(site.type, site.origin, site.blueprintVersion).equals(pos)
                && level.getBlockState(pos).is(IndustryContent.controller(site.type))) return site;
        }
        return null;
    }

    /** Reads loaded blocks only, without modifying poll caches or creating chunk tickets. */
    public static boolean available(ServerLevel level, IndustryData data, Site site) {
        if (!healthy(level, site)) return false;
        if (site.type == SiteType.POWER_STATION) return true;
        Site substation = site.type == SiteType.SUBSTATION ? site : upstream(data, site, SiteType.SUBSTATION);
        if (substation == null || !healthy(level, substation)) return false;
        Site station = upstream(data, substation, SiteType.POWER_STATION);
        return station != null && healthy(level, station);
    }

    public static boolean healthy(ServerLevel level, Site site) {
        return site.functional() && IndustryEngine.loaded(level, site)
            && level.getBlockState(Blueprint.controller(site.type, site.origin, site.blueprintVersion)).is(IndustryContent.controller(site.type))
            && criticalIntact(level, site);
    }

    public static boolean criticalIntact(ServerLevel level, Site site) {
        if (site.type.isConsumer() || site.blueprintVersion == Blueprint.LEGACY_VERSION) return true;
        return Blueprint.criticalBlocks(site.type, site.origin, site.blueprintVersion).entrySet().stream()
            .allMatch(entry -> level.hasChunkAt(entry.getKey()) && level.getBlockState(entry.getKey()).equals(entry.getValue()));
    }

    public static GridConnection network(IndustryData data, Site site) {
        if (site.type == SiteType.POWER_STATION) return GridConnection.GENERATOR;
        Site substation = site.type == SiteType.SUBSTATION ? site : upstream(data, site, SiteType.SUBSTATION);
        Site station = substation == null ? null : upstream(data, substation, SiteType.POWER_STATION);
        return site.type == SiteType.SUBSTATION ? GridConnection.distribution(node(station))
            : GridConnection.consumer(node(substation), node(station));
    }

    private static Site upstream(IndustryData data, Site target, SiteType required) {
        Site source = target.upstream == null ? null : data.sites.get(target.upstream);
        return source != null && source.type == required && IndustryRules.linkAllowed(
            source.owner.equals(target.owner), source.dimension.equals(target.dimension),
            source.type.ordinal(), target.type.ordinal(), source.origin.distSqr(target.origin)) ? source : null;
    }

    private static GridConnection.Node node(Site site) {
        return site == null ? null : new GridConnection.Node(site.loaded, site.functional());
    }

    private static EnergyLedger.Account account(IndustryData data, Site site) {
        return new EnergyLedger.Account() {
            @Override public int stored() { return site.energy; }
            @Override public int capacity() { return IndustryRules.BUFFER; }
            @Override public void store(int balance) { site.energy = balance; data.setDirty(); }
        };
    }
}
