package dev.frontier.industry.repair;

import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.IndustryMod;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class RepairContent {
    private static final DeferredRegister<MemoryModuleType<?>> MEMORIES = DeferredRegister.create(Registries.MEMORY_MODULE_TYPE, IndustryMod.ID);
    private static final DeferredRegister<SensorType<?>> SENSORS = DeferredRegister.create(Registries.SENSOR_TYPE, IndustryMod.ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, IndustryMod.ID);
    public static final RegistryObject<MemoryModuleType<UUID>> REPAIR_TARGET = MEMORIES.register("repair_target", () -> new MemoryModuleType<>(Optional.of(UUIDUtil.CODEC)));
    public static final RegistryObject<SensorType<RepairTargetSensor>> REPAIR_SENSOR = SENSORS.register("repair_target", () -> new SensorType<>(RepairTargetSensor::new));
    public static final RegistryObject<BlockEntityType<PressWorkstationEntity>> PRESS = BLOCK_ENTITIES.register("press_workstation", () ->
        BlockEntityType.Builder.of(PressWorkstationEntity::new, IndustryContent.detail("press")).build(null));
    private RepairContent() {}
    public static void register(IEventBus bus) {
        MEMORIES.register(bus); SENSORS.register(bus); BLOCK_ENTITIES.register(bus);
    }
}
