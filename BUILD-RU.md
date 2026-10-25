# Сборка Frontier Nations

В проекте шесть отдельных Forge-модулей. Сборка использует Java 17, Minecraft
1.20.1, Forge 47.4.0, ForgeGradle 6.0.54 и Gradle Wrapper 8.8. Нужны JDK 17
(не только JRE), Python 3.10+ и интернет для первой загрузки Gradle/Forge/Minecraft.
Зависимости модов берутся из точного снимка `instance/mods`, а не из новых
версий на сайтах модов. Проверяются все SHA-256 из `modpack.lock.json`.

В Linux/macOS задайте `JAVA_HOME` для установленного JDK 17, добавьте его `bin`
в `PATH` и выполните из корня проекта:

```sh
export JAVA_HOME="/путь/к/jdk-17"
export PATH="$JAVA_HOME/bin:$PATH"
python3 tools/build.py
```

В Windows PowerShell:

```powershell
$env:JAVA_HOME = 'C:\путь\к\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
py tools/build.py
```

Скрипт сначала создаёт локальный Maven-репозиторий `vendor/maven`, затем
запускает Wrapper. Ни один путь к компьютеру автора не зашит в сборку.
`GRADLE_USER_HOME` можно задать самостоятельно для общего локального кеша.
Автоматическая загрузка JDK выключена: команда `java -version` должна показать 17.

Прямые команды Gradle после подготовки зависимостей:

```sh
python3 tools/prepare_dependencies.py
./gradlew build
./gradlew :arcade:build
./gradlew :nations:build
./gradlew :industry:build
./gradlew :style:build
./gradlew :aviation:build
./gradlew :missile-fix:build
```

В Windows используйте `gradlew.bat`. Если весь инструментарий уже находится
в кеше, доступен `python3 tools/build.py --offline build`.
Для полной пересборки: `python3 tools/build.py clean build`.

| Модуль | Результат в `modules/<модуль>/build/libs/` |
|---|---|
| arcade | `frontier-arcade-vehicles-1.7.0.jar` |
| nations | `frontier-nations-1.20.1-0.1.2-alpha.jar` |
| industry | `frontier-industry-1.20.1-0.4.0.jar` |
| style | `frontier-arcade-style-1.1.0.jar` |
| aviation | `frontier-light-aviation-1.1.0.jar` |
| missile-fix | `frontier-flamingo-launch-fix-1.3.0.jar` |

Общая команда `build` также собирает эти шесть итоговых JAR в
`build/team-mods/`. Отдельный вызов коллектора — `./gradlew releaseJars`.

`build` заканчивается задачей `reobfJar`: итоговые JAR имеют имена методов
Minecraft для обычного Forge. JAR содержат исходные MixinConfigs; обработчик
аннотаций Mixin выключен, потому что текущие mixin используют явные runtime
дескрипторы и `remap=false`. SimpleBedrockModel и MAE извлекаются из вложенных
архивов установленного SBW только для компиляции. В готовые модули эти
библиотеки не встраиваются; их runtime-копии остаются внутри исходного SBW.

## Сохранённые части Industry

В `modules/industry/preserved-combat` находятся две точные production-копии
`NativeCombatEvents.class` и `ReadyWeapons.class`. Доступные одноимённые Java
файлы расходятся с установленной версией, поэтому исключены из компиляции.
Подготовка зависимостей сверяет классы с закреплённым Industry 0.4.0.
ForgeGradle создаёт их отдельную копию в официальных именах для компилятора
и среды разработки, а `restorePreservedCombat` после `reobfJar` помещает
исходные runtime-байты обратно в выпускной JAR. Это обязательная часть
сборки: два исходника нельзя считать воспроизводимыми из Java до восстановления
точной версии. Изменения в этих файлах пока не попадут в собранный мод.

В Arcade каталог `dev/frontier/arcade/smoke` сохранён для работы команды,
но исключён из обычной сборки, как и в текущем production-JAR. Два исторических
smoke-класса Nations остаются частью его исходного состава.

Ресурсы style уже расположены в `resourcepacks/frontier_ifv`. Сборка сохраняет
этот путь; встроенный pack продолжает выбираться существующим кодом с приоритетом TOP.
Три authoring-копии Aviation `vehicle/*_lod.geo.json` исключены из выпуска,
как в принятом JAR; рабочие дальние модели остаются в `vehicle_lod`.
Патч `recruits-tacz-1.022-frontier.1.jar` используется из снимка без пересборки.

## Среда разработки и ручной запуск

Импортируйте корневой `settings.gradle` как Gradle-проект в IDE с JDK 17.
`./gradlew :arcade:genIntellijRuns` создаёт конфигурации запуска.
Явные команды `./gradlew :arcade:runClient` и
`./gradlew :arcade:runServer` доступны для ручной проверки. Все шесть модулей
подключаются из outputs исходников. Шесть старых custom-JAR из снимка в runtime
classpath не попадают, поэтому повторных mod ID от них нет. Рабочая папка —
локальная `run/`; копировать туда production-миры не требуется.

Эти команды запускают игру только по явному вызову. В рамках подготовки
проекта клиент и сервер не запускались. Полная совместимость dev-запуска
не подтверждена: существующие mixin с `remap=false` местами содержат SRG-имена
методов, которые могут потребовать адаптации для dev-среды с официальными именами.
Проверка игрового поведения делается отдельно в тестовой Forge-инстанции.

Сборка не устанавливает результаты в `.minecraft`, не заменяет закреплённые
JAR в `instance/mods` и не обращается к пользовательским мирам. Для проверки
изменений используйте отдельную тестовую инстанцию и переносите нужный JAR вручную.

## Карта Xaero и визуальные контуры стран

Управление: [XAERO-COUNTRIES-RU.md](docs/player/XAERO-COUNTRIES-RU.md).
Сборка `:nations:build` запускает проверки геометрии, ограничений сетевых
пакетов, NBT и миграции старых флагов. Вложенная XaeroLib извлекается только
для компиляции из закреплённого Xaero 1.47.0; в runtime второй JAR не добавляется.

Также выполняются `:nations:mapStateTest` (фоновые полосы, поздние ACK и таймауты)
и `:nations:recruitsSchedulerLifecycleTest` (native планировщик при остановке
dedicated-server). `:industry:operationsPayloadTest` проверяет полный большой
Unicode-снимок КПК, wire codec и ограничения распаковки; включён в Industry check.
Результаты игровой проверки: [MULTIPLAYER-2026-10-25.md](docs/testing/MULTIPLAYER-2026-10-25.md).

`python3 tools/run_map_client.py run-xaero-polished` запускает отдельный
подготовленный профиль из текущего проекта через `vendor/runtime` с точными
Minecraft1.20.1/Forge47.4.0. Требуется JDK17 и ранее установленный Forge client
runtime. Аккаунты и токены лаунчера не используются. Автоматических тестовых
действий при обычном запуске нет.

Игровые проверки отдельно собираются командой
`python3 tools/build.py :nations:mapAcceptanceJar`. Helper находится только в
`modules/nations/build/acceptance/frontier-map-checks.jar`; он не включается в
Nations production JAR или в `build/team-mods`. Его нужно явно скопировать в
свежий тестовый профиль. Запуск `tools/run_map_client.py <профиль> --acceptance`
проверяет marker/runId. Для двух настоящих клиентов задаются `--username MapOwner`
и `--username MapGuest --guest`, одинаковый `--port`, а владельцу —
`--peer-report <профиль-гостя>/peer-progress.json`. Интегрированный тестовый сервер
слушает только loopback; helper создаёт native страны/владение только в новом
тестовом мире. `--restart` разрешает проверку сохранённого мира того же runId.
Результаты и кадры остаются в тестовых профилях и не добавляются в Git.

Для отдельного подготовленного loopback dedicated-server acceptance-владелец
может использовать `--acceptance --dedicated --username MapOwner --port <порт>`;
гостю по-прежнему нужны `--acceptance --guest --username MapGuest` и тот же порт.
Этот режим не создаёт встроенный сервер и доступен только с явно установленным
отдельным helper. Для повторного входа после перезапуска тестового сервера
добавляется `--restart`; пользовательские миры для сценария не используются.

## Боевые multiplayer проверки PIS-9 / PIS-10

```sh
GRADLE_USER_HOME="$PWD/vendor/gradle-cache" python3 tools/build.py --offline build :industry:combatAcceptanceJar
python3 tools/verify_project.py
python3 tools/check_combat_vehicle_bounds.py
python3 tools/run_combat_checks.py --latency-ms 80
```

Runner требует Linux/Xvfb и ранее установленные точные Forge client/server
runtime в `vendor`. Создаёт свежие `run-combat-*` профили, loopback dedicated
сервер и два настоящих клиента. Задержка задаётся в каждом направлении;
без параметра используется обычное соединение. Helper
`modules/industry/build/acceptance/frontier-combat-checks.jar` отделён от
production и не входит в `build/team-mods`; нужны явный opt-in и marker/runId.
Результаты и границы проверки:
[PIS-COMBAT-2026-10-25.md](docs/testing/PIS-COMBAT-2026-10-25.md).
