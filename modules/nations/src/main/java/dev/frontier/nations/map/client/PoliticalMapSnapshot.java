package dev.frontier.nations.map.client;

import com.talhanation.recruits.client.ClientManager;
import com.talhanation.recruits.client.gui.worldmap.claim.ClaimRenderer;
import com.talhanation.recruits.world.RecruitsClaim;
import com.talhanation.recruits.world.RecruitsFaction;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Disposable rendering projection of Recruits' already synchronized client state.
 * It never creates claims, writes saved data, requests chunks, or sends packets.
 *
 * <p>Public so the Xaero overlay package can reuse the same projection without
 * duplicating claim reads; the source of truth stays Recruits' ClientManager.</p>
 */
public final class PoliticalMapSnapshot {
    public static final int MAX_CHUNKS = 131_072;
    public static final int MAX_CLAIMS = 4_096;
    public final Map<Long, Cell> cells;
    public final List<Country> countries;
    public final Map<UUID, Claim> claims;
    public final boolean truncated;

    private PoliticalMapSnapshot(Map<Long, Cell> cells, List<Country> countries,
                                 Map<UUID, Claim> claims, boolean truncated) {
        this.cells = cells;
        this.countries = countries;
        this.claims = claims;
        this.truncated = truncated;
    }

    public static PoliticalMapSnapshot capture() {
        Map<Long, Cell> cells = new LinkedHashMap<>();
        Map<UUID, Claim> claims = new HashMap<>();
        Map<String, CountryBuilder> countries = new LinkedHashMap<>();
        boolean truncated = false;
        int claimsScanned = 0;
        int chunksScanned = 0;
        List<RecruitsClaim> source = ClientManager.recruitsClaims;
        if (source != null) {
            for (RecruitsClaim nativeClaim : source) {
                if (++claimsScanned > MAX_CLAIMS) {
                    truncated = true;
                    break;
                }
                if (nativeClaim == null || nativeClaim.isRemoved) continue;
                if (claims.size() >= MAX_CLAIMS || cells.size() >= MAX_CHUNKS) {
                    truncated = true;
                    break;
                }
                RecruitsFaction faction = nativeClaim.getOwnerFaction();
                String owner = faction == null ? "__administration" : faction.getStringID();
                if (owner == null || owner.isBlank()) owner = "claim:" + nativeClaim.getUUID();
                String name = faction == null ? "Администрация" : faction.getTeamDisplayName();
                if (name == null || name.isBlank()) name = owner;
                int color = ClaimRenderer.getClaimColor(nativeClaim) | 0xFF000000;
                CountryBuilder country = countries.computeIfAbsent(owner,
                        key -> new CountryBuilder(key));
                country.name = name;
                country.color = color;
                country.claimCount++;
                Claim claim = new Claim(nativeClaim.getUUID(), owner, nativeClaim.getName(),
                        nativeClaim.isUnderSiege, nativeClaim.getHealth());
                claims.put(claim.id, claim);
                List<ChunkPos> chunks = nativeClaim.getClaimedChunks();
                if (chunks == null) continue;
                int copied = 0;
                for (ChunkPos chunk : chunks) {
                    if (++chunksScanned > MAX_CHUNKS) {
                        truncated = true;
                        break;
                    }
                    if (chunk == null) continue;
                    if (cells.size() >= MAX_CHUNKS) {
                        truncated = true;
                        break;
                    }
                    // Invalid far-away coordinates are ignored; no world lookup is performed.
                    if (Math.abs((long) chunk.x) > 1_875_000L || Math.abs((long) chunk.z) > 1_875_000L) continue;
                    Cell cell = new Cell(chunk.x, chunk.z, claim, color);
                    if (cells.putIfAbsent(ChunkPos.asLong(chunk.x, chunk.z), cell) == null) {
                        country.chunks++;
                        copied++;
                        if (copied == 1 && country.largestClaim == 0) {
                            country.anchorX = chunk.x;
                            country.anchorZ = chunk.z;
                        }
                    }
                }
                if (copied > country.largestClaim && nativeClaim.getCenter() != null) {
                    country.largestClaim = copied;
                    country.anchorX = nativeClaim.getCenter().x;
                    country.anchorZ = nativeClaim.getCenter().z;
                }
                if (chunksScanned > MAX_CHUNKS) break;
            }
        }
        for (Cell cell : cells.values()) {
            cell.edges = (sameOwner(cells, cell.x - 1, cell.z, cell.claim.owner) ? 0 : 1)
                    | (sameOwner(cells, cell.x + 1, cell.z, cell.claim.owner) ? 0 : 2)
                    | (sameOwner(cells, cell.x, cell.z - 1, cell.claim.owner) ? 0 : 4)
                    | (sameOwner(cells, cell.x, cell.z + 1, cell.claim.owner) ? 0 : 8);
        }
        if (ClientManager.factions != null) {
            for (RecruitsFaction faction : ClientManager.factions) {
                if (faction == null || faction.getStringID() == null || faction.getStringID().isBlank()) continue;
                CountryBuilder country = countries.computeIfAbsent(faction.getStringID(), CountryBuilder::new);
                // Claims can retain an older faction snapshot after rename/color changes.
                country.name = faction.getTeamDisplayName();
                if (country.name == null || country.name.isBlank()) country.name = country.id;
                country.color = 0xFF000000 | dev.frontier.nations.flag.TabFlags.color(faction);
            }
        }
        List<Country> ordered = new ArrayList<>();
        for (CountryBuilder country : countries.values()) {
            ordered.add(new Country(country.id, country.name, country.color, country.chunks,
                    country.claimCount, country.anchorX, country.anchorZ));
        }
        ordered.sort(Comparator.comparing(Country::name, String.CASE_INSENSITIVE_ORDER));
        return new PoliticalMapSnapshot(cells, List.copyOf(ordered), Map.copyOf(claims), truncated);
    }

    private static boolean sameOwner(Map<Long, Cell> cells, int x, int z, String owner) {
        Cell neighbor = cells.get(ChunkPos.asLong(x, z));
        return neighbor != null && neighbor.claim.owner.equals(owner);
    }

    public Country country(String id) {
        for (Country country : countries) if (country.id.equals(id)) return country;
        return null;
    }

    public record Claim(UUID id, String owner, String name, boolean underSiege, int health) {}
    public record Country(String id, String name, int color, int chunks, int claims, int x, int z) {}

    public static final class Cell {
        public final int x;
        public final int z;
        public final Claim claim;
        public final int color;
        public int edges;

        Cell(int x, int z, Claim claim, int color) {
            this.x = x;
            this.z = z;
            this.claim = claim;
            this.color = color;
        }
    }

    private static final class CountryBuilder {
        final String id;
        String name;
        int color;
        int chunks;
        int claimCount;
        int largestClaim;
        int anchorX;
        int anchorZ;

        CountryBuilder(String id) { this.id = id; }
    }
}
