# Интерфейс Industry на LDLib

Minecraft 1.20.1, Forge 47.4.0, Java 17. Используется закреплённый
`ldlib-forge-1.20.1-1.0.52.a.jar`; SHA256 и источник находятся в
`integration-libraries.lock.json`. LDLib2 не используется.

## Где редактировать макеты

Исходные макеты хранятся в Git как Java, без внешней папки редактора:

- `modules/industry/src/main/java/dev/frontier/industry/client/FactoryScreen.java`:
  `rebuild()` задаёт страницы завода, `refreshProductRows()` — список изделий.
  Логический размер — 356×268. Координаты существующих inventory slots заданы
  в `IndustryMenus.FactoryMenu`; изменять их для оформления не требуется.
- `modules/industry/src/main/java/dev/frontier/industry/client/OperationsScreen.java`:
  `rebuild()` и методы разделов задают КПК 560×342. В «Армии» сохранены
  подразделы бойцов, ПВО и техники/БПЛА.
- `client/ui/IndustryWidgets.java`: общие LDLib кнопки, текст, поиск,
  строки, предметные иконки и индикаторы. Длинная подпись кнопки прокручивается;
  у обрезанной строки есть полная подсказка. Не заменяйте полный текст в данных
  сокращённой строкой.
- `client/ui/UiViewport.java`: единое уменьшение макета вокруг центра экрана.
  Рендер, клик, отпускание, перетаскивание, движение мыши и колесо используют
  взаимно обратные координаты. Это сохраняет доступ к слотам и кнопкам на Auto.

`LdlibWidgetHost` содержит настоящее дерево `WidgetGroup`, `ButtonWidget`,
`TextFieldWidget`, `ImageWidget` и `ProgressWidget`. Его локальный
`ModularUIGuiContainer` нужен LDLib для фокуса и подсказок. Он не становится
`player.containerMenu`, не имеет `SlotWidget` и не отправляет LDLib actions.
Все виджеты помечены `setClientSideWidget()`; сервер получает прежние пакеты.

Для новой кнопки используйте `IndustryWidgets.ActionButton`, а callback
запускайте через `widgets.afterInput(...)`: перестройка дерева непосредственно
из прохода LDLib по его дочерним элементам недопустима. `enabled(false, reason)`
оставляет подсказку с причиной отказа. Для нового текстового поля присвойте
стабильный `id`, как `product_search`; кнопки имеют `id`, равный исходной подписи.
`host.find(id)` предназначен для точного поиска виджета, в том числе тестами.

LDLib visual editor может служить инструментом эскиза. Экспорт `.ui` сам по
себе не подключает серверные действия: текущие runtime-макеты определены
перечисленными Java-файлами. Если команда переводит страницу на `.ui`, храните
экспорт и редактируемые ресурсы в Git, используйте официальную LDLib1.x загрузку
`UIProject.loadUIFromFile` и явно привяжите идентификаторы к прежним callbacks.
Не загружайте макеты из личного `.minecraft`; работайте в тестовом профиле.

## Серверное состояние и действия

`FactoryMenu`, ID меню, 24 слота объекта и 36 слотов игрока сохраняются.
`IndustryNet` protocol3 IDs0/1 и `OperationsNet` protocol2 IDs0/1 не меняются.
Сервер повторно проверяет владельца, измерение, дистанцию, открытое меню,
допустимые действия, частоту запросов, ресурсы и исследования.

Кнопка «В очередь» добавляет новый заказ. Она не означает немедленное списание
всех материалов. Каждая партия оплачивается сервером перед началом работы;
`reserved` показывает сохранённую оплаченную партию. Страница «Очередь»
отображает все заказы, включая скрытые в новом каталоге. Отмена первого заказа
использует прежнее серверное действие `cancel` и возврат резерва. Не удаляйте
заказ на клиенте и не пытайтесь повторно оплатить уже зарезервированную партию.

Нехватка питания, материалов или рабочих показана как причина ожидания.
Она не вводит нового запрета ставить заказ в очередь. Цена берётся из
серверного каталога, состояние — из snapshot, результат действия — из
`actionMessage`. Клиент не рассчитывает энергосеть и не завершает ремонт.

Состояние энергии использует `energy`, `energyCapacity`, `powered`,
`networkConnected`, `networkStatus`. Секция `repairState` содержит
`requested`, `progressTicks`, `totalTicks`, `workers`, `kitReserved`,
`requiredRole`, `reason`. Кнопка отмены ремонта посылает `repair_cancel`,
отдельно от отмены производственного заказа. Вкладка «Ремонт» открывает тот же
объект через `open_site`; сервер сохраняет проверку доступа рядом с объектом.

При каждом открытии завода старый snapshot сбрасывается и запрашивается новый.
При выходе очищаются factory/PDA snapshots. Выбор изделия и страница — локальное
состояние представления; инвентарь, оплаченная очередь и ремонт хранятся сервером.
Новых клавиш B/R нет. «Справочник» открывает `IndustryGuide.open()`.

## Проверка

Лёгкие проверки не требуют запуска Minecraft:

```sh
javac -d /tmp/pis2-ui-test \
  modules/industry/src/main/java/dev/frontier/industry/client/ui/UiViewport.java \
  modules/industry/src/main/java/dev/frontier/industry/client/ui/UiPage.java \
  modules/industry/src/main/java/dev/frontier/industry/client/FactoryUiState.java \
  modules/industry/src/test/java/dev/frontier/industry/client/ui/UiViewportTest.java \
  modules/industry/src/test/java/dev/frontier/industry/client/ui/UiPageTest.java \
  modules/industry/src/test/java/dev/frontier/industry/client/FactoryUiStateTest.java
java -cp /tmp/pis2-ui-test dev.frontier.industry.client.ui.UiViewportTest
java -cp /tmp/pis2-ui-test dev.frontier.industry.client.ui.UiPageTest
java -cp /tmp/pis2-ui-test dev.frontier.industry.client.FactoryUiStateTest
```

Тесты проверяют края завода/КПК и обратные координаты при масштабированных
разрешениях 960×540, 640×360, 427×240 и 320×240; последний слот и нижнее действие;
пустой/сократившийся список, последнюю страницу, сохранение и сброс выбора.
Они не заменяют визуальный игровой прогон и проверку пакетов.

В свежем мире проверить GUI Scale2, Scale3 и Auto: производство, полный состав
стоимости, поиск с буквами E/русским текстом, склад и Shift-клик, очередь,
неизученное изделие, отсутствие энергии/ресурсов/рабочих, серверное подтверждение,
ремонт и справочник. Затем открыть объект повторно, переподключиться и проверить
ту же оплаченную партию. Второй клиент отправляет чужой/неверный запрос: сервер
должен отказать без списания и без раскрытия чужой очереди.

## Контракт автоматического игрового прогона

Только test-only `src/integrationTest/.../Pis1SmokeClient.java`, вручную
зарегистрированный через `init()`, использует `frontier.pis1Smoke=true`.
`frontier.pis1RunId` должен совпадать с `test-profile.json`, где
`worldsCopied=false`. Свежий мир называется `Pis1-Test-<runId>`. Существующий
мир открывается только при явном `frontier.pis1Restart=true` и совпадении marker.

Клиент выставляет `frontier.pis1ClientReady=true`. Серверный fixture открывает
реальное меню завода и выставляет `frontier.pis1UiStage=<kind>@<scale>`:

| kind | Действие |
|---|---|
| `factory` | Снимок открытого сервером завода |
| `factory-search` | Ввод E и поиск реального изделия |
| `factory-order` | Поиск и клик «В очередь» |
| `factory-storage` | Клик «Склад» |
| `factory-management` | Клик «Управление» |
| `country`, `army`, `production`, `repair` | Открыть КПК и нажать раздел |
| `guide` | Открыть КПК и нажать «Справочник» |

`scale` принимает `2`, `3`, `auto`. Рецепт задаётся `frontier.pis1RecipeId`
(по умолчанию `minecraft:iron_sword`). `frontier.pis1SearchGuard` показывает
результат ввода E, `frontier.pis1OrderClicked` — факт клика; принятие и списание
проверяет серверный fixture. После записи PNG клиент подтверждает точный stage
в `frontier.pis1UiDone`. Ошибка записывается в `frontier.pis1UiFailure`.
PNG: `screenshots/pis1-<kind>-<scale>.png`. Завершение: fixture устанавливает
`frontier.pis1Stop=true`. Новые прогоны имеют новый runId и отдельный профиль.

На момент добавления инструкции математические проверки выполнены. Реальные
скриншоты и результаты серверного игрового прогона должны прикладываться
отдельно с runId и SHA256; этот документ не объявляет их выполненными.

Источники: [LDLib UI](https://low-drag-mc.github.io/LowDragMC-Doc/en/ldlib/ui/),
[официальный выпуск1.0.52.a](https://www.curseforge.com/minecraft/mc-mods/ldlib/files/8830518).
Используемые сигнатуры дополнительно сверены `javap` с закреплённым JAR.

## Готовый текст и подсказки

`IndustryWidgets` использует `LiteralTextTexture`: серверные подписи и названия изделий являются готовым текстом. Обычный LDLib1.0.52.a `TextTexture.updateText` повторно вызывает локализацию с форматированием и превращает `100%` в `Format error`. Для таких строк используйте имеющуюся обёртку, сохраняя перенос/ROLL; не исправляйте серверный текст экранированием процентов.

`TooltipBounds` переносит длинную подсказку и размещает её внутри окна, чтобы панель JEI не рисовала предметы поверх текста. При изменении ширины окна передавайте его актуальные логические границы в `widgets.tooltip`.
