package dev.frontier.arcade.artillery;

import com.atsuishio.superbwarfare.entity.vehicle.base.SpArtilleryEntity;
import dev.frontier.arcade.ArcadeVehicles;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.util.Mth;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * With nobody at the gunner's seat our guns and the launcher swing back to their travel position (turret ahead, barrel
 * level) at the turret's own turning speed, as a crew does before driving off. Run alike on server and client so the
 * turret neither jumps nor is pulled back by a sync.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID)
public final class ArtilleryStow {
    private static final Set<SpArtilleryEntity> SERVER = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<SpArtilleryEntity> CLIENT = Collections.newSetFromMap(new WeakHashMap<>());

    private ArtilleryStow() { }

    @SubscribeEvent
    public static void join(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof SpArtilleryEntity gun && ArtilleryVehicles.ALL.stream().anyMatch(type -> type.get() == gun.getType()))
            (event.getLevel().isClientSide() ? CLIENT : SERVER).add(gun);
    }

    @SubscribeEvent
    public static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (SpArtilleryEntity gun : event.level.isClientSide() ? CLIENT : SERVER) {
            if (gun.isRemoved() || gun.level() != event.level || gun.getNthEntity(gun.getTurretControllerIndex()) != null) continue;
            float yaw = gun.getTurretYRot(), pitch = gun.getTurretXRot();
            if (Math.abs(Mth.wrapDegrees(yaw)) < 0.01f && Math.abs(pitch) < 0.01f) continue;
            gun.setTurretYRotO(yaw);
            gun.setTurretXRotO(pitch);
            gun.setTurretYRot(approach(Mth.wrapDegrees(yaw), gun.getTurretTurnYSpeed()));
            gun.setTurretXRot(approach(pitch, gun.getTurretTurnXSpeed()));
        }
    }

    private static float approach(float angle, float speed) {
        float step = Math.max(0.5f, speed);
        return Math.abs(angle) <= step ? 0 : angle - Math.signum(angle) * step;
    }
}
