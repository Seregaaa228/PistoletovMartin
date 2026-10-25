# Объединённая проверка Xaero и PIS-9/PIS-10 — 25.10.2026

По просьбе пользователя объединены работа чата «Установить фикс Xaero’s Map»
и PIS-9/PIS-10. Исходный Xaero commit `dc913b0` и checkpoint `3a544a5`
объединены с принятой `main` `3e7928e6d211a474c488f5ed8cba376c6021b31b`.
Это новая проверка объединённого состояния; прежние отчёты ниже остаются
свидетельствами только своих исходных SHA-256.

## Разрешение объединения

- В Industry сохранены принятые FE, ремонт, LDLib интерфейс и снимки оплаченных рецептов из main. ConstructionAccess продолжает запрещать строительство пилоту/пассажиру.
- КПК сохраняет защиту от позднего открытия поверх native экрана, учитывает фокус LDLib text field и очищает сетевой буфер при смене соединения.
- Оба source set — integrationTest и combatAcceptance — сохранены отдельно. Production JAR не содержит ни одного acceptance/integrationtests класса.
- Xaero helper нажимает настоящую LDLib кнопку через vanilla MouseHandler callbacks с учётом UiViewport. Прямой Screen event не задавал activeButton, который читает pinned LDLib ClickData; игровой callback не подменялся.
- Java17 / Minecraft1.20.1 / Forge47.4.0, native ID, данные и оба точных combat .class сохранены. Шесть дополнительных библиотек взяты из уже принятой main с её неизменённым integration lock; исходный профиль27модов не заменялся.

## Проверки

- `python3 tools/verify_project.py`: PASS,27pinned модов/3378profile files/6модулей/2preserved class, CRC архивов.
- `python3 tools/run_contract_checks.py`: все8 standalone Java17 наборов прошли, включая каталог, атомарную сборку частей, FE и UI bounds.
- `python3 -m unittest discover -s tools/tests -v`:8/8PASS.
- `python3 tools/check_integration_assets.py`:24book/model links PASS.
- Offline `:industry:build`:BUILD SUCCESSFUL,49tasks,30секунд.
- Offline `build :industry:combatAcceptanceJar :nations:mapAcceptanceJar`:BUILD SUCCESSFUL,86tasks,41секунда.
- После изменения только тестового ввода отдельно пересобран и успешно запущен `:nations:mapAcceptanceJar`.
- `git diff --check origin/main`:PASS. Лицензии третьих сторон из main не переформатировались.

## Новая игровая проверка

Бой: `run-combat-aca544f4`, server runId `2d1ba5f4-d5cc-4725-a718-aab83f62ddb9`.
Выделенный сервер,33production мода и отдельный helper, два настоящих клиента,
новый мир, дополнительные80мс в каждом направлении. **1327/1327** server
assertions и по **14/14** у обоих клиентов. Полные сценарии описаны в
[PIS-COMBAT-2026-10-25.md](PIS-COMBAT-2026-10-25.md); здесь они повторены
с объединёнными Industry/Nations и библиотеками main. Новый combat JSON и
запись экрана находятся в ignored `vendor/combat-evidence/run-combat-aca544f4-*`.

Xaero/КПК: выделенный сервер `run-combined-map-server-0c7a3548`, новый мир `world-verified-871c0a`,
клиенты `run-combined-map-clients-9b998b-owner` / `run-combined-map-clients-9b998b-guest`.
Владелец **28/28**, гость **7/7**,
после полного перезапуска и повторного входа **13/13**.
Проверены реальная кнопка КПК→Xaero, read-only M, вогнутый поблочный контур,
права/отказ без КПК, флаг и Tab, синхронизация второго клиента, сброс,
большой Unicode-снимок КПК через production transport и сохранение после
перезапуска. Обе штатные остановки сервера завершились кодом0. JSON,
markers, логи и screenshots находятся в ignored `vendor/combined-evidence`.

Все миры созданы заново внутри проекта; личный .minecraft, аккаунты,
пользовательские сохранения и реальные серверы не использовались.
В Git входят исходники, тестовые инструменты и этот отчёт. Миры, логи,
кэши, IDE bin и локальное состояние инструментов исключены.

## SHA-256 проверенных production JAR

| JAR | SHA-256 |
|---|---|
| `frontier-arcade-style-1.1.0.jar` | `8e07e5c34582b6e65bed3147fdd1cc9eb4ecdea1cc998b0de14a723f40f0642c` |
| `frontier-arcade-vehicles-1.7.0.jar` | `59e3182b029d04adb4dcccd2cb52751f3c1b9e388ea625e465e1b1f22e2c8d6a` |
| `frontier-flamingo-launch-fix-1.3.0.jar` | `40828a577990477cf0ecb3ff1abb5528db23c9f05a65cba9a868fb8ecbf598a5` |
| `frontier-industry-1.20.1-0.4.0.jar` | `4b4a05fc278e6502fbc167ad030a4b217764ef81e2a1da538651506282b0ab97` |
| `frontier-light-aviation-1.1.0.jar` | `55565c3883c4b1255017438fe22d4d3370d6fe9238b6736e174d84bb6eacfdf7` |
| `frontier-nations-1.20.1-0.1.2-alpha.jar` | `e855b7250fe2d6a13ed22649b40a6856096c21607668c2e068ff7ba247ded84f` |

Два preserved-combat class в Industry проверены побайтно по исходному overlay.
Нагрузочный тест большого онлайна и полная матрица всех ПВО/БЧ не выполнялись;
это ограничение не заменяется компиляцией или локальными проверками.
