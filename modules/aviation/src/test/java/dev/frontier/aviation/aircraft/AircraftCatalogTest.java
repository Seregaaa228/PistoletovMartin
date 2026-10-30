package dev.frontier.aviation.aircraft;

import com.google.gson.JsonParser;
import dev.frontier.aviation.control.FlightKind;
import dev.frontier.aviation.control.FlightProfile;
import dev.frontier.aviation.control.FlightProfiles;
import dev.frontier.aviation.handling.HandlingProfile;
import dev.frontier.aviation.weapons.WeaponKind;
import dev.frontier.aviation.weapons.WeaponLoadout;
import dev.frontier.aviation.weapons.WeaponMount;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Catalog-to-native resource contracts and extension isolation. Does not initialize Forge registries. */
public final class AircraftCatalogTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        check(AircraftCatalog.all().size() == 12, "all existing controlled IDs retained");
        check(AircraftCatalog.models().stream().map(AircraftDefinition::typeId).toList().equals(List.of(
            "frontier_aviation:su25", "frontier_aviation:f16", "frontier_aviation:j10")), "stable owned IDs and ordering");
        for (var definition : AircraftCatalog.all()) {
            check(FlightProfiles.find(definition.typeId()).orElseThrow().equals(definition.flight()), "one flight lookup");
            check(definition.registersModel() == definition.destroyOnDeath(), "native lifetime untouched");
            if (!definition.registersModel()) {
                check(definition.handling().nativeOnly(), "native-only handling retained");
                check(definition.weapons().mounts().isEmpty(), "external native weapons not replaced");
                continue;
            }
            String id = definition.typeId().split(":", 2)[1];
            Path resources = Path.of("modules/aviation/src/main/resources");
            var nativeData = JsonParser.parseString(Files.readString(resources.resolve(
                "data/frontier_aviation/sbw/vehicles/" + id + ".json"))).getAsJsonObject();
            var guns = nativeData.getAsJsonObject("Weapons");
            check(definition.flight().kind() == FlightKind.AIRCRAFT, "owned jets remain aircraft");
            check(guns.size() == definition.weapons().mounts().size(), "every manual native gun has a slot");
            for (var mount : definition.weapons().mounts()) {
                check(guns.has(mount.nativeGun()), "slot resolves to real native named gun");
                var gun = guns.getAsJsonObject(mount.nativeGun());
                check(gun.get("Magazine").getAsInt() >= mount.maximumBurst(), "burst fits magazine");
                check(mount.minimumIntervalTicks() >= Math.ceil(1200.0 / gun.get("RPM").getAsDouble()),
                    "intent cadence cannot exceed native RPM");
                check(mount.kind() != WeaponKind.GUIDED_MISSILE
                    || gun.get("Projectile").getAsString().startsWith("superbwarfare:"), "guided launcher matches native projectile");
            }
            check(Files.isRegularFile(resources.resolve("assets/frontier_aviation/sbw/vehicles/" + id + ".json")),
                "client model definition exists");
            check(definition.model().width() == 8 && definition.model().height() == 6.5f, "accepted entity dimensions retained");
        }
        var fighter = AircraftCatalog.find("frontier_aviation:f16").orElseThrow();
        check(fighter.weapons().find("missile").orElseThrow().maximumBurst() == 2, "F16 paired trigger");
        check(fighter.weapons().nativeGun("AAMissile").orElseThrow().id().equals("aamissile"),
            "large AAM native name is stable");
        check(fighter.weapons().nativeGun("LightAAMissile").orElseThrow().id().equals("light_aamissile"),
            "medium AAM native name is stable");
        check(fighter.weapons().find("medium_agmissile").orElseThrow().nativeGun().equals("MediumAGMissile"),
            "medium AGM has a stable separate native slot");
        check(AircraftCatalog.find("frontier_aviation:su25").orElseThrow().weapons().find("missile").isEmpty(),
            "cannon-only model cannot acquire another model's missile");
        check(AircraftCatalog.find("test:unknown").isEmpty(), "unknown models require a definition");
        check(FlightProfiles.find("superbwarfare:ah_6").orElseThrow().kind() == FlightKind.HELICOPTER,
            "helicopter kind retained");

        var customized = new AircraftDefinition("test:training", new FlightProfile("training", FlightKind.AIRCRAFT, 9, 5, 30),
            HandlingProfile.ATTACK, WeaponLoadout.CANNON_ONLY, true,
            new AircraftModel(4, 3, List.of(new AircraftModel.Exhaust(0, 1, -2)), .8f));
        var index = AircraftCatalog.index(List.of(fighter, customized));
        check(index.get("test:training").flight().responseTicks() == 9, "new model owns its response");
        check(index.get("test:training").handling() == HandlingProfile.ATTACK, "new model owns its handling");
        check(index.get("test:training").weapons().find("missile").isEmpty(), "new model owns its loadout");
        rejects(() -> AircraftCatalog.index(List.of(fighter, fighter)), "duplicate aircraft ID");
        rejects(() -> new WeaponLoadout(List.of(WeaponLoadout.CANNON, WeaponLoadout.CANNON)), "duplicate weapon slot");
        rejects(() -> new WeaponLoadout(List.of(WeaponLoadout.CANNON,
            new WeaponMount("alternate", "Cannon", WeaponKind.CANNON, 1, 1, 256))), "duplicate native gun");
        rejects(() -> new WeaponMount("invalid slot", "Cannon", WeaponKind.CANNON, 1, 1, 256), "invalid slot ID");
        rejects(() -> new WeaponMount("bad", "Cannon", WeaponKind.CANNON, 1, 1, Double.NaN), "invalid range");
        var mutable = new ArrayList<>(List.of(WeaponLoadout.CANNON));
        var immutable = new WeaponLoadout(mutable);
        mutable.clear();
        check(immutable.mounts().size() == 1, "caller cannot mutate another aircraft loadout");
        try {
            immutable.mounts().clear();
            throw new AssertionError("mutable shared loadout");
        } catch (UnsupportedOperationException expected) { assertions++; }
        System.out.println("AircraftCatalogTest: " + assertions + " contracts passed");
    }

    private static void rejects(Runnable action, String name) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError(name);
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        assertions++;
    }
}
