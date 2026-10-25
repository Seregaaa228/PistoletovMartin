# MC Pilot: локальный игровой тестер

Установлен MC Pilot CLI **0.20.2** и его готовый клиентский мод для
Minecraft **1.20.1 / Forge 47.x / Java 17**. Версия самого mod `mct` — 0.10.1;
это отдельная нумерация, не версия CLI. Источник и SHA256 закреплены в
`test-tools.lock.json`, MIT-лицензия сохранена в `licenses/test-tools/mc-pilot`.

Инструмент находится в `vendor/mc-pilot`, добавлен только в отдельные
тестовые клиенты. Он не входит в принятые 27 модов, шесть выпускных модулей
или обычный интеграционный профиль. `test-profile.json` явно перечисляет его
в `testTools`; проверяющий скрипт разрешает только точный закреплённый SHA256.

## Установка CLI

Из корня проекта, с Node.js 20.18.1 или новее (требование зависимости Undici):

```sh
npm install --prefix vendor/mc-pilot/cli --ignore-scripts --save-exact \
  @kzheart_/mc-pilot@0.20.2 --cache "$PWD/vendor/mc-pilot/npm-cache" \
  --no-audit --no-fund
python3 tools/mct.py --cli-version
python3 tools/mct.py schema
```

Обёртка `tools/mct.py` запускает установленный CLI напрямую, задаёт
`MCT_HOME` и `MCT_CACHE_DIR` внутри проекта. Глобальная установка, личный
`.minecraft`, автоматическая установка skills в домашнюю папку и повторное
скачивание Minecraft не нужны. Для GUI-команд нужен запущенный настоящий клиент.
Если нужная версия Node не стоит в `PATH`, задайте `MCT_NODE=/absolute/path/to/node`.
Обёртка также проверена с уже установленным Node 24.22.1; системный Node не обновлялся.

## Подключение существующих тестовых клиентов

Клиентский JAR берётся из точного release URL в `test-tools.lock.json`;
перед копированием в `mods` отдельного тестового профиля сверяется SHA256.
В manifest этого профиля добавляются точные имя и SHA256 в `mods` и `testTools`.
На выделенный сервер MC Pilot не устанавливается.

Наш `tools/run_test_client.py` сохраняется. Дополнительные переменные окружения:

```sh
MCT_HOME="$PWD/vendor/mc-pilot/home"
MCT_CLIENT_NAME=owner
MCT_CLIENT_WS_PORT=25560
MCT_CLIENT_RESOURCE_PACK_POLICY=prompt
```

У второго клиента другое имя `foreign` и порт 25561. Сам мод слушает
WebSocket только на loopback. CLI обнаруживает уже запущенные клиенты через
`vendor/mc-pilot/home/state/clients.json`: `defaultClient` и объект `clients`,
где каждая запись содержит реальные `pid`, `name`, `wsPort`, `startedAt`,
`logPath`, `instanceDir`. PID берётся у процесса нашего launcher с точным
`frontier.pis1RunId`; чужие процессы не регистрируются.

Примеры чтения состояния:

```sh
python3 tools/mct.py --client owner status all
python3 tools/mct.py --client foreign position get
python3 tools/mct.py --client owner gui info
python3 tools/mct.py --client owner gui screenshot --output /absolute/project/test/screenshots/owner.png
```

Для доступных действий использовать `schema`, а не угадывать флаги.
Скриншоты берутся из framebuffer игры. Внутренние LDLib widgets не превращаются
автоматически в обычные Minecraft buttons; их вид проверяется по кадрам.
Процессы запускает наш launcher: закрывать их через MC Pilot `down/stop` не нужно.

CLI status и снимки проверены на двух клиентах. Обнаруженное падение LWJGL/Flywheel
было вызвано перезаписью общей native-библиотеки при подготовке другого клиента.
Launcher теперь сохраняет неизменные файлы и публикует замену атомарно, сохраняя
отображённый в памяти inode работающего клиента. Итоги — в [отчёте PIS-1](PIS-1-2026-10-25.md).

## Выбор инструмента

[MC Pilot](https://github.com/kzheart/mc-pilot/releases/tag/v0.20.2) работает
внутри настоящего Forge-клиента и даёт состояние, ввод и кадры игры.
[yuniko minecraft-mcp-server](https://github.com/yuniko-software/minecraft-mcp-server)
использует Mineflayer: полезен для обычных действий бота, но не исполняет Java
рендереры LDLib, Jade и GeckoLib. Его установка не заменяет визуальную приёмку
этой сборки. Подключение такого бота к нашему Forge-серверу не заявляется проверенным.
