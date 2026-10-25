package dev.frontier.arcade.combat;

import com.atsuishio.superbwarfare.tools.CustomExplosion;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.geranium.GeraniumEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import ru.lavafrai.uncomplicatedfpv.entity.FpvDroneEntity;

/** Exclude the one direct-hit target from splash; native damage and PvP policy still apply. */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID)
public final class DroneBlastLimits {
    private record DirectImpact(Entity source, Entity target) { }
    private static final ThreadLocal<DirectImpact> DIRECT_TARGET = new ThreadLocal<>();
    private DroneBlastLimits() { }
    static void explode(FpvDroneEntity source, Vec3 point, Entity warhead, Entity attacker, float damage, float radius, Entity direct) {
        DirectImpact previous = DIRECT_TARGET.get();
        DIRECT_TARGET.set(new DirectImpact(source, direct));
        try { new CustomExplosion.Builder(source).position(point).source(warhead).attacker(attacker).damage(damage).radius(radius).destroyBlock(true).explode(); }
        finally { if (previous == null) DIRECT_TARGET.remove(); else DIRECT_TARGET.set(previous); }
    }
    // SBW's NORMAL-priority adapter removes vehicles and hurts them inside this
    // event, before CustomExplosion's own damage loop. Exclude our direct hit first.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void limit(ExplosionEvent.Detonate event) {
        if (event.getLevel().isClientSide || !(event.getExplosion() instanceof CustomExplosion blast)) return;
        Entity source = blast.getDirectSourceEntity();
        boolean fpv = source instanceof FpvDroneEntity;
        if (!fpv && !(source instanceof GeraniumEntity)) return;
        DirectImpact direct = DIRECT_TARGET.get();
        if (fpv && direct != null && direct.source() == source && direct.target() != null)
            event.getAffectedEntities().remove(direct.target());
    }
}
