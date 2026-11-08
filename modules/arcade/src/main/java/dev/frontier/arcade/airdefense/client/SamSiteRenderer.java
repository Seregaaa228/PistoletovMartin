package dev.frontier.arcade.airdefense.client;

import com.atsuishio.superbwarfare.client.model.entity.VehicleModelInstance;
import com.atsuishio.superbwarfare.client.renderer.entity.BasicVehicleRenderer;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.frontier.arcade.airdefense.SamSiteEntity;
import dev.frontier.arcade.airdefense.SamSystem;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.Mth;

/**
 * SBW's vehicle renderer with the complexes' own moving parts (bones from sam_assets.py), all
 * driven by the site's synced state:
 * <ul>
 *   <li>S-125: turntable and launch beams follow the aim, each missile disappears when fired,
 *       the guidance antenna sweeps and nods in AUTO;</li>
 *   <li>S-300/S-400: the outrigger jacks go down first, then the canister pack rises to vertical
 *       on two telescoping rams; a canister's launch cover stays open once it has fired;</li>
 *   <li>Pantsir: turret, launch packs, tracking radar and the optical head follow the aim, the
 *       search radar sweeps in AUTO, a pack's front caps are gone once it has fired.</li>
 * </ul>
 * Geo angles: SBW draws the model mirrored in X, so yaw (Y) turns are negated; elevation (X) is not.
 */
public final class SamSiteRenderer extends BasicVehicleRenderer {
    /** Fraction of the deployment spent lowering the jacks before the pack starts to rise. */
    private static final float JACKS = 0.25f;

    public SamSiteRenderer(EntityRendererProvider.Context context) { super(context); }

    @Override
    public void transformCustomModelPart(VehicleEntity entity, VehicleModelInstance instance, PoseStack pose, float yaw, float partial) {
        super.transformCustomModelPart(entity, instance, pose, yaw, partial);
        if (!(entity instanceof SamSiteEntity site)) return;
        float aimYaw = (float) Math.toRadians(site.aimYaw()), pitch = site.aimPitch();
        float sweep = site.mode() == SamSiteEntity.AUTO ? (entity.tickCount + partial) * 0.12f : 0;
        rotate(instance.getBone("move_turret"), 0, -aimYaw);
        rotate(instance.getBone("move_launcher"), (float) Math.toRadians(pitch), 0);
        rotate(instance.getBone("move_radar"), 0, sweep);
        SamSystem system = site.system();
        if (system == SamSystem.S125) {
            rounds(instance, site);
            // The guidance antenna nods in elevation while it searches.
            rotate(instance.getBone("move_antenna"), (float) Math.toRadians(sweep == 0 ? 0 : 6 * Math.sin(sweep * 2.3)), 0);
        }
        else if (system == SamSystem.PANTSIR) pantsir(instance, site, pitch);
        else if (system == SamSystem.PATRIOT) patriot(instance, site, pitch);
        else pack(instance, site);
    }

    /** S-125: a fired missile leaves its rail empty until the rail is reloaded. */
    private static void rounds(VehicleModelInstance instance, SamSiteEntity site) {
        int ready = site.ready(0), capacity = site.system().racks.get(0).capacity();
        for (int i = 0; i < capacity; i++) {
            BoneState round = instance.getBone("move_round_" + i);
            // Rounds leave in rail order: the first `capacity - ready` rails are empty.
            if (round != null) round.visible = i >= capacity - ready;
        }
    }

    private static void pantsir(VehicleModelInstance instance, SamSiteEntity site, float pitch) {
        rotate(instance.getBone("move_tracking"), (float) Math.toRadians(Mth.clamp(pitch, -5, 25)), 0);
        rotate(instance.getBone("move_eo"), (float) Math.toRadians(Mth.clamp(pitch, -10, 60)), 0);
        int fired = site.system().racks.get(0).capacity() - site.ready(0);
        // Even rounds leave the left pack, odd the right.
        visible(instance.getBone("move_caps_l"), (fired + 1) / 2 == 0);
        visible(instance.getBone("move_caps_r"), fired / 2 == 0);
    }

    /** S-300/S-400: jacks, pack, rams, covers and the crew-cab whip antenna. */
    private static void pack(VehicleModelInstance instance, SamSiteEntity site) {
        float deploy = site.deployPercent() / 100f;
        float jacks = Mth.clamp(deploy / JACKS, 0, 1), raise = Mth.clamp((deploy - JACKS) / (1 - JACKS), 0, 1);
        for (int i = 0; i < 4; i++) slide(instance.getBone("move_jack_" + i), 0, -8 * jacks, 0);
        float phi = (float) Math.toRadians(90 * raise);
        rotate(instance.getBone("move_pack"), phi, 0);
        // Rams pinned to the deck at (y 31, z 10), rod eye on the pack lug: angle and stroke from the pack angle.
        double dy = -11 * Math.cos(phi) + 30 * Math.sin(phi) + 11, dz = 60 - 11 * Math.sin(phi) - 30 * Math.cos(phi);
        float alpha = (float) -Math.atan2(dy, dz), stroke = (float) (Math.hypot(dy, dz) - 30) / 3;
        for (int k = 0; k < 2; k++) {
            rotate(instance.getBone("move_ram_" + k), alpha, 0);
            for (int s = 1; s <= 3; s++) slide(instance.getBone("move_ram_" + k + "_s" + s), 0, 0, stroke);
        }
        rotate(instance.getBone("move_antenna"), (float) Math.toRadians(-90 * jacks), 0);
        for (int tube = 0; tube < 4; tube++)
            rotate(instance.getBone("move_cover_" + tube), (float) Math.toRadians(spent(site, tube) ? 100 : 0), 0);
    }

    /** Patriot: jacks down first, the launcher rises on its two rams, covers open once it is up. */
    private static void patriot(VehicleModelInstance instance, SamSiteEntity site, float pitch) {
        float deploy = site.deployPercent() / 100f, jacks = Mth.clamp(deploy / JACKS, 0, 1);
        for (int i = 0; i < 4; i++) slide(instance.getBone("move_jack_" + i), 0, -3.6f * jacks, 0);
        // Rams: barrel pinned at (y 27.8, z 40) on the turntable, rod eye at (27.8, 74) on the launcher (hinge 30, 95).
        double f = Math.toRadians(pitch), dy = 27.8 - 30, dz = 74 - 95;
        double by = 30 + dy * Math.cos(f) - dz * Math.sin(f), bz = 95 + dy * Math.sin(f) + dz * Math.cos(f);
        double vy = by - 27.8, vz = bz - 40;
        float alpha = (float) -Math.atan2(vy, vz), stroke = (float) (Math.hypot(vy, vz) - 34);
        for (int k = 0; k < 2; k++) {
            rotate(instance.getBone("move_ram_" + k), alpha, 0);
            slide(instance.getBone("move_ram_" + k + "_s1"), 0, 0, stroke);
        }
        float covers = (float) Math.toRadians(deploy >= 0.95f ? 100 : 0);
        for (int i = 0; i < 4; i++) rotate(instance.getBone("move_cover_" + i), covers, 0);
        rotate(instance.getBone("move_antenna"), (float) Math.toRadians(-90 * jacks), 0);
    }

    /** Whether a canister has launched its round (S-400 inner canisters: any of their four). */
    private static boolean spent(SamSiteEntity site, int tube) {
        SamSystem system = site.system();
        if (system == SamSystem.S300) return tube < system.racks.get(0).capacity() - site.ready(0);
        int big = system.racks.get(0).capacity() - site.ready(0), small = system.racks.get(1).capacity() - site.ready(1);
        return switch (tube) { case 0 -> big >= 1; case 3 -> big >= 2; case 1 -> small >= 1; default -> small >= 2; };
    }

    private static void rotate(BoneState bone, float x, float y) {
        if (bone != null) bone.rotation.rotationYXZ(y, x, 0);
    }

    private static void slide(BoneState bone, float dx, float dy, float dz) {
        if (bone == null) return;
        bone.x = bone.definition().bindX() + dx;
        bone.y = bone.definition().bindY() + dy;
        bone.z = bone.definition().bindZ() + dz;
    }

    private static void visible(BoneState bone, boolean shown) {
        if (bone != null) bone.visible = shown;
    }
}
