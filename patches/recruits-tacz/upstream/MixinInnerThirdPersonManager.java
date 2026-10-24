package com.logic.recruitstacz.mixin.tacz;

import com.logic.recruitstacz.compat.playeranimator.TACZRecruitAnimatorCompat;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.animation.third.InnerThirdPersonManager;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.compat.playeranimator.PlayerAnimatorCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(InnerThirdPersonManager.class)
public class MixinInnerThirdPersonManager {

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void setRotationAnglesHead(LivingEntity entityIn, ModelPart rightArm, ModelPart leftArm, ModelPart body, ModelPart head, float limbSwingAmount) {
        // 游戏暂停时不进行动画计算，否则会 StackOverflow
        if (Minecraft.getInstance().isPaused()) {
            return;
        }
        if (entityIn instanceof IGunOperator operator) {
            ItemStack mainHandItem = entityIn.getMainHandItem();
            IGun iGun = IGun.getIGunOrNull(mainHandItem);
            if (iGun == null) {
                TACZRecruitAnimatorCompat.stopAllAnimation(entityIn);
                PlayerAnimatorCompat.stopAllAnimation(entityIn);
                return;
            }
            // 睡觉、爬梯、游泳、鞘翅飞行不播放第三人称动画
            if (entityIn.getPose() == Pose.SLEEPING || entityIn.onClimbable() || entityIn.isSwimming() || entityIn.getPose() == Pose.FALL_FLYING) {
                TACZRecruitAnimatorCompat.stopAllAnimation(entityIn);

                PlayerAnimatorCompat.stopAllAnimation(entityIn);
                return;
            }

            TimelessAPI.getGunDisplay(mainHandItem).ifPresent(display -> {
                if(TACZRecruitAnimatorCompat.hasPlayerAnimator3rd(entityIn, display)) {
                    TACZRecruitAnimatorCompat.playAnimation(entityIn, display, limbSwingAmount);
                }

                if (PlayerAnimatorCompat.hasPlayerAnimator3rd(entityIn, display)) {
                    PlayerAnimatorCompat.playAnimation(entityIn, display, limbSwingAmount);
                } else {
                    playVanillaAnimation(entityIn, rightArm, leftArm, body, head, operator, display);
                }
            });
        }
    }

    @Shadow(remap = false)
    private static void playVanillaAnimation(LivingEntity entityIn, ModelPart rightArm, ModelPart leftArm, ModelPart body, ModelPart head, IGunOperator operator, GunDisplayInstance display) {

    }

}

