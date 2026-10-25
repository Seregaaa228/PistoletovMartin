package dev.frontier.nations.flag;

import net.minecraft.nbt.CompoundTag;

public final class FlagStorageMigrationTest {
    public static void main(String[] args){
        NationFlags canonical=new NationFlags(),legacy=new NationFlags();
        FlagDefinition red=FlagDefinition.solid(14),blue=FlagDefinition.solid(11);
        canonical.set("same",red);legacy.set("same",blue);legacy.set("nether-only",blue);
        canonical.mergeLegacy(NationFlags.load(legacy.save(new CompoundTag())));
        if(!canonical.flag("same").equals(red))throw new AssertionError("migration overwrote canonical flag");
        if(!canonical.flag("nether-only").equals(blue))throw new AssertionError("legacy dimension flag was lost");
        if(!legacy.flag("same").equals(blue))throw new AssertionError("legacy source mutated");
        var loaded=NationFlags.load(canonical.save(new CompoundTag()));
        if(!loaded.snapshot().equals(canonical.snapshot()))throw new AssertionError("migration does not survive NBT reload");
        System.out.println("FlagStorageMigrationTest: 4 checks passed");
    }
}
