package dev.frontier.arcade.artillery.client;

import com.atsuishio.superbwarfare.client.model.entity.VehicleModelInstance;
import com.atsuishio.superbwarfare.entity.vehicle.base.ArtilleryEntity;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
import dev.frontier.arcade.artillery.ArtilleryVehicles;
import net.minecraft.client.Minecraft;

/**
 * Bones the PLZ-05 renderer does not know: the Grad's jacks slide down out of their housings, the Bohdana's spades
 * swing down into the ground, the cab steering wheels turn with the front wheels, and cab parts that would sit in
 * the driver's face are hidden from the driver's own first-person view only.
 */
public final class ArtilleryModelHooks {
    /** Model units the Grad's jack legs extend to reach the ground (from grad_assets.py). */
    private static final float GRAD_JACK_TRAVEL = 8;
    /** Degrees about X the Bohdana's spades swing to dig in (from bohdana_assets.py). */
    private static final float BOHDANA_SPADE_ANGLE = 119.46f;
    /**
     * Steering-wheel turns per front-wheel angle, as SBW's own pickup: SBW's rudder is negative for a right turn, and
     * a negative spin about the bone's local Z (the column) turns the wheel clockwise.
     */
    private static final float STEERING_RATIO = 8;

    private ArtilleryModelHooks() { }

    public static void apply(ArtilleryEntity entity, VehicleModelInstance instance, float partial) {
        var type = entity.getType();
        if (type == ArtilleryVehicles.GRAD.get()) {
            float legs = ArtilleryLegsClient.progress(entity, partial);
            slide(instance.getBone("move_grad_jack_l"), -GRAD_JACK_TRAVEL * legs);
            slide(instance.getBone("move_grad_jack_r"), -GRAD_JACK_TRAVEL * legs);
            hideFromDriver(entity, instance.getBone("move_grad_fp_hide"));
            steer(entity, instance.getBone("move_grad_steering_wheel"));
        } else if (type == ArtilleryVehicles.BOHDANA.get()) {
            float legs = ArtilleryLegsClient.progress(entity, partial);
            swing(instance.getBone("move_bh_spade_l"), BOHDANA_SPADE_ANGLE * legs);
            swing(instance.getBone("move_bh_spade_r"), BOHDANA_SPADE_ANGLE * legs);
            hideFromDriver(entity, instance.getBone("move_bh_fp_hide"));
            steer(entity, instance.getBone("move_bh_steering_wheel"));
        }
    }

    private static void slide(BoneState bone, float dy) {
        if (bone != null) bone.y = bone.definition().bindY() + dy;
    }

    private static void swing(BoneState bone, float degrees) {
        if (bone != null) bone.rotation.rotateX((float) Math.toRadians(degrees));
    }

    /**
     * The cab steering wheel follows SBW's steering (rudder, radians, the angle of the front wheels). SBW never updates
     * the rudder's previous value, so interpolating would jitter; the current value is used as SBW's wheels see it.
     */
    private static void steer(ArtilleryEntity entity, BoneState bone) {
        if (bone != null) bone.rotation.rotateZ(entity.getRudderRot() * STEERING_RATIO);
    }

    private static void hideFromDriver(ArtilleryEntity entity, BoneState bone) {
        if (bone == null) return;
        var mc = Minecraft.getInstance();
        bone.visible = !(mc.player != null && mc.player.getVehicle() == entity && entity.getSeatIndex(mc.player) == 0
            && mc.options.getCameraType().isFirstPerson());
    }
}
