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

## Интеграции PIS-1

Для новой ветки интеграций дополнительно выполните
`python3 tools/fetch_integrations.py`. Скрипт скачивает только шесть точных
Forge1.20.1 файлов из `integration-libraries.lock.json` и проверяет SHA256.
В полном новом комплекте он берёт проверенные файлы из `integration-libraries/`
без скачивания. Это кандидаты до игровой приёмки; исходные 27 JAR и их
`modpack.lock.json` не заменяются. GeckoLib4.8.4 и Architectury9.2.14
используются из принятого профиля.

Обычный `tools/create_test_profile.py run-my-test` теперь копирует
пересобранные модули, закреплённые интеграции и версионируемые KubeJS-скрипты.
`--baseline` по-прежнему создаёт исходную копию без новых библиотек.
Проверка нового профиля:

```sh
python3 tools/verify_project.py --profile run-my-test
python3 -m unittest discover -s tools/tests -v
python3 tools/check_integration_assets.py
```

Запись тестовых миров/логов идёт только в созданный профиль. Упаковщик
исключает любой каталог с `test-profile.json`, включая профили с нестандартным
именем. В Git не добавляются vendor, runtime, миры и журналы.

Для автоматизированной локальной проверки есть отдельный helper:
`./gradlew :industry:integrationTestJar`, затем
`python3 tools/create_test_profile.py run-my-check --test-helper`.
Helper не входит в production JAR и releaseJars. Он запускается только при
явном свойстве `frontier.pis1Smoke=true` и совпадающем runId свежего профиля.

Опциональный переносимый запуск клиента из текущего проекта (Java17):

```sh
mkdir -p vendor/runtime
# Создайте vendor/runtime/launcher_profiles.json с содержимым {"profiles":{}}.
java -jar installers/forge-1.20.1-47.4.0-installer.jar --installClient vendor/runtime
python3 tools/run_test_client.py run-my-check --prepare-only
python3 tools/run_test_client.py run-my-check
```

Launcher использует только локальное тестовое имя без аккаунта/токена,
проверяет официальные Minecraft SHA1 и создаёт свежий мир с runId.
Для графического запуска нужен доступный дисплей/OpenGL. `--restart`
разрешён только для того же созданного тестового мира с matching marker;
`--server localhost --port <порт>` подключается к отдельному локальному
тестовому серверу. При запуске сервера EULA принимает владелец явно.

Для управления настоящими тестовыми клиентами установлен отдельный
MC Pilot 0.20.2: `python3 tools/mct.py --cli-version`. Установка, закрепление
клиентского JAR и подключение без повторного скачивания Minecraft описаны в
[MC-PILOT-RU.md](docs/testing/MC-PILOT-RU.md). В принятую игровую сборку этот
необязательный инструмент не добавляется.

Детали контракта: `docs/INDUSTRY-ENERGY-JADE-RU.md`,
`docs/ui/LDLIB-RU.md`, `instance/kubejs/README-RU.md`.
Текущее состояние проверки: [PIS-1](docs/testing/PIS-1-2026-10-25.md).

Для проверки выделенного сервера нужны три отдельных свежих профиля с
`--test-helper` и одинаковыми пересобранными модулями: `run-server`, `run-owner`,
`run-foreign`. Точный Forge 47.4.0 server runtime устанавливается внутрь
`run-server` через закреплённый установщик с `--installServer run-server`.
Владелец принимает EULA для этого профиля. В его `server.properties` указать
`server-ip=127.0.0.1`, свободный порт и `online-mode=false` для локальных тестовых
имён. Затем в отдельных терминалах:

```sh
python3 tools/run_test_server.py run-server --dedicated-acceptance
python3 tools/run_test_client.py run-owner --server 127.0.0.1 --port 25565 --username Pis1Owner
python3 tools/run_test_client.py run-foreign --server 127.0.0.1 --port 25565 --username Pis1Foreign
python3 tools/run_dedicated_observations.py run-server run-owner run-foreign
```

В командах клиентов заменить порт на выбранный. Helper требует ровно два
настоящих loopback-подключения с разными UUID, совпадающие runId профилей и
пустой промышленный ledger. Bridge передаёт этапы обоим клиентам и ждёт их
фактические подтверждения. Результаты и кадры остаются внутри тестовых профилей;
в Git их не добавлять. Успех сборки или отдельного сценария не завершает игровую
приёмку и проверку стабильности.

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
