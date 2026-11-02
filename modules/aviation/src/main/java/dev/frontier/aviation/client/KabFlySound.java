package dev.frontier.aviation.client;

import dev.frontier.aviation.LightAviation;
import dev.frontier.aviation.weapons.KabEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * Sound of a falling КАБ, both our own generated assets (sounds.json): a looping rush of
 * air for the whole flight, louder and higher as the bomb speeds up, and the falling-pitch
 * whistle (3.2 s) KabEntity starts so that it ends at impact. SBW's projectile fly sound
 * is muted for the КАБ.
 */
public final class KabFlySound extends AbstractTickableSoundInstance {
    private static final SoundEvent AIRFLOW = SoundEvent.createVariableRangeEvent(new ResourceLocation(LightAviation.ID, "kab_airflow"));
    private static final SoundEvent WHISTLE = SoundEvent.createVariableRangeEvent(new ResourceLocation(LightAviation.ID, "kab_whistle"));

    private final KabEntity bomb;
    private final boolean airflow;

    private KabFlySound(KabEntity bomb, boolean airflow) {
        super(airflow ? AIRFLOW : WHISTLE, SoundSource.NEUTRAL, bomb.level().getRandom());
        this.bomb = bomb;
        this.airflow = airflow;
        looping = airflow;
        delay = 0;
        volume = airflow ? 0.2F : 2.0F;
        pitch = 1.0F;
        follow();
    }

    public static void startAirflow(KabEntity bomb) {
        Minecraft.getInstance().getSoundManager().play(new KabFlySound(bomb, true));
    }

    public static void startWhistle(KabEntity bomb) {
        Minecraft.getInstance().getSoundManager().play(new KabFlySound(bomb, false));
    }

    @Override
    public void tick() {
        if (bomb.isRemoved()) {
            stop();
            return;
        }
        follow();
        if (airflow) {
            float speed = (float) bomb.getDeltaMovement().length();
            volume = Mth.clamp(speed / 2.5F, 0.2F, 1.4F);
            pitch = Mth.clamp(0.75F + speed / 6.0F, 0.75F, 1.4F);
        }
    }

    private void follow() {
        x = bomb.getX();
        y = bomb.getY();
        z = bomb.getZ();
    }
}
