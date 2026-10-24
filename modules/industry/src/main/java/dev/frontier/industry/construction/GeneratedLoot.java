package dev.frontier.industry.construction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.frontier.industry.IndustryMod;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.*;
import net.minecraftforge.registries.*;

/** Generated vanilla architecture must not become an infinite repair-to-ore source. */
public final class GeneratedLoot extends LootModifier {
    public static final DeferredRegister<Codec<? extends IGlobalLootModifier>> CODECS=DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS,IndustryMod.ID);
    public static final RegistryObject<Codec<GeneratedLoot>> TYPE=CODECS.register("generated_building",()->RecordCodecBuilder.create(instance->codecStart(instance).apply(instance,GeneratedLoot::new)));
    public GeneratedLoot(LootItemCondition[] conditions){super(conditions);}
    @Override public Codec<? extends IGlobalLootModifier> codec(){return TYPE.get();}
    @Override protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot,LootContext context){
        var origin=context.getParamOrNull(LootContextParams.ORIGIN);var state=context.getParamOrNull(LootContextParams.BLOCK_STATE);
        if(origin!=null&&state!=null&&Construction.generated(context.getLevel(),BlockPos.containing(origin),state))loot.clear();
        return loot;
    }
}
