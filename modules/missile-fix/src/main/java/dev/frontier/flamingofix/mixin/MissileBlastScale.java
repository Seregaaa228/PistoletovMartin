package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.MissileImpactConfig;
import dev.sbw.deepstrike.game.MissileKind;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Server-configured crater size (frontier-missiles-server.toml [blastScale]); entity damage stays native. */
@Mixin(targets="dev.sbw.deepstrike.game.MissileExplosion",remap=false)
public abstract class MissileBlastScale {
    @Redirect(method="*",at=@At(value="FIELD",target="Ldev/sbw/deepstrike/game/MissileKind;craterRadius:I",
            opcode=Opcodes.GETFIELD,remap=false),remap=false,require=1)
    private int frontierFlamingo$craterRadius(MissileKind kind) {
        return (int)Math.round(kind.craterRadius*MissileImpactConfig.blastScale(kind.id));
    }

    @Redirect(method="*",at=@At(value="FIELD",target="Ldev/sbw/deepstrike/game/MissileKind;craterDepth:I",
            opcode=Opcodes.GETFIELD,remap=false),remap=false,require=1)
    private int frontierFlamingo$craterDepth(MissileKind kind) {
        return (int)Math.round(kind.craterDepth*MissileImpactConfig.blastScale(kind.id));
    }

    @Redirect(method="*",at=@At(value="FIELD",target="Ldev/sbw/deepstrike/game/MissileKind;partialDestructionRadius:I",
            opcode=Opcodes.GETFIELD,remap=false),remap=false,require=1)
    private int frontierFlamingo$partialRadius(MissileKind kind) {
        return (int)Math.round(kind.partialDestructionRadius*MissileImpactConfig.blastScale(kind.id));
    }
}
