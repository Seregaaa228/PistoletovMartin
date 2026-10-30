# Управление авиацией: проверка кода 26 октября 2026

Это первый проход для JAR с SHA `9407e13b…`. Последующее исправление управления,
новый JAR и актуальные проверки описаны в
[отчёте стабильности](AVIATION-STEERING-STABILITY-2026-10-26.md).

Ветка `codex/flight-controls`. Minecraft 1.20.1, Forge 47.4.0, Java 17.
Контракт: [CONTROL-CONTRACT.md, revision 2](../aviation/CONTROL-CONTRACT.md).
Исходное рабочее дерево было чистым; изменения ограничены Aviation и документацией.
Зависимости, lock-файлы, native ID, принятый профиль и Industry preserved-combat
не менялись.

Реализованы плавные угловые скорости и реверс для 12 типов авиации, запоминаемый
газ, запуск/остановка двигателя, тормоза, шасси/зависание, клиентский прогноз,
серверные сессии управления и общий API для будущего пилота LivingEntity.
Сам AI в изменение не входит. Управление описано в [инструкции игрока](../player/АВИАЦИЯ.md).

По уточнению пользователя проверка исполнителя ограничена кодом, контрактами,
bytecode и сборками. Игра, клиенты и серверы не запускались; поведение проверяет
пользователь. Старые игровые отчёты не засчитываются этому изменению.

## Распределение и ревью

Одновременно работали координатор и максимум три субагента. Общие регистрации,
build-файлы, mixin-конфигурация и Git оставались у координатора.

| Работа | Модель и уровень |
| --- | --- |
| Контракт A и независимое итоговое ревью J | GPT-6 Astra, xhigh; отдельный агент ревью |
| Физика C, native D, сервер/сеть E, клиент F и проверка интеграционных путей H | GPT-6.1 Sol, high |
| Простые типы B, клавиши/локализации/HUD G | GPT-6 Luna, medium |
| Базовые проверки, регистрационный аудит, контрольные суммы I | GPT-6 Luna, low |

Замечания независимого ревью исправлены и повторно проверены: темп газа
вертолёта, плавное снятие форсажа, миграция/повреждённые NBT и сохранение будущей
версии расширения, завершение клиентского управления при native takeover,
конфликт H у самолётов, зависимость форсажа от прежних native-флагов и
наследование EngineInfo.Tom6. Для Tom6 отдельно проверены коэффициенты, расход
двух native energy sites, пределы углов и отсутствие чужого звука запуска.
Открытых важных или критических замечаний в заключительном ревью нет.

## Фактически выполненные команды

Все команды выполнены из корня проекта, завершились с кодом 0.

| Команда | Результат |
| --- | --- |
| `python3 tools/verify_project.py` | 27 pinned mods, 3378 файлов профиля, 6 модулей, 2 preserved combat .class; CRC проверены |
| `python3 tools/run_contract_checks.py` | 8 standalone suites: 10042/12/57/6/65/9/52/46 проверок |
| `python3 -m unittest discover -s tools/tests -v` | 8/8 тестов |
| `python3 tools/check_integration_assets.py` | 24 book entries/model links |
| `GRADLE_USER_HOME="$PWD/vendor/gradle-cache" python3 tools/build.py --offline :industry:build` | BUILD SUCCESSFUL; 49 задач, 23 выполнены, 26 up-to-date |
| `GRADLE_USER_HOME="$PWD/vendor/gradle-cache" python3 tools/build.py --offline build` | BUILD SUCCESSFUL; 94 задачи, 56 выполнены, 38 up-to-date |
| `git diff --check` | Без ошибок |

Сборки выполнялись последовательно, с закреплённым локальным кешем. Предупреждение
Gradle о deprecated features не является ошибкой сборки.

## Новые проверки Aviation в `check`

| Проверка | Что проверяет | Результат |
| --- | --- | --- |
| `flightControlModelTest` | Профили, отклик/затухание/реверс, wrap/finite, изоляция, газ, фазы двигателя, тормоза/hover | 1948 |
| `flightAuthorityTest` | Чистый lease ledger: владелец, epoch/sequence, timeout, одноразовые mode requests, handoff | 24 |
| `flightPacketCodecTest` | Round trip, пределы байтов UTF-8, некорректные/лишние данные, значения и размеры | 282 |
| `flightClientStateTest` | Чистые mouse/replay/interpolation/correction и terminal BUSY/retired epoch/RELEASED | 34 |
| `nativeFlightHooksContractTest` | Точные native physics/persistence/Su24/Tom6 bytecode-якоря | PASS |
| `nativeFlightPacketHooksContractTest` | Оба server guards, отдельный UAV-путь, шесть client mixins, aliases, отсутствие дублированного pilot broadcast | 70 |
| `nativeFlightPersistenceContractTest` | Реальные CompoundTag/NBT: legacy, idle, OFF/RUNNING/transient, повреждённый v1, неизменный будущий v2 | PASS |
| `nativeFlightBoostContractTest` | Реальный sampling policy с pinned EngineInfo, текущий boost, ground factor, Tom6 dispatch/caps/energy/angles/sound | PASS |

В последнем тесте экземпляры EngineInfo создаются без конструктора, требующего
реестра звуков; используются настоящие native типы и getter-методы. ASM проверяет,
что production `sample(Entity)` вызывает проверяемую политику и не читает старый
sprint-флаг. Это проверка кода без загрузки мира.

Четыре прежних impact/geometry проверки Aviation также прошли. Аудит подтвердил
регистрацию всех 12 JavaExec test mains, 27 уникальных mixin-классов и соответствие
упакованного JSON исходникам. RU/EN: 32/32 ключа Aviation и 2/2 DeepStrike,
параметры форматирования совпадают.

## Артефакты полной сборки

Файлы находятся в `build/team-mods/`. Aviation побайтно совпадает с
`modules/aviation/build/libs/frontier-light-aviation-1.1.0.jar`.
Принятые JAR в `instance/mods` автоматически не подменялись. Новая авиация нужна
и клиенту, и серверу; ревизия нового канала строго `1.1.0:flight1`.

| JAR | SHA256 |
| --- | --- |
| `frontier-light-aviation-1.1.0.jar` | `9407e13b7098a4b19bfcb7864c37b2b4635b005201b911891b7f60282b7aa977` |
| `frontier-arcade-style-1.1.0.jar` | `8e07e5c34582b6e65bed3147fdd1cc9eb4ecdea1cc998b0de14a723f40f0642c` |
| `frontier-arcade-vehicles-1.7.0.jar` | `59e3182b029d04adb4dcccd2cb52751f3c1b9e388ea625e465e1b1f22e2c8d6a` |
| `frontier-flamingo-launch-fix-1.3.0.jar` | `40828a577990477cf0ecb3ff1abb5528db23c9f05a65cba9a868fb8ecbf598a5` |
| `frontier-industry-1.20.1-0.4.0.jar` | `4b4a05fc278e6502fbc167ad030a4b217764ef81e2a1da538651506282b0ab97` |
| `frontier-nations-1.20.1-0.1.2-alpha.jar` | `e855b7250fe2d6a13ed22649b40a6856096c21607668c2e068ff7ba247ded84f` |

## Производительность и границы доказательств

Измерен только чистый `FlightControlModel.step`: 16 независимых состояний,
14 самолётов/2 вертолёта, чередующиеся оси/газ/форсаж, 10000 прогревочных и
20000 измеряемых пакетов по 16 шагов. Java 17.0.21, i7-11800H; p50 0.001423 мс,
p95 0.002613 мс, p99 0.003897 мс, максимум 1.160345 мс на пакет. Положительная
конечная контрольная сумма подтверждена. Это локальный микробенчмарк общей JVM;
он не измеряет native физику, мир, сеть, GC под игровой нагрузкой или FPS/TPS.
Полный бюджет p95 <= 2 мс/тик для 16 машин этим результатом не подтверждён.

Не выполнялись свежий мир и реальные полёты всех 12 типов, reload/restart,
выделенный сервер с двумя клиентами и RTT 0/160/300 мс, пассажир/наблюдатель,
реальные пересадки/отключения/чанки, пилот NPC через сервис, оружие и исключения
UAV/наземной техники, нагрузка 1/8/16 машин. Эти сценарии проверяет пользователь.
Применение mixins в работающем Minecraft не выводится из bytecode-проверок.
FlightAuthorityTest проверяет чистый ledger; привязки FlightControlService к
живым местам, измерениям, spectator/NPC и lifecycle рассмотрены по коду,
но не исполнялись в мире. Аналогично чистые клиентские тесты не запускают GUI/focus.

Локальные полные журналы, промежуточные ревью и harness микробенчмарка сохранены
в игнорируемом `vendor/flight-controls-work/`; в Git не добавлены миры, адреса,
учётные данные, кэши и сторонние JAR.
