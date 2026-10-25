// KubeJS 6 / Forge 1.20.1. These events run at world load and /reload.
ServerEvents.tags('item', event => {
  event.add('frontier_industry:conductors', ['minecraft:copper_ingot', 'minecraft:iron_ingot'])
})

ServerEvents.recipes(event => {
  // A small, reversible example of adding a recipe.
  event.shapeless('minecraft:copper_block', ['9x minecraft:copper_ingot'])
    .id('frontier_industry:packed_copper')
  // Move the existing repair-kit assembly recipe into the versioned scripts.
  event.remove({ id: 'frontier_industry:repair_kit' })
  event.shapeless('frontier_industry:repair_kit', [
    '2x minecraft:iron_ingot', '#frontier_industry:conductors', 'minecraft:redstone'
  ]).id('frontier_industry:repair_kit')
})

ServerEvents.highPriorityData(event => {
  // Industry's bounded datapack adapter reads this directory on the server.
  // No free-form output NBT, code execution, or client-supplied prices.
  event.addJson('frontier_industry:frontier_industry/production/field_repair_kit.json', {
    title: 'Ремонтный комплект',
    factory: 'EQUIPMENT_FACTORY',
    output: { item: 'frontier_industry:repair_kit', count: 1 },
    cost: [
      { item: 'minecraft:iron_ingot', count: 2 },
      { item: 'minecraft:copper_ingot', count: 1 },
      { item: 'minecraft:redstone', count: 1 }
    ],
    ticks: 600,
    fePerTick: 16
  })
})
