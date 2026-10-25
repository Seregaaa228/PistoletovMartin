# PIS-13 / PIS-15 / PIS-16: ракеты, 2026-10-25

## Зафиксированные зависимости и границы

Minecraft 1.20.1 / Forge 47.4.0 / Java 17. Принятые JAR в `instance/mods`
не заменялись. Анализ сделан `javap -c -p` и локальным ForgeFlower 2.0.629.0
из закреплённого Gradle-кэша. Декомпилированные сторонние классы находятся
только в игнорируемой папке `modules/missile-fix/build/inspect`.

| Исходный JAR | SHA-256 |
|---|---|
| `dsm-0.9.0.jar` | `e4a9ce6f27b53228e10e20ac2f18400f82d3d2ffcd36486fcaddf588a411ccc9` |
| `superbwarfare-0.8.9.1-hotfix-mc1.20.1-993063bed-all.jar` | `3aaf4c239bc0fb31f9217927a44d74071d904d1dd03c5308ca3395ecd67d86dc` |

ID, native NBT, форматы очередей, сохранённые боевые классы Industry и версии
не изменены. Native splash сохранён. После воспроизведения прямого попадания
JAGM в F16 без потери здоровья добавлен отдельный настраиваемый контактный
урон, описанный ниже. Новый код не отключает коллизии.

## Найденные причины и изменения

`GuidedMissileEntity.advanceFlight` уже проверяет весь отрезок серверного
движения через `FlightCollision.sweepSegment`; заменять его обычным лучом
или мгновенным уроном было бы ухудшением. Однако:

1. Native `sweepBlocks` сообщает неизвестные чанки в `missingTerrain` и
   `firstUnknownFraction`, а `advanceFlight` не проверял
   `terrainKnownThroughContact()`. Незагруженное препятствие могло
   превратиться в воздух; также разрешался контакт за неизвестным
   участком. `KnownMissileTerrain` останавливает такой подшаг до переноса
   позиции/разрешения взрыва. Native `FlightTerrain` продолжает асинхронную
   подготовку коридора; следующий серверный тик повторяет подшаг. Скорость,
   топливо и индекс маршрута на этой остановке сохраняются.
2. Блоки проверялись точным native `MissileShapeSweep`, а обычные сущности —
   только расширенным world AABB ракеты. У диагональной FP5 этот большой
   прямоугольник включает пустые углы между частями модели. Детерминированный
   стенд воспроизводит 567 таких точек. `PreciseMissileEntityContact`
   оставляет native поиск кандидатов, выбор ближайшего блока/сущности,
   владельца, ПВО и порядок обработки; после broad phase проверяет native
   кубы ракеты. Замыкания `MethodHandle` разрешаются один раз на точные
   package-private методы закреплённого JAR; одна mesh готовится лениво
   на отрезок, повторно используется для кандидатов и освобождается после
   проверки. Отдельная физическая сущность не создаётся.
3. Вторая граница native entity collision — generic AABB самолёта не включает
   внешнюю часть крыла. Для guided-ракет только broad phase поиска загруженных
   OBB-целей расширен на 48 блоков; обычные сущности сохраняют прежний query.
   Narrow phase проверяет каждую реальную native OBB против каждого native
   куба ракеты непрерывным 15-осевым SAT. Используются center и все три
   half-edge вектора уже подготовленного native mesh, его angular padding,
   полные оси/extent целевой OBB после `updateOBB`. Крен не теряется, пустоты
   между частями не заполняются. Геометрия ракеты извлекается один раз на
   отрезок; быстрые swept AABB отсеивают пары до SAT без выделения новых AABB
   для каждого куба. Native цикл по-прежнему выбирает ближайший блок/цель и
   применяет те же owner/PVO filters. Query читает индекс сущностей и не
   загружает чанки. Граница проверяется инструментом
   `tools/check_combat_vehicle_bounds.py`; текущие закреплённые данные содержат
   388 native OBB и 41 OBB собственных профилей.

В SBW `FastThrowableProjectile.causeExplode` уже есть флаг `exploded`.
В DSM `GuidedMissileEntity.detonate` проверяет `detonationResolved`, затем
`ActiveFlights.resolve`. Эти native гарантии одного результата сохранены.
Штатная вероятность неразрыва DSM также сохранена: FP5 10%, Storm Shadow 5%,
JAGM 2%, Spike 5%, Peacekeeper 10–15%. Значения сверены с synthetic
`GuidedMissileEntity$1` switch map; номер switch case не равен ordinal enum.
Она не является пропуском hitreg.

Проверка геометрии опровергла предположение, что обычному FP5 недостаточно
native стартового допуска: хвост 3.25 блока, допуск 10 блоков. Поэтому
глобальное расширение иммунитета/игнорирование земли не добавлялось.
FP5 native launcher использует фиксированный угол 12°; положение и
направление проверены для четырёх ориентаций и пяти входных углов настройки.
В модуле уже есть `NativeLauncherPolicy`: NPC-ограничение Industry применяется
только к установкам с `frontierNativeDefenseOwner/Crew/Hold`, а обычный
DeepStrike launcher не должен отменять штатный запуск.

В acceptance-стенде первые установки стояли с шагом 16 блоков вдоль
траекторий. В прогоне 15/20 ракет прошли 24 блока; пять остальных могли
законно встретить соседнюю установку. Стенд исправлен: расстояние между
дальними стартовыми площадками 64 блока по обеим осям, нужные свежие чанки
временно удерживаются до конца проверки. Игровой иммунитет не расширен.

## Прямое попадание и native splash

В прогоне двух клиентов с задержкой 80 мс в каждом направлении настоящий
JAGM двигался более десяти серверных тиков, столкнулся с настоящим F16 и
создал ровно один native `ExplosionEvent.Start`, но не снизил здоровье F16.
Проверка закреплённых классов выявила несколько независимых условий:
DSM не наносит отдельный entity-contact damage; generic AABB не совпадает
с внешними OBB; native атрибуция владельца также терялась (см. ниже).
В точке соприкосновения носа ракеты с прежним AABB самолёта
origin ракеты оказывается примерно в 5.95 блока от base самолёта; радиус
vanilla-взрыва JAGM равен 6. Остаточный splash оказывается ниже native брони
F16 (`All -7`, затем explosion ×4 и общий множитель ×0.65), а внешняя
ударная волна DSM исключает область внутри crater radius 6.

Добавлены явные базовые значения **сырого** контактного урона. Это
намеренная настройка баланса PIS-15, а не измеренные native значения.

| Native kind | Базовый прямой урон |
|---|---:|
| FP5 | 300 |
| Storm Shadow | 220 |
| JAGM | 150 |
| Peacekeeper | 400 |
| Spike NLOS | 120 |

| БЧ | Множитель прямого урона |
|---|---:|
| `none` | 0.30 |
| `he`, физическая FP5 `graphite` | 1.00 |
| `penetrating` | 1.35 |
| `cluster` | 0.65 |
| `thermobaric` | 0.80 |
| `nuclear` | 2.50 |
| `shaped_charge` | 1.75 |

Применяется только native допустимая комбинация kind/БЧ. Не-FP5 graphite
сохраняет EMP без добавленного физического контакта. Параметры server config
`frontier-missiles-server.toml`: база по типу 0–4096, множитель по БЧ 0–10;
ноль отключает компонент, итог ограничен 16384. Native топливо, траектория,
радиусы, осколки и кратер не изменяются.

Цель контакта записывается только в ветке фактического ближайшего
`EntityHitResult` после native launch grace и проверки известного terrain.
Более близкий блок, промах и перехват не дают прямого урона. Одноразовый
token потребляется тем же native объектом взрыва только после разрешённого
`ExplosionEvent.Start`; native dud не создаёт этого взрыва. Вооружённые БЧ
добавляют контактную цель в существующий vanilla-stage список получателей
**до** существующего `ForgeEventFactory.onExplosionDetonate`; удаление цели
защитным обработчиком запрещает контактный урон. Второй искусственный event
для этого этапа не создаётся. У native `none` такого recipient event нет,
поэтому только для этой ветки вызывается один event с тем же объектом
native взрыва. Native отдельный outer-shock event остаётся штатным.

Источник урона — vanilla explosion с native ракетой и её владельцем.
Сохраняются native броня, обработчики защиты и обычные правила hurt cooldown:
контакт и splash в один тик не обещают арифметическую сумму двух значений.
Второй вызов detonate не может повторить прямой урон или создать новый blast.

Отдельно проверен SBW `LivingEventHandler.onExplosionDetonate`: он удаляет
все `VehicleEntity` из recipient list и сам наносит им native splash.
Поэтому уменьшение F16 health после OBB-исправления само по себе **не**
доказывало применение нового контактного профиля. Узкий adapter откладывает
только это native удаление для фактической контактной машины. Native SBW
splash выполняется как раньше; затем остальные Forge handlers могут удалить
цель, запрещая добавленный direct. После события машина удаляется перед
vanilla splash loop, чтобы не нанести splash второй раз. Маркер ограничен
одним этапом события и очищается до native outer shock. Другие машины и
другие взрывы сохраняют исходный обработчик. Entity invulnerability также
исключает добавленный компонент. Стенд сравнивает 20 пар F16 при raw config
0 и 150, проверяет разницу более 100 native health, повторный callback и
LOWEST recipient veto, который разрешает только исходный SBW splash.
20 пар, отличие настроенного direct от исходного splash, LOWEST veto и
одноразовость подтверждены dedicated прогоном `0c1263a1`, описанным ниже.

Для `none` добавлен отдельный `contactOnlyEvent` флаг: во время единственного
нового validation event пропускается только собственный SBW radial handler.
Остальные Forge protection handlers продолжают работу. Это предотвращает
появление нового radial splash там, где native kinetic branch раньше не
отправлял Detonate; исходный native урон 45 и настроенный direct остаются
раздельными. Проба F16 снизу внутри радиуса 4 сравнивает raw config 0 и 150:
добавка должна точно равняться native armor compute для raw 45 (150 ×0.30),
а recipient veto должен оставить ровно исходный kinetic baseline.

## Атрибуция native splash и пустой historical driver

В отдельной реальной kinetic-пробе pristine F16 исходный raw 45 не снижал
native health даже при origin в 0.6804 блока от машины. `javap` подтвердил
две связанные причины в закреплённых JAR:

- DSM `GuidedMissileEntity` наследует обычный `Entity`. Vanilla
  `Explosion.getIndirectSourceEntity` узнаёт только LivingEntity, TNT и
  Projectile, поэтому записанный DSM `ownerId` не попадал в DamageSource.
  Native `MissileExplosion` вызывает constructor без явного DamageSource.
- SBW `VehicleEntity.hurt` сравнивает attacker с native owner/lastDriver
  и запрещает self-hit. У pristine машины default `LAST_DRIVER_UUID` равен
  строке `undefined`; найденный driver и потерянный attacker оба null,
  поэтому `null == null` ошибочно запрещало легитимный native splash.

`NativeMissileBlastOwner` перехватывает только генерацию default source в
полном vanilla Explosion constructor (`DamageSources.m_269093_`). Только
для настоящего native MissileExplosion с Guided direct source он ищет
`ServerLevel.getEntity(ownerId)` — точно как private DSM `getOwner`.
Найденная реальная сущность записывается как causing entity. Исходный
damage-type holder, direct source и optional source position сохраняются.
Вызов `DamageSources.explosion(missile,owner)` здесь намеренно не используется:
он изменил бы native `EXPLOSION` на `PLAYER_EXPLOSION`. Явный DamageSource
других вызовов, другие взрывы и существующая ненулевая атрибуция не меняются.

При вышедшем владельце или неизвестном UUID никакая сущность/игрок не
создаётся. `NativeMissileVehicleOwnerGuard` исправляет только вторую часть:
в точной native self-hit ветке (`ResourceKey` check ordinal 1,
`VEHICLE_STRIKE`) null attacker больше не считается тем же null driver
**только** для explosion-tagged damage от реального Guided source.
Если recorded owner UUID совпадает с native historical-driver UUID машины,
исходный запрет self-hit сохраняется и после logout, когда обе сущности
не разрешаются; UUID не превращается в фиктивного игрока или guessed team.
Настоящий одинаковый owner/driver, native team/PvP, invulnerability,
damage modifiers/броня, crash classification, радиус и Forge events
продолжают обрабатываться исходным кодом. Это не общая правка SBW для всех
взрывов и не расширение owner grace.

Новые dedicated пробы вызывают настоящую native detonate без advanceFlight
и без contact token: 20 pristine F16 и 20 BMP-2 рядом с JAGM HE, 20 T-90A
рядом с native Storm Shadow HE. Blast origin находится на 4.5 блока выше
base с боковым сдвигом 0.25. JAGM даёт здесь raw 7.526: native T-90A
`All -20` законно поглощает его полностью. Поэтому для танка используется
настоящая более мощная БЧ Storm (power5 вместо3), а native броня не меняется.
Сравниваются фактический splash и исходная SBW формула с native armor
compute, точный native holder, реальная атрибуция, один Start и повторный
detonate. Для каждого типа отдельно проверяются same historical driver
при разрешимом владельце и при одинаковом неразрешимом UUID,
null owner, другой неразрешимый recorded UUID, HIGHEST recipient removal до native
SBW splash и Start cancellation. Отдельный настоящий vanilla TNT blast
должен сохранить исходный null source и прежнее поведение SBW: он доказывает
узкую область нового guard. Неиспользуемый UUID моделирует lookup после
logout; фактическое отключение клиента в этой пробе не заявляется.
Применение обоих Mixin и все 141 новые assertions подтверждены финальным
dedicated прогоном `a66255a7` с двумя настоящими клиентами и задержкой 80 мс
в каждом направлении. Полученный splash совпал с исходным native compute:

| Pristine цель / native БЧ | Фактический splash | Native compute |
|---|---:|---:|
| F16 / JAGM HE | 1.3678589 | 1.3678541 |
| BMP-2 / JAGM HE | 1.8313141 | 1.8313174 |
| T-90A / Storm Shadow HE | 4.3146057 | 4.3146057 |

Те же положительные значения получены при null owner и неразрешимом
recorded UUID, с null attacker без подстановки. Same-driver и совпадающий
неразрешимый UUID сохранили нулевой урон; HIGHEST recipient veto и Start
veto также дали ноль при положительном исходном compute. Все 60 pristine
повторов, их duplicate callbacks и три настоящих unrelated TNT проверки
прошли. Финальная идентичность артефакта приведена ниже.

## Native инвентарь и профили PIS-15

Все пять типов используют `deepstrike:guided_missile` с native `kind`;
различаются предметами, динамикой, БЧ и значениями ниже. Скорость здесь —
native км/ч, дальность — блоки. Это установленные значения, а не результаты
измерения полёта по всем предельным маршрутам.

| Предмет | Дальность min/max | Скорость min/default/max | Кратер / частичное разрушение / shock radius | Peak shock damage |
|---|---:|---:|---:|---:|
| `deepstrike:fp5`, `deepstrike:fp5_graphite` | 200 / 3000 | 350 / 850 / 1650 | 30 / 50 / 90 | 100 |
| `deepstrike:storm_shadow` | 50 / 950 | 850 / 1200 / 1850 | 10 / 20 / 30 | 55 |
| `deepstrike:jagm` | 50 / 350 | 900 / 1600 / 2200 | 6 / 10 / 10 | 28 |
| `deepstrike:peacekeeper` | 100 / 6000 | 2000 / 2400 / 5500 | 38 / 65 / 110 | 150 |
| `deepstrike:spike_nlos_missile` | 25 / 3000 | 80 / 450 / 650 | 7 / 12 / 18 | 38 |

Строковые ID предметов сверены с native статическим initializer `BFItems`,
а не выведены из имён Java-полей.

Cruise-маршрут делает до четырёх физических шагов по 0.0125 секунды на тик,
а не телепортируется к цели. FP5/Storm Shadow имеют крейсерскую динамику,
JAGM управляемую ракетную, Peacekeeper отдельный баллистический план,
Spike отдельное наведение/дроссель. ПВО использует native intercept-механику.

`MissileData` сохраняет каждую БЧ в **собственном** `ItemStack` под
`DeepStrikeMissile`: `Kind/Warhead/Engine/Camo/Fuel/MaxFuel/Modules`.
`LauncherBlockEntity.launchNow` читает именно выбранный `firingSlot` и вызывает
`applyMissileData` для новой ракеты. Копировать БЧ по одному только ID предмета
нельзя. Стенд отдельно проверяет сохранение двух одинаковых предметов с
разными БЧ, топливом и range-модулем.

Native turbojet: дальность ×1.25, max speed ×1.15; range-модуль ×1.4 к
дальности; booster ×1.25 к max speed. Для Peacekeeper отдельная формула:
6000 + 2400 за range + 1200 за booster, без повышения max speed.

| Native БЧ | Поведение, которое сохранено |
|---|---|
| `none` | Кинетическое попадание, native локальное разрушение и урон 45 в радиусе 4 |
| `he` | Native базовый взрыв, кратер и внешняя ударная волна |
| `penetrating` | Уменьшенный радиус кратера ×0.55, глубина ×2.4 |
| `graphite` | Native EMP; существующий принятый `GraphitePhysicalBlast` дополнительно сохраняет физический взрыв FP5 |
| `cluster` | Native суббоеприпасы, меньший базовый кратер |
| `thermobaric` | Native огненное кольцо, увеличенные радиусы/ударная волна |
| `nuclear` | Native Peacekeeper thermal pulse и усиленный взрыв |
| `shaped_charge` | Native Spike направленная струя и локальный взрыв |

Список допустимых БЧ проверяет native `supportsWarhead`; он не подменяется
одним общим уроном. Внешняя ударная волна использует расстояние и native LOS
через collision blocks; базовый взрыв учитывает native exposure. Кратер,
стекло и структурные повреждения имеют собственные native правила.
Это не означает, что любая преграда полностью останавливает любой профиль.
Industry и остановка критических узлов проверяются общим стендом отдельно.

## Локальная видимость и эффекты PIS-19

Оставлены native движущиеся тела и native remote-flight синхронизация.
Tracked-сущности не рисуются второй раз как remote contacts; вместо отдельных
framebuffer/light проходов используются обычные модели. Полёт виден через
модель и след, попадание через короткий локальный burst.

Существующий `VanillaMissileEffects`: до 32 trail и 96 burst частиц на
клиентский тик (8/24 при Minimal), до 4 explosion emitters, след не дальше
192 блоков; реже на расстоянии и при сниженной настройке частиц.
`LightweightPatriotEffects`: до 4 коротких explosion частиц на тик не дальше
128 блоков. Серверные декоративные DeepStrike-пакеты ограничены
8 пакетами / 32 частицами на уровень и тик. Полёт/урон этим бюджетом не
ограничиваются. Dynamic lights для ракет не возвращались.

Исправлена отдельная воспроизводимая ошибка следа: при Minimal интервалы
8/16/24 тика сохраняли начальную фазу, а старый `tick % 4 == 0` мог навсегда
подавить FLAME для запуска в нечётный тик. `TrailCadence` решает наличие
flame на каждом принятом ближнем образце. Сначала расходуется слот на
fullbright vanilla FLAME, затем на дым; даже последний доступный слот
не уходит только в тёмный дым. Общий бюджет 32/8 не повышен, дальний след
остаётся дымом, обычная проверка глубины частиц сохраняется.

## Выполнено и что ещё подтверждать

Команда выполнена с `GRADLE_USER_HOME=vendor/gradle-cache`:

```sh
python3 tools/build.py --offline :missile-fix:build :missile-fix:nativeGeometryProbe
```

Результат: **BUILD SUCCESSFUL**, 225 проверок, включая 20 пересечений тонкой
цели и 20 параллельных промахов на каждый native тип, диагональные ложные
AABB-попадания, совместимость обратного broad-phase преобразования,
известный/неизвестный порядок terrain contact и 20 стартовых поз.
`check` теперь также запускает этот стенд. Это headless-проверка реальной
native геометрии, но она не доказывает применение Mixin в игре.
SHA-256 собранного `frontier-flamingo-launch-fix-1.3.0.jar` первого прогона
**до последующего исправления фазового исчезновения flame**:
`f4ecc59d14a4bb02df66ba7fd25bb49410049aa7e52c0efb948a97a691fff288`.

Отдельный `javac --release 17` / `java` прогон `TrailCadenceTest` выполнил
178 проверок. Перебраны все 24 начальные фазы для интервалов 2/4/6/8/12/16/24,
воспроизведены 114 старых полностью тёмных сочетаний, проверены последний
слот бюджета, дальний образец и перевод часов назад. `check` запускает
`trailCadenceTest` при следующих сборках.

Отдельный `javac --release 17` / `java` прогон `DirectImpactProfileTest`
выполнил 210 проверок: пять баз, допустимые БЧ и множители, отключение,
нечисловые/предельные значения, регрессия native брони F16 и 20 серий
одноразового контактного token. Эти проверки сами по себе не подтверждают
загрузку новых Mixin; совместная Forge-сборка и dedicated прогон выполняются
после добавления контактного компонента.

`SweptMissileObbTest` отдельно выполнил 105 pure Java проверок: точное время
контакта с тонким крылом, 20 общих yaw/roll преобразований ракеты и цели,
промахи за крылом, пустые углы диагональной OBB, перенос координат, исходное
пересечение и native angular padding. Native geometry probe дополнен
100 сравнениями нового SAT с исходным native mesh/AABB contact для пяти
ракет. Dedicated helper дополнен 20 контактами внешнего крыла настоящего
F16 в разных ориентациях/кренах, 20 пролётами через пустое место над крылом,
доказательством исключения части путей старым query и ближайшим блоком.
Применение OBB Mixin подтверждено указанными ниже dedicated прогонами;
SBW direct adapter и его native/kinetic границы подтверждены финальным
прогоном `a66255a7`.

`DeferredContactIteratorTest` выполнил 160 pure Java проверок: сохранение
native обработки остальных получателей, различение native consumption и
позднего protection veto, отсутствие второй vanilla splash итерации машины.

`modules/industry/src/combatAcceptance/.../MissileChecks.java` находится в
отдельном acceptance sourceSet, вне production JAR. API:
`begin(ServerLevel,ServerPlayer)`, `tick(ServerLevel)`, `results()`, `done()`.
Он создаёт в свежем мире 20 настоящих FP5 launchers в четырёх направлениях,
загружает native предмет через `load`, вызывает публичный `fire` со штатным
countdown и повторяет тот же mission UUID. Проверяет один spawn, расход одного
слота, вылет за 24 блока, точные sweep-контакты/промахи по блокам, NPC,
native самолёту и настоящему игроку, неизвестный соседний чанк, независимость
NBT БЧ и один kinetic blast при повторном вызове native detonate.
Для прямых коллизионных проб используется private `advanceFlight(Vec3)`;
обычные 20 пусков этот путь не обходят. Подписи `stationary` относятся к
зафиксированному положению цели внутри серверного тика.

Дополнительно helper создаёт 20 настоящих JAGM-контактов с отдельной живой
целью и проверяет потерю здоровья, один Start и отсутствие повторного урона.
Отдельные случаи отменяют Start, удаляют recipient, дают параллельный промах,
ставят более близкую непроницаемую стену и выбирают штатный dud через seed.
Это реальные native collision/detonation вызовы в выделенном тестовом мире;
только непосредственные collision-пробы используют private advanceFlight.

Дополнительный `FlightCombatChecks` проверяет actual server ticks:
летящая JAGM достигает настоящего F16, должна двигаться не менее 10 тиков,
вызвать ровно один native ExplosionEvent и снизить native здоровье самолёта.
Следующий этап запускает настоящий SBW FIM92 в движущуюся native Spike:
проверяется полёт перехватчика, native состояние INTERCEPTED и отсутствие
второго взрыва основной БЧ. Helper не вызывает `hit/detonate/intercept`
напрямую. Он задаёт native маршрут и target UUID; seed 1 используется
только для воспроизводимого боевого патрона теста, штатная вероятность
неразрыва production не меняется. Это проверка native перехвата вручную
заданной цели, а не доказательство полного автоматического radar/crew ПВО.
Настоящий CombatGuest занимает native cockpit F16; на время lead-in
CombatOwner находится рядом для штатного открытия карты и внешнего обзора.
Исходные позиции/углы обоих игроков и invulnerability гостя восстанавливаются.
На Start helper только читает одноразовую контактную цель для диагностики,
не потребляя token и не подменяя native событие.

Промежуточный общий dedicated прогон `run-combat-d120ce9c-server`, server runId
`b6683caa-9b7d-4e97-937f-24f5850b0e51`, подтвердил для JAR
`f4ecc59d…fff288` все начальные серверные ракетные проверки:
5 типов × 20 контактов и 20 промахов по блокам/NPC/native машине/игроку,
диагональный ложный контакт и его отклонение применённым Mixin,
остановку у реально неизвестного чанка, единственный kinetic blast,
независимость сохранённых БЧ и принятие 20 native countdown.
Оба настоящих клиента сообщили native missile visible и changing position.
Общий прогон прервался в другом industrial fixture (`industrialNode`,
`NoSuchElementException`) до финальных результатов вылета всех 20 FP5;
это не является полным успешным прогоном и не подтверждает последующую
версию FLAME/cadence или новый `FlightCombatChecks`.

Следующий промежуточный прогон `run-combat-c41c4dfe-server`, server runId
`6a5fc042-557b-4f8d-b7ac-f546bdf9f7d1`, использовал JAR
`5c882ea9454926a67158d39a2ecbbf8dbfd0672bcef0ec839e69026567352bd4`
и задержку 80 мс в каждом направлении. Все 20 FP5 после разнесения площадок
прошли 24 блока; все 20 countdown дали ровно одну ракету и потратили один
слот. Все 20 JAGM direct-contact серий с NPC и 20 повторных detonate прошли,
как и отмена Start, удаление recipient и параллельный промах. Реальный FIM92
перехватил движущуюся Spike без второго взрыва её БЧ. Полный прогон не прошёл:
817/840 серверных assertions, по 10/11 на клиентах; natural JAGM/F16 health
всё ещё false, wall/dud assertions нуждались в отдельной диагностике.
Эта версия ещё не содержит OBB-уточнение цели и следующие helper-изменения.
Wall assertion теперь учитывает неизменённый минимум 1 vanilla splash при
нулевом exposure; dud seed подбирается на реальном Entity RandomSource.
Окончательный результат нужно связывать со следующим хешем/прогоном.

Прогон `run-combat-4832cc73-server`, server runId
`88e923bc-26f5-4b71-9311-b1d34fb3ce9c`, SHA missile JAR
`0d287efa3f6537d431e27a10be9bd284bf0896b3c0d52c58aa57f86ade5f972a`,
при 80 мс в каждом направлении подтвердил все 42 OBB assertions, natural
JAGM/F16 collision, 20 FP5 пусков и native FIM92 перехват. Оба клиента прошли
13/13, сервер 999/1000; единственным false оставался dud test с ошибочным
порогом 5% вместо подтверждённых native JAGM 2%. Этот прогон ещё не включает
SBW direct-recipient adapter: F16 360→347.37476 тогда терял native splash.

Новый `NativeRouteChecks` в этом же прогоне подтвердил **15/15** естественных
полетов: full native rocket fuel, `none` для исключения dud/HE нагрузки,
default speed ближнего/среднего маршрута и native max speed дальнего.
Начальные 3×3 чанка удерживал стенд, дальнейший коридор — native FlightTerrain.
Никакой private advanceFlight не вызывался. Все native отчёты дали IMPACT
и координаты ResultX/Y/Z с ошибкой менее 2 блоков от заданной воздушной цели.

| Тип | Дальность near / mid / max | Тики до IMPACT near / mid / max | Максимальная ошибка |
|---|---:|---:|---:|
| FP5 | 220 / 1600 / 3000 | 121 / 277 / 370 | 1.001 |
| Storm Shadow | 70 / 500 / 950 | 87 / 168 / 220 | 1.023 |
| JAGM | 70 / 200 / 350 | 77 / 107 / 131 | 1.001 |
| Peacekeeper | 120 / 3050 / 6000 | 520 / 1047 / 1303 | 1.054 |
| Spike NLOS | 45 / 1512.5 / 3000 | 55 / 413 / 561 | 0.270 |

Storm использовал native `releaseFromAircraft`, Peacekeeper native selector
и ballistic plan, Spike native `configureSpike`. Детали фактического пути,
скорости и полного native отчёта сохранены в ignored
`combat-native-routes.json` тестового профиля. Кинетические route-пробы не
доказывают все HE/material эффекты или повторяемость каждой БЧ на всех дальностях.

Также пройден native laptop backend: настоящий предмет через `useOn` записал
`BoundLauncher`; настоящий LaunchMission прошёл native encode/decode, а handler
получил sender настоящего соединения игрока. Дублированный command UUID дал
один countdown, один missile и расход одного слота; ракета вышла за 24 блока.
Это проверка item/codec/backend, а не автоматический клик по клиентскому UI.
API отдельного helper: `begin/tick/done/results/details`, локальный лимит
6000 тиков. Он не включён в production JAR.

Последний полный baseline **до двух новых owner/null-driver Mixin**:
`run-combat-0c1263a1-server`, server runId
`11558ab4-cfcd-4de6-b1f9-6ffb7719fb2f`, missile JAR
`d374ede7d1e10aaf96696a2546b419637d019778d815881ceb6a1057c1e912f9`.
При 80 мс в каждом направлении прошли 1186/1186 общих server assertions
и по 14/14 у обоих настоящих клиентов. Это подтверждает предшествующие
direct/OBB/dud/kinetic/launcher/route/intercept изменения. В усиленной
kinetic baseline-пробе использован настоящий distinct CombatGuest как
historical driver; новые pristine пробы выше устраняют эту зависимость
и требуют отдельно подтвердить новую атрибуцию.

В том же baseline пройден физический переход JAGM→F16→wreck→земля→crew:
после естественного попадания стенд не вызывает hurt/destroy/wreck/tick,
а разрешает обычную гравитацию и ждёт 40–80 настоящих серверных тиков.
Свежая bedrock-платформа на y119 сохраняет землю после кратера. Проверяются
native isWreck, сохранение экипажа в воздухе, отпускание crew на земле,
тот же plane UUID с одним join и один plane-source ExplosionEvent.Start.
Оба клиента подтвердили получение настоящего wreck через entity tracking.

Финальный полный прогон после owner/null-driver исправления:
`run-combat-a66255a7-server`, server runId
`fdc1a694-6921-4840-99f1-ab02bc7251c9`; owner runId
`bada8206-37c9-4047-bc1f-f6d862760c79`, guest runId
`f0191452-4c20-44f0-a983-b84df5b536be`. SHA-256 missile JAR:
`40828a577990477cf0ecb3ff1abb5528db23c9f05a65cba9a868fb8ecbf598a5`.
При 80 мс в каждом направлении результат — **1327/1327** общих серверных
assertions и **14/14** на каждом клиенте, включая все новые 141 splash/
owner/guard assertions. В этом прогоне повторно прошли native запуски,
контакты/промахи, direct/kinetic veto, физический wreck/crew переход,
15 естественных near/mid/max маршрутов и native FIM92 перехват.

Результаты повторного прогона двух клиентов, задержки, moving/edge целей,
ближних/средних/предельных маршрутов, ноутбука и реального перехвата нужно
брать из отдельного отчёта общего прогона. Нельзя объявлять все PIS-13,
PIS-15 и PIS-16 закрытыми только на основании компиляции или этого документа.
