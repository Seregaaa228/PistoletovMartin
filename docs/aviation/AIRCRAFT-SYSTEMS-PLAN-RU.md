# Aircraft Systems Implementation Plan

> **For agentic workers:** use subagent-driven-development within the user's already authorized implementation. Keep each write scope separate and review integrations before runtime tests.

**Goal:** согласовать ракеты с прицелом, откалибровать полёт, полностью удалять
уничтоженные самолёты и дать новым моделям/NPC общий расширяемый контур.

**Architecture:** единый каталог связывает идентичность, модель, handling и loadout.
Серверные intents игрока/NPC проходят FlightControlService и native weapon queue.
Native engine/projectile/explosion сохраняют ownership физики и боевых ресурсов.

**Tech Stack:** Minecraft 1.20.1, Forge 47.4.0, Java 17, pinned SBW/DSM, существующий Gradle.

**Spec:** `docs/aviation/AIRCRAFT-SYSTEMS-DESIGN-RU.md`.

## Global Constraints

- Не менять зависимости/lock-файлы/native ID/форматы сохранений.
- Не трогать Industry preserved-combat, ledger и пользовательские миры.
- Общие и серверные типы не импортируют Minecraft client classes.
- Только свежие тестовые профили; основной запущенный профиль не автоматизировать.
- Сначала выяснить native-порядок операций; фиксированная поправка pitch не является решением.

## Review Focus

- Скорость носителя и смещение двух подвесок не должны разносить ракеты выше прицела.
- Callback смерти и загрузка wreck не должны давать повторный взрыв/ghost entity.
- Малые скорости, крен и снижение не должны создавать энергию или отменять stall.
- Поздний выстрел/команда старой epoch не должны переживать смену пилота/места.
- Новая definition должна менять реальные runtime handling/loadout, а не только metadata.

### Task 1: каталог и серверный API — основной агент

- [x] Добавить immutable `aircraft/AircraftDefinition`, `AircraftModel`, `AircraftCatalog`.
- [x] Связать `FlightProfiles.find(String)` с единственным каталогом.
- [x] Регистрировать собственные модели через `AircraftProfile` по определениям.
- [x] Добавить `weapons/WeaponMount`, `WeaponLoadout`, `WeaponKind` и серверный dispatch/status.
- [x] Добавить `api/AircraftController`: acquire/submit/observe/owns/release на реальном сервисе.
- [x] Покрыть отдельные handling/loadout, коллизии ID и соответствие native JSON.
- [x] Описать добавление модели и цикл NPC с sequence/lease/нейтрализацией.

### Task 2: прицел и запуск — Opus 5.5

- [x] Проследить F16 HUD → packet → native deferred shot → AGM guidance по pinned bytecode.
- [x] Создать общий расчёт прицела с геометрической верификацией и muzzle convergence.
- [x] Подключить launcher/queued validation/client trigger по capabilities loadout.
- [x] Принимать LivingEntity для NPC, сохранять seat/lease/ammo/heat/reload и клик/удержание.
- [x] Проверить обе подвески, скорость носителя, геометрию и неверные запросы.

### Task 3: handling — Sonnet 5.5

- [x] Проследить native linear integrator и причину бокового drift.
- [x] Добавить pure `handling/HandlingProfile` и модель поправки с ограниченными коэффициентами.
- [x] Подключить в `NativeFlightAdapter`/native physics один раз на физический шаг.
- [ ] Проверить energy/forward/ground/stall/roll и client/server порядок без двойного replay сил.

### Task 4: уничтожение — Fable 5.1

- [x] Найти точный native destroy/wreck/explosion transition.
- [x] Добавить scoped терминальное удаление через definition.destroyOnDeath.
- [ ] Проверить ровно один взрыв, passengers, старый wreck и отсутствие сохранения/коллизии.
- [x] Передать имя mixin основному агенту для единой регистрации.

### Task 5: упреждение — отдельный Opus 5.5

- [x] Добавить `weapons/InterceptSolver` с finite/lifetime validation и stable positive root.
- [x] Проверить аналитические и seeded встречи projectile/target с inherited velocity.
- [x] Явно ограничить применимость постоянной скоростью, без AGM acceleration/gravity.

### Task 6: интеграция и приёмка — основной агент

- [x] Проверить diff всех агентов, зарегистрировать проверки/mixins без конфликтов файлов.
- [x] Выполнить commands README, Industry и full build; проверить CRC/SHA256 и side loading.
- [x] Собрать отдельный opt-in runtime fixture и запустить реальные свежие игровые профили.
- [ ] Проверить прицел, handling, destruction и реального NPC, reload/restart и два клиента.
- [x] Выполнить независимое Claude review; исправить найденное и повторить затронутые проверки.
- [ ] Сохранить точный отчёт с SHA256 и открытыми сценариями; commit только своих изменений.
