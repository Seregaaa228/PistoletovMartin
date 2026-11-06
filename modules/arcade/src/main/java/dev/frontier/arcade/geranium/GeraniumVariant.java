package dev.frontier.arcade.geranium;

import dev.frontier.arcade.ArcadeVehicles;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.RegistryObject;
import ru.lavafrai.uncomplicatedfpv.registry.ModSounds;

/**
 * The two station-flown strike aircraft. Both are the same {@link GeraniumEntity} class with
 * their own registry type, so the station, warhead and block-budget rules apply to both; the
 * variant is always derived from the entity type (saved Geranium-2 worlds stay the jet).
 */
public enum GeraniumVariant {
    JET("Герань-2", GeraniumFlightModel.Profile.JET, "geranium_2_jet", 6000),
    PROPELLER("Шахед-136", GeraniumFlightModel.Profile.PROPELLER, "geranium_2", 4000);

    private final String title;
    private final double signalRange;
    private final GeraniumFlightModel.Profile profile;
    private final ResourceLocation model, animation;
    private final String idle, flight;

    GeraniumVariant(String title, GeraniumFlightModel.Profile profile, String asset, double signalRange) {
        this.title = title;
        this.signalRange = signalRange;
        this.profile = profile;
        // GeckoLib queries these per rendered frame; resolve each resource once.
        this.model = new ResourceLocation(ArcadeVehicles.ID, "geo/" + asset + ".geo.json");
        this.animation = new ResourceLocation(ArcadeVehicles.ID, "animations/" + asset + ".animation.json");
        this.idle = "animation." + asset + ".idle";
        this.flight = "animation." + asset + ".flight";
    }

    public static GeraniumVariant of(EntityType<?> type) {
        return GeraniumContent.SHAHED_TYPE.isPresent() && type == GeraniumContent.SHAHED_TYPE.get() ? PROPELLER : JET;
    }

    public String title() { return title; }
    public GeraniumFlightModel.Profile profile() { return profile; }
    /**
     * Station radio budget in blocks (UF's terrain and REB losses still apply on top). A full battery
     * covers three to four times this distance at cruise: 3.3 min for the jet, 5.9 min for the Shahed.
     */
    public double signalRange() { return signalRange; }
    public ResourceLocation model() { return model; }
    public ResourceLocation animation() { return animation; }
    public String animationName(boolean engineRunning) { return engineRunning ? flight : idle; }
    public EntityType<GeraniumEntity> type() { return (this == JET ? GeraniumContent.TYPE : GeraniumContent.SHAHED_TYPE).get(); }
    public Item item() { return (this == JET ? GeraniumContent.ITEM : GeraniumContent.SHAHED_ITEM).get(); }
    /** Exterior and monitor engine loop: our jet event, or Uncomplicated FPV's original Shahed motor. */
    public RegistryObject<SoundEvent> engineSound() { return this == JET ? GeraniumContent.JET_ENGINE : ModSounds.SHAHED136_ENGINE; }
}
