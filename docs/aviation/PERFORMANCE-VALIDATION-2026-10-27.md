# Проверка второго этапа оптимизации авиации

База: commit `c6c21e0`, Aviation SHA256
`e8107c44e5a5724fa9cce90fc09e00205db81a30dd229671546983a227630613`.
Итоговый JAR: `build/team-mods/frontier-light-aviation-1.1.0.jar`, SHA256
`be2aa9699a4a50e6e5614a2eeaed0b758e50f3c37d132715061ab6d196938b6a`.
Minecraft 1.20.1 / Forge 47.4.0 / Java 17; зависимости, native ID,
схема NBT, частоты команд/снимков и формат flight4 сохранены.

## Что изменилось

- Канонический NBT v1 обновляется на месте только при изменении значения.
  Старые/повреждённые данные канонизируются как прежде; неизвестные версии
  не переписываются. 10000 последовательных сохранений сравниваются
  побайтно с прежним writer, включая signed zero, NaN, infinity и лишние поля.
- ACK удаляет подтверждённый префикс упорядоченной клиентской истории.
  Attitude переиспользует immutable состояние при точном совпадении углов
  после прежней нормализации. Differential replay проверяет состояние,
  output, capture, коррекции, clamps, эпохи и очереди 0/4/16/80.
- Сетевой bridge использует общий чистый codec прямо через FriendlyByteBuf.
  VarInt длина и тело сохранены. 405 bridge assertions проверяют побайтную
  совместимость, heap/direct, ёмкости 0/1/256, длины 127/128, UTF-8, null,
  смещения, обрывы, переполнение, некорректные значения и хвост пакета.
  Чистый codec имеет отдельные 139 assertions без Minecraft/Netty imports.

Кеш геометрии и прочие опробованные варианты SAT отклонены. Геометрия,
сбор препятствий, лимиты столкновений и повреждения остались прежними.
Совместное ревью выполнено агентами Opus 5.5, Sonnet 5.5 и Fable 5.1.

## Измерения отдельных участков

Это Java microbenchmarks, не FPS/TPS и не input-to-photon. NBT и prediction
сравнивались с отдельно скомпилированными исходниками базы; codec сравнивает
прежний byte-array transport с новым bridge при одинаковых сообщениях.
Результат декодирования/состояния удерживается от удаления JIT, Netty buffers
освобождаются. Время не является assertion в обычном check.

| Участок | Прежнее выделение памяти | Новое выделение памяти |
| --- | ---: | ---: |
| NBT, 16 машин, неизменное состояние | 272 B/save | 0 B/save |
| NBT, 16 машин, меняющийся throttle | 271,84 B/save | 15,84 B/save |
| Prediction fighter, очередь около 16 | 1836–1839 B/tick | 1513 B/tick |
| Codec command, группы 8/16 пакетов | 1328 B/packet | 680 B/packet |
| Codec snapshot, группы 8/16 пакетов | 3200 B/packet | 1904 B/packet |

NBT: 7 samples после прогрева, повтор до/после в обратном порядке.
Для 16 машин median 60,3–69,2 → 18,9–19,2 ns/save в неизменном состоянии;
59,9–64,7 → 27,4–28,4 ns/save при меняющемся throttle.
Prediction: 5 samples × 10000 ticks после 10000 ticks прогрева, два повтора;
CPU эффект смешанный и не объявляется улучшением. Первый повтор пересекался
с baseline Minecraft и не подходит для заключения о CPU.
Codec: 5 samples × 50000 iterations после 20000 iterations прогрева.
В финальном запуске группы 8/16: command 489–493 → 264–267 ns/packet,
snapshot 1557–1603 → 479–528 ns/packet. Это один component run;
из него не выводится общий выигрыш игры или гарантированная задержка.

Явные measurement tasks:

```sh
GRADLE_USER_HOME="$PWD/vendor/gradle-cache" python3 tools/build.py --offline :aviation:flightPersistenceBenchmark
GRADLE_USER_HOME="$PWD/vendor/gradle-cache" python3 tools/build.py --offline :aviation:flightPredictionBenchmark
GRADLE_USER_HOME="$PWD/vendor/gradle-cache" python3 tools/build.py --offline :aviation:flightCodecBenchmark
```

Сырые локальные журналы находятся в игнорируемом
`vendor/flight-performance-20261027/`: `persistence-*-repeat*.log`,
`prediction-{baseline,winner}-{0,1}.log`, `codec-final-both.log`.

## Реальный запуск и границы

До изменения выполнен настоящий baseline запуск в свежем плоском мире,
профиль `before-980acd1c`, один пилот и 1/8/16 загруженных соседних F-16.
После 10 секунд прогрева снимались 20 секунд каждого сценария:

| Загружено F-16 | Mean wholeServerTick | Mean frameInterval |
| --- | ---: | ---: |
| 1 | 4,052 ms | 19,800 ms |
| 8 | 6,415 ms | 22,878 ms |
| 16 | 8,338 ms | 24,542 ms |

Это весь интегрированный сервер и клиент под Xvfb, ограничение 60 FPS.
Последующий обычный взлёт прошёл; финальная высота самолёта −14,916
против поверхности плоского мира около −60. Baseline не доказывает поведение
итогового JAR. Сравнимый игровой after-прогон итоговой сборки не выполнен:
по просьбе пользователя запущен основной профиль для ручной проверки.
Не запускались dedicated server, два клиента, RTT 160/300, TPS 15/10,
reload/restart и повторная игровая проверка оружия/камеры/шасси этой сборки.
Пользовательский основной профиль не использовался как автоматический QA.
Общий выигрыш FPS/TPS и input latency пока не установлен.

## Фактически выполненные проверки

Все команды ниже завершились с exit 0:

```sh
python3 tools/verify_project.py
python3 tools/run_contract_checks.py
python3 -m unittest discover -s tools/tests -v
python3 tools/check_integration_assets.py
GRADLE_USER_HOME="$PWD/vendor/gradle-cache" python3 tools/build.py --offline :industry:build build :aviation:flightPerformanceTestJar
GRADLE_USER_HOME="$PWD/vendor/gradle-cache" python3 tools/build.py --offline build
git diff --check
```

Профиль: 27 pinned mods / 3378 files / 6 modules / 2 preserved combat classes.
Standalone: 8 suites; Python: 8 tests; ресурсы: 24 entries/model links.
Первая полная сборка: 125 tasks, 1m03s. Повторная после tiny-buffer fix:
125 tasks, 55s, включая 405 bridge assertions и все flight checks.
Root/module JAR совпадают побайтно, ZIP CRC проверен, helper не попал в release.

Итоговый JAR установлен в основной профиль по прямому запросу пользователя.
Прежний сохранён в
`/home/chpoka/.minecraft/frontier-backups/flight-performance-20261027T162936Z/`.
Игра запущена с закреплённым runtime и выбранным локальным аккаунтом;
процесс и окно Forge 1.20.1 подтверждены. Миры и настройки не редактировались.
