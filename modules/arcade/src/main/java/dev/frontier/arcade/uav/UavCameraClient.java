package dev.frontier.arcade.uav;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Method;

/** Renders the remote camera without changing the player's entity, position or game mode. */
@Mod.EventBusSubscriber(modid = NativeUavCamera.MOD_ID, value = Dist.CLIENT)
public final class UavCameraClient {
    private static final Method SET_CAMERA_POSITION = findPositionSetter();
    private static boolean cameraAvailable = SET_CAMERA_POSITION != null;

    private UavCameraClient() { }

    private static Method findPositionSetter() {
        try {
            return ObfuscationReflectionHelper.findMethod(Camera.class, "m_90584_", double.class, double.class, double.class);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** Test hook: resolves only a currently tracked, loaded native entity authorized by item NBT. */
    public static Entity currentTarget() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!cameraAvailable || minecraft.player == null || minecraft.level == null || !minecraft.player.isAlive()) return null;
        ItemStack stack = minecraft.player.getMainHandItem();
        if (!NativeUavCamera.isUsing(stack)) return null;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.hasUUID("FrontierUavLinkedUav") || !tag.hasUUID("FrontierUavCameraOwner")
            || !minecraft.player.getUUID().equals(tag.getUUID("FrontierUavCameraOwner"))
            || !minecraft.level.dimension().location().toString().equals(tag.getString("FrontierUavDimension"))) return null;
        double range = Math.min(NativeUavCamera.MAX_RANGE, tag.getDouble("FrontierUavRange"));
        if (range < 1.0) return null;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity.getUUID().equals(tag.getUUID("FrontierUavLinkedUav")) && NativeUavCamera.isNativeShahed(entity)
                && entity.isAlive() && !entity.isRemoved() && minecraft.level.hasChunkAt(entity.blockPosition())
                && minecraft.player.distanceToSqr(entity) <= range * range) return entity;
        }
        return null;
    }

    @SubscribeEvent
    public static void camera(ViewportEvent.ComputeCameraAngles event) {
        Minecraft minecraft = Minecraft.getInstance();
        Entity target = currentTarget();
        if (target == null || minecraft.player == null) return;
        float partial = (float) event.getPartialTick();
        Vec3 nose = Vec3.directionFromRotation(0.0f, target.getYRot()).scale(1.6);
        double x = Mth.lerp(partial, target.xo, target.getX()) + nose.x;
        double y = Mth.lerp(partial, target.yo, target.getY()) + 0.15;
        double z = Mth.lerp(partial, target.zo, target.getZ()) + nose.z;
        try {
            SET_CAMERA_POSITION.invoke(event.getCamera(), x, y, z);
            event.setYaw(minecraft.player.getYRot());
            event.setPitch(Mth.clamp(minecraft.player.getXRot(), -85.0f, 85.0f));
            event.setRoll(0.0f);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            // Fail back to the ordinary body camera; never retain a broken detached view.
            cameraAvailable = false;
        }
    }

    @SubscribeEvent
    public static void movement(MovementInputUpdateEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getEntity() != minecraft.player || minecraft.player==null) return;
        ItemStack held=minecraft.player.getMainHandItem();
        var item=net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(held.getItem());
        boolean nativeMonitor=item!=null && item.toString().equals("superbwarfare:monitor") && held.hasTag() && held.getTag().getBoolean("Using") && held.getTag().getBoolean("Linked");
        if(currentTarget()==null && !nativeMonitor)return;
        var input = event.getInput();
        input.leftImpulse = 0.0f;
        input.forwardImpulse = 0.0f;
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
    }
}
