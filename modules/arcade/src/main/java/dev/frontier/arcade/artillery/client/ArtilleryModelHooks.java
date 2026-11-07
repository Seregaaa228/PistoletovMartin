package dev.frontier.arcade.artillery.client;

import com.atsuishio.superbwarfare.client.model.entity.VehicleModelInstance;
import com.atsuishio.superbwarfare.entity.vehicle.base.ArtilleryEntity;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
import dev.frontier.arcade.artillery.ArtilleryVehicles;
import net.minecraft.client.Minecraft;

/**
 * Bones the PLZ-05 renderer does not know: the trucks' stabiliser jacks slide straight down out of their housings,
 * and the cab parts in front of the crew's eyes (windscreen frame, dashboard) are hidden from the cab crew's own
 * first-person view only, so they look out over the bonnet. Only translation is used: SBW's baker flips bone X/Y
 * rotations, which turned rotated parts crooked in game. The bones carry SBW's dynamic "move_" prefix, without which
 * the baker folds them into the hull.
 */
public final class ArtilleryModelHooks {
    /** Model units the Grad's jack legs extend to reach the ground (from grad_assets.py). */
    private static final float GRAD_JACK_TRAVEL = 8;
    /** Model units the Bohdana's jack legs extend to reach the ground (from bohdana_assets.py). */
    private static final float BOHDANA_JACK_TRAVEL = 9;
    /** Seat index of the gunner, who rides inside the mount; every other seat is in the cab. */
    private static final int GUNNER_SEAT = 1;

    private ArtilleryModelHooks() { }

    public static void apply(ArtilleryEntity entity, VehicleModelInstance instance, float partial) {
        var type = entity.getType();
        if (type == ArtilleryVehicles.GRAD.get()) {
            float legs = ArtilleryLegsClient.progress(entity, partial);
            slide(instance.getBone("move_grad_jack_l"), -GRAD_JACK_TRAVEL * legs);
            slide(instance.getBone("move_grad_jack_r"), -GRAD_JACK_TRAVEL * legs);
            hideFromCab(entity, instance.getBone("move_grad_fp_hide"));
        } else if (type == ArtilleryVehicles.BOHDANA.get()) {
            float legs = ArtilleryLegsClient.progress(entity, partial);
            slide(instance.getBone("move_bh_jack_l"), -BOHDANA_JACK_TRAVEL * legs);
            slide(instance.getBone("move_bh_jack_r"), -BOHDANA_JACK_TRAVEL * legs);
            hideFromCab(entity, instance.getBone("move_bh_fp_hide"));
        }
    }

    private static void slide(BoneState bone, float dy) {
        if (bone != null) bone.y = bone.definition().bindY() + dy;
    }

    private static void hideFromCab(ArtilleryEntity entity, BoneState bone) {
        if (bone == null) return;
        var mc = Minecraft.getInstance();
        bone.visible = !(mc.player != null && mc.player.getVehicle() == entity && entity.getSeatIndex(mc.player) != GUNNER_SEAT
            && mc.options.getCameraType().isFirstPerson());
    }
}
