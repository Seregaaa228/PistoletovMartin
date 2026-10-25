# PIS-1 Library Integrations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development or executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Реализовать PIS-2…PIS-8 как совместимые интеграции Industry с проверяемым игровым результатом.

**Architecture:** IndustryData остаётся единственным серверным источником. Интеграции подключаются через узкие адаптеры: FE к Site.energy, Jade к фильтрованному IndustryState, SmartBrain к существующему WorkerEntity, Gecko к его состоянию и одному detail_press. LDLib использует существующие меню и сетевые действия. Каталог получает валидируемые шаблоны datapack/KubeJS и снимок оплаченной единицы.

**Tech Stack:** Java17, Minecraft1.20.1, Forge47.4.0, Gradle8.8/ForgeGradle6.0.54; GeckoLib4.8.4 из baseline. Кандидаты: LDLib1.0.52.a, SmartBrainLib1.15, Jade11.13.3, KubeJS6/Rhino для 1.20.1, Patchouli1.20.1-84.1.

**Spec:** ../specs/2026-10-25-pis-1-integrations-design.md

## Global Constraints

- Minecraft1.20.1/Forge47.4.0/Java17, native ID и прежние поля NBT сохраняются.
- Не изменять две .class modules/industry/preserved-combat и не заменять их старыми Java.
- Не менять стоимость, очередь, найм, Recruits custody или боевые правила ради интерфейса.
- Не использовать личный .minecraft, старые каталоги чатов или пользовательские миры.
- Не обновлять исходный профиль/lock до игровой проверки: новые зависимости имеют отдельный manifest и тестовый профиль.
- Только относящиеся к задаче файлы попадают в изменения; исходные пользовательские изменения не трогать.

## Review Focus

- Оплаченная единица после /reload и restart даёт прежний output/стоимость, остальные единицы используют новый шаблон.
- Чужой игрок не получает очередь/энергию через Jade universal fallback или поддельный пакет.
- Старый контроллер без BlockEntity восстанавливает capability после загрузки без сброса Site.energy.
- Отмена/смерть/недоступный путь не завершают ремонт, не потребляют комплект повторно и не создают recruit.
- Клиентский UI/рендерер не загружается dedicated server; GUI Scale2/3/Auto сохраняет доступ к длинному тексту.

## Владение и зависимости

Одновременно максимум три исполнителя и координатор. Общие build/scripts/locks/mods.toml и catalogue/orders — координатор. Исполнители меняют только свои файлы и оговорённые секции IndustryEngine. Сборки Gradle запускаются координатором последовательно.

| Исполнитель | Jira | Собственность | Зависимость |
|---|---|---|---|
| energy_jade | PIS-8, затем PIS-7 | energy/, engine/IndustryState, integration/jade/, IndustryContent controller, energy/state sections IndustryEngine | общий state первым |
| repair_animation | PIS-3, затем PIS-4 | WorkerEntity, repair/, client repair renderers, architecture/detail_press, модели/анимации | текущий repair ledger; operating hook координатора |
| ldlib_ui | PIS-2 | client FactoryScreen/OperationsScreen/LDLib host/widgets, UI docs | старые snapshots + новые scalar state |
| координатор | PIS-5, PIS-6, сборка/приёмка | catalog integration, Order snapshots, guide, dependencies/tools | финальный UI/state для справки |

### Task 1: Восстановить точные входы и закрепить дополнительные библиотеки

**Files:** integration-libraries.lock.json; tools/prepare_dependencies.py; tools/create_test_profile.py; tools/verify_project.py; tools/package_project.py; modules/industry/build.gradle; META-INF/mods.toml.

**Interfaces:** prepare() публикует baseline и проверенные additional artifacts в vendor/maven; accepted 27-JAR modpack.lock.json неизменен. create_test_profile --integrations копирует locked дополнительные библиотеки и kubejs, пишет итоговый test-profile.json с SHA256/runId.

- [x] Скачать официальный release, проверить SHA256, восстановить только отсутствующие исключённые из Git бинарные входы.
- [x] Получить официальные metadata/JAR точных Forge1.20.1 версий, проверить upstream hash, сохранить SHA256/лицензию/URL в отдельном lock.
- [x] Добавить воспроизводимую подготовку/проверку интеграционного профиля без загрузок во время обычной подготовки.
- [x] Проверить отказ при неверном SHA256, duplicate mod ID, существующем destination; baseline verify и сборку.

### Task 2: Forge Energy и единое публичное состояние

**Files:** energy/IndustryEnergy.java; energy/ControllerBlockEntity.java; engine/IndustryState.java; IndustryContent.java; IndustryEngine.java; research/Research.java.

**Interfaces:** IndustryEngine.state(ServerPlayer, UUID): Optional<IndustryState>; stateAt(ServerPlayer, BlockPos): Optional<IndustryState>. State — immutable record с id/type/status/health/loaded/functional/powered/energy/energyCapacity/networkConnected/networkStatus/workers/repairWorkers/repairProgress/repairTotal/repairRequested/currentOrder/quantity/progress/total. Existing snapshot keys сохраняются.

- [x] Написать содержательные проверки conservation/simulate/negative/boundary transfers и разрыва chain.
- [x] Реализовать live IEnergyStorage к Site.energy, ролям/сторонам, loaded/functional сети; без второго storage.
- [x] Добавить BlockEntity bridge к существующим controller IDs, lifecycle invalidate/revive и восстановление старых контроллеров.
- [x] Прогнать проверки; описать 1 unit=1FE и прежние коэффициенты. Сценарий: разрушить узел, сохранить очередь, починить узел и отдельно завод.

### Task 3: SmartBrain ремонтных рабочих

**Files:** WorkerEntity.java; repair/RepairWork.java; repair sensors/behaviors; scoped repairCrew/workerTick hooks.

**Interfaces:** RepairWork.snapshot(MinecraftServer, Site): JsonObject; Site ledger остаётся владельцем progress/kit/request. Brain выбирает только разрешённую назначенную цель; server engine один раз/тик завершает ремонт.

- [x] Реализовать SmartBrainOwner для существующего worker и repair roles без изменения других ролей/найма/custody.
- [x] Реализовать sensor и явные idle/moving/repairing/no_path/unloaded/target_missing/cancelled состояния, bounded scans.
- [x] Сохранять назначение и авторитетный progress; revalidate owner/dimension/alive/role/target перед repair.
- [ ] Проверить restart/chunk unload/death/unreachable/два работника, время1800ticks и ровно одно завершение.

### Task 4: GeckoLib ремонтник и detail_press

**Files:** WorkerEntity; client/repair renderers; architecture/detail_press BlockEntity; assets/frontier_industry/geo/, animations/, textures/; editable .bbmodel.

**Interfaces:** transient Site.operating true только после фактического progress increment; клиент получает согласованный server flag, без изменения ledger.

- [x] Добавить idle/walk/repair контроллеры worker и operate/stop detail_press с GeckoLib4.8.4.
- [x] Сохранить старые block/entity IDs, ресурсы техники и модельные системы SBW.
- [x] Хранить редактируемые исходники, проверить пути JSON/текстур/анимаций.
- [ ] Проверить dedicated classloading, остановку при потере энергии, второго клиента и нагрузку.

### Task 5: LDLib UI

**Files:** client/FactoryScreen.java; client/OperationsScreen.java; client LDLib host/widgets; docs UI authoring.

**Interfaces:** Сохраняются FactoryMenu, IndustryNet protocol3 IDs0/1 и OperationsNet protocol2 IDs0/1, существующие action handlers. client.IndustryGuide.open() открывает справку.

- [x] Собрать reusable LDLib WidgetGroup/buttons/textfields/progress в существующем container screen.
- [x] Добавить КПК Страна/Армия/Производство/Ремонт и Справочник, понятные причины/подписи, не назначать B/R.
- [ ] Проверить server rejection, reopen/reconnect, GUI Scale2/3/Auto, длинные строки/списки.
- [x] Сохранить инструкцию редактирования и снимки основных состояний.

### Task 6: KubeJS и безопасный производственный каталог

**Files:** integration/catalog/IndustryRecipeReloadListener.java; IndustryCatalog.java; engine/IndustryData.Order; production/complete sections IndustryEngine; instance/kubejs/server_scripts/; docs catalog.

**Interfaces:** datapack path frontier_industry/production; validated namespaced template ID, factory, output/cost/ticks/fePerTick. KubeJS ServerEvents.highPriorityData генерирует JSON; listener атомарно публикует validated custom map. Built-in full catalog остаётся доступным для legacy paid queue.

- [x] Создать валидируемый reload adapter без произвольного reflection/API scripts.
- [x] Добавить снимок paid output/ticks/FE/title; существующие Recipe/Quantity/Progress/Reserved/Paid поля сохраняются. Snapshot снимается при успешной оплате; старые reserved orders восстанавливаются из built-in catalog.
- [ ] Тестировать malformed/empty/free/wrong factory/duplicate ID/unknown item; reject invalid entries с диагностикой.
- [x] Добавить рабочие recipes/tags/template примеры; документировать /reload и restart, изменение шаблона при paid заказе.

### Task 7: Jade

**Files:** integration/jade/IndustryJadePlugin.java; Jade providers; docs data policy.

**Interfaces:** stateAt фильтрует owner/dimension/reach/loaded, payload только bounded scalar fields/текущий заказ. Universal energy provider переопределяется для controller, для чужого игрока возвращается empty list.

- [x] Реализовать русскую подсказку сети/завода/ремонта с единым state.
- [x] Закрыть fallback и ограничить частоту/объём обновлений.
- [ ] Проверить own/foreign/two clients, restart/chunk load и согласованность с КПК.

### Task 8: Patchouli

**Files:** data/frontier_industry/patchouli_books/field_guide/book.json; assets/.../patchouli_books/field_guide/{en_us,ru_ru}; client/IndustryGuide.java; docs/player.

**Interfaces:** IndustryGuide.open(): void, book ID frontier_industry:field_guide, fallback en_us содержит основной русский текст.

- [x] Создать категории/пошаговые страницы всех перечисленных тем, item images/схемы из реальных ресурсов.
- [x] Подключить кнопку КПК к Patchouli API только на клиенте; keybind выводить через соответствующие key mapping.
- [ ] Сверить книгу с окончательными UI/состояниями; проверить ссылки/предметы/ресурсы и GUI scales.

### Task 9: Общая проверка и передача

**Files:** docs/testing/PIS-1-2026-10-25.md; durable progress ledger рядом с планом.

- [x] Независимо прочитать изменения каждого исполнителя, исправить ошибки/конфликты.
- [x] Собрать затронутые модули и весь bundle; выполнить Python/Java contract tests, baseline и integration profile verify.
- [x] Создать свежие baseline/integration профили с runId и SHA256, выполнить доступные игровые сценарии без пользовательских миров.
- [x] Сравнить нагрузку на одинаковом сценарии; приложить screenshots/параметры или точно записать недоступные проверки.
- [x] Передать проверяемую ветку/PR с Jira ссылками; задачи не закрывать, пока обязательная игровая приёмка не выполнена.
