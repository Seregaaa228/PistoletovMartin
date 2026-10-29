# Frontier Nations — рабочий проект команды

Minecraft Java **1.20.1**, **Forge 47.4.0**, **JDK 17**.

Здесь собраны текущий клиентский профиль, исходники шести собственных дополнений,
патч Recruits/TaCZ, ресурсы и материалы проверенных выпусков. Папка не зависит
от прежних чатов Codex и не использует личный `.minecraft` как рабочую директорию.

## Начало работы

1. Скачайте [полный комплект проекта из Releases](https://github.com/Seregaaa228/PistoletovMartin/releases/tag/frontier-team-0.4.0)
   и распакуйте его, включая `instance/mods`. Для работы через Git клонируйте
   репозиторий и перенесите в него исключённые папки из полного комплекта.
2. Установите JDK 17. Настройте `JAVA_HOME`, если установлено несколько JDK.
3. Выполните `python3 tools/verify_project.py` (`py -3` на Windows).
4. Откройте `settings.gradle` как Gradle-проект в IntelliJ IDEA.
5. Инструкции по сборке и запуску находятся в [BUILD-RU.md](BUILD-RU.md).

Первый Gradle-запуск загружает инструменты и зависимости; для него нужен интернет.
Сторонние моды взяты из точного снимка сборки, их версии нельзя автоматически
заменять более новыми. Собранные рабочие JAR не подменяют принятый профиль сами.

После сборки создайте отдельный профиль для проверки:

```sh
python3 tools/create_test_profile.py ../Frontier-Nations-test
```

Укажите созданную папку как игровую директорию отдельного профиля лаунчера с
Forge47.4.0. Для копии исходной принятой сборки добавьте `--baseline`. Скрипт
отказывается перезаписывать существующую папку и не запускает игру сам.

## Проверка перед передачей

Выполняйте команды из корня проекта с JDK 17. Базовый маршрут проверки:

```sh
python3 tools/verify_project.py
python3 tools/run_contract_checks.py
python3 -m unittest discover -s tools/tests -v
python3 tools/check_integration_assets.py
GRADLE_USER_HOME="$PWD/vendor/gradle-cache" python3 tools/build.py --offline :industry:build
GRADLE_USER_HOME="$PWD/vendor/gradle-cache" python3 tools/build.py --offline build
```

Offline-сборка возможна только при уже заполненном кеше; иначе сначала выполните
`python3 tools/build.py` с доступом к закреплённым источникам зависимостей.
`run_contract_checks.py` отдельно компилирует и запускает Java 17 contract-проверки
без игры. Gradle `./gradlew :industry:test` их не заменяет. Проверки профиля,
ресурсов и сборки не подтверждают игровое поведение: reload/restart, выделенный
сервер и сценарий с двумя клиентами засчитываются только после реального запуска
в отдельном свежем мире. Правила изоляции и обязательные инварианты — в
[AGENTS.md](AGENTS.md), подробности запуска — в [BUILD-RU.md](BUILD-RU.md).

## Состав

| Каталог | Назначение |
|---|---|
| `modules/arcade` | Герань-2, ретранслятор, пульты, баланс и совместимость техники; 1.7.0 |
| `modules/nations` | Страны, границы, политическая карта и совместимость Recruits; 0.1.2-alpha |
| `modules/industry` | Заводы, электричество, ремонт, строительство, исследования и КПК; 0.4.0 |
| `modules/style` | Единые модели, иконки, облегчённые эффекты и фильтрация каталогов; 1.1.0 |
| `modules/aviation` | Собственные самолёты и правила взаимодействия вооружений; 1.1.0 |
| `modules/missile-fix` | Исправление запуска Flamingo и облегчённые эффекты ракет; 1.3.0 |
| `patches/recruits-tacz` | Исходный ASM-патч поверх точного стороннего JAR; 1.022-frontier.1 |
| `instance` | Принятый профиль из 27 модов, общие настройки и стандартный набор TaCZ |
| `docs/player` | Инструкции по управлению для игроков |
| `reference` | Исходные архивы выпусков и исторические отчёты |
| `licenses` | Исходные лицензии и фактические объявления из JAR |

`modpack.lock.json` фиксирует имена и SHA256 всех установленных модов.
`instance-files.lock.json` фиксирует переданный профиль. В комплект не включены
аккаунты лаунчера, пользовательские миры, сохранённые серверы, личные карты и
рабочие журналы игры.

## Third-party mods used in Frontier Nations

The accepted Minecraft 1.20.1 / Forge 47.4.0 profile includes these third-party
mods. Versions refer to the pinned JAR files, not necessarily the latest releases.

| Mod | Pinned version |
|---|---|
| Architectury | 9.2.14 |
| Cloth Config | 11.1.136 |
| Curios API | 5.14.1 |
| DeepStrike | 0.9.0 |
| Embeddium | 0.3.31 |
| FerriteCore | 6.0.1 |
| GeckoLib | 4.8.4 |
| Just Enough Items (JEI) | 15.56.0.205 |
| Kotlin for Forge | 4.12.0 |
| Mob Player Animator | 1.3.3 |
| ModernFix | 5.27.89 |
| Player Animator | 1.0.2-rc1 |
| Villager Recruits | 1.15.2 |
| Recruits: Living Villages | 1.1.0 |
| Superb Recruits | 1.115 |
| Superb Warfare | 0.8.9.1-hotfix (993063bed) |
| Timeless & Classics Guns: Zero (TaCZ) | 1.1.8-hotfix2 |
| Uncomplicated FPV | 1.0.1 |
| Xaero's Minimap | 26.6.0 |
| Xaero's World Map | 1.47.0 |

The profile also contains six Frontier-developed modules listed above and the
Recruits/TaCZ compatibility patch `1.022-frontier.1`. The complete 27-JAR
inventory, source links and declared licenses are recorded in
[modpack-manifest.json](modpack-manifest.json); file hashes are recorded in
[modpack.lock.json](modpack.lock.json). Additional PIS-1 integrations described
below are separate from this accepted profile. This inventory documents which
mods are included; it is not a count of users or downloads.

## Интеграции PIS-1

В ветке PIS-1 разрабатываются интеграции LDLib, SmartBrainLib, GeckoLib4,
KubeJS, Patchouli, Jade и Forge Energy. Дополнительные библиотеки закреплены
отдельно в `integration-libraries.lock.json`; исходный принятый профиль
сохраняется для сравнения. Подготовка и отдельный тестовый запуск описаны
в [BUILD-RU.md](BUILD-RU.md#интеграции-pis-1), текущее состояние и открытые
проверки — в [кратком отчёте PIS-1](docs/testing/PIS-1-2026-10-25.md).

## Передача каталога Industry

Сервер делит каталог по рецептам: JSON каждой части занимает не более 256 КиБ
(262144 байт UTF-8) при лимите `writeUtf` 512 КиБ. `catalogHash` — SHA-256 полного
каталога. При refresh клиент передаёт известный хэш; если он совпал, сервер шлёт
базовый снимок без каталога, а клиент использует уже собранные рецепты. Иначе
каталог передаётся полностью частями. Клиент применяет новый снимок атомарно после
получения всех частей с одним хэшем, сохраняя прежний полный снимок до этого.

## Важное ограничение исходников Industry

В архиве Industry Java-файлы `NativeCombatEvents` и `ReadyWeapons` были старее
принятой боевой логики. Две точные рабочие `.class` сохранены в
`modules/industry/preserved-combat`. Сборка накладывает их после reobfuscation.
Это сохраняет принятый вариант, но изменение этих двух классов требует отдельно
восстановить соответствующий исходный код и повторить боевые проверки.

## Совместная разработка

Правила работы — [CONTRIBUTING.md](CONTRIBUTING.md), границы модулей и сценарии
регрессии — [docs/ARCHITECTURE-RU.md](docs/ARCHITECTURE-RU.md).

В Git хранятся исходники, общие настройки и lock-файлы. Большие сторонние JAR,
стандартные ресурсы TaCZ, установщик Forge и старые ZIP исключены через
`.gitignore`; они присутствуют в полном комплекте проекта. Для нового клона
перенесите также эти папки из [полного комплекта](https://github.com/Seregaaa228/PistoletovMartin/releases/tag/frontier-team-0.4.0).

Репозиторий команды: [Seregaaa228/PistoletovMartin](https://github.com/Seregaaa228/PistoletovMartin).
Архив в Releases — исходный снимок передачи; актуальные изменения команды
хранятся в ветках Git. Контрольная сумма архива опубликована рядом с ним.

Полный комплект содержит клиентский профиль. Готовая выделенная серверная
сборка с отдельным установщиком этим переносом не объявляется.
