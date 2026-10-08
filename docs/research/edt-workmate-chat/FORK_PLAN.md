# План возможной доработки EDT-MCP

Статус: план, реализация не начата. Рабочий checkout форка создан: **https://github.com/Nicodimych/EDT-MCP**, remote `upstream` указывает на **https://github.com/DitriXNew/EDT-MCP**. Плагины не устанавливались. Java-код следует добавлять в этот EDT-MCP checkout, а не в архивный Go-проект feenlace/mcp-1c.

## Основание и версия

Для анализа использован чистый локальный snapshot `E:\projects\Автодействия\.tmp\edt-mcp-src`, commit `6d1853136226ccf50c2d68994f6bc94c57c1adb7`. Новый checkout `E:\projects\EDT-MCP` клонирован из форка на актуальной ветке `master`; это отдельная база для дальнейших изменений. Соответствие установленной сборке EDT-MCP 2.20.1 исходному snapshot не доказано. Перед реализацией выбрать базовый commit форка и повторить проверку сигнатур текущего Напарника.

В upstream указана лицензия AGPL-3.0-or-later. В пакет не копируются исходники целого стороннего проекта или бинарники 1С. При распространении форка сохранить upstream license/notice и отдельно проверить условия поставки зависимостей.

## Куда вносить изменения

Пути относительно upstream; Java prefix: `mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/`.

| Существующий участок | Назначение и предполагаемое изменение |
|---|---|
| tools/impl/AskWorkmateTool.java | Сохранить совместимость one-shot/native direct-tool/chat-panel режимов; не перегружать его всеми операциями stateful API |
| utils/WorkmateGateway.java | Переиспользовать получение сервисов и version compatibility; вынести native диалог в отдельный adapter, явно передавать ConversationSession и возвращать новую |
| utils/BackgroundJobs.java, BackgroundJobPolling.java, BackgroundJobRenderer.java | Переиспользовать длительные операции/ограниченное ожидание; отделить job от chat/turn; добавить частичный результат и отмену |
| tools/McpToolRegistry.java | Зарегистрировать новые workmate_chat_* tools, capabilities и схемы |
| bridge/IEdtMcpBridge.java, bridge/EdtMcpBridge.java | Существующая внутренняя диспетчеризация; менять контракт только при необходимости, не вводить обход tool policy |
| protocol/McpProtocolHandler.java, transport/McpHttpHandler.java, protocol/McpSessionRegistry.java | Не связывать lifecycle чата с HTTP session; проверить reconnect и совместимость протокола |
| Activator.java | Управление ресурсами адаптера, startup/disposal, отмена/завершение фоновых задач |
| guides/ask_workmate.md, docs/tools/ask_workmate.md | Обновить границы старого инструмента; отдельное руководство нового API |

Предлагаемые новые компоненты, ещё не существующие: WorkmateChatService, WorkmateChatStore, WorkmateTurnRunner, WorkmateEventJournal, WorkmateVersionAdapter, WorkmateToolPolicy. Имена не обязательны; важны разделение ответственности и проверяемые контракты.

## Последовательность

### 1. Контракт и минимальный диалог

Реализовать create/send/status/events, stable chatId, ConversationSession, один активный ход на чат, clientRequestId. Native adapter использует проверенный sendAsync; без обязательного открытия WebView. Установить явные project/skill/allowed tools и лимиты. Готовность: вопрос → получение факта → уточнение того же факта, с событиями и сохранённым курсором.

### 2. Вопросы и подтверждения — основной технический риск

Исследовать механизм native взаимодействий и границу с IChat/WebView. Получить реальные fixtures: choice, free text, approve, reject. Если карточки доступны только в UI-протоколе, согласовать поддерживаемый adapter с разработчиками Напарника или честно объявить ограниченную capability. Не выдавать простой send("да") за поддержку подтверждений.

До решения этого этапа продукт может называться текстовым stateful chat, но не полноценным управлением всеми действиями Напарника.

### 3. Инструменты и устойчивость

Сохранить native tool dispatch и серверный accepted/result цикл. Добавить реальную проверку разрешений перед локальным исполнением. Подключить безвредный тестовый custom IMcpTool через новый официальный для моста registry/SPI. Не включать произвольные команды Xelgo как готовый API: у них UI-контекст и побочные эффекты.

Получить повторяемый fixture отрицательного index; корректно собирать fragmented tool calls по ID/index и различать промежуточные/финальные frames. Никакой безусловной замены -1 на 0. Добавить checkpoint для лимита tool rounds и ошибок связи.

### 4. Сохранение, отмена, совместимость панели

Persist metadata/cursor/events и состояния вызовов. После restart выполнять reconcile; различать not_sent, sent, executing, result_received, acknowledged, execution_unknown. Проверить отмену HTTP/исполнителей, не только установку AtomicBoolean. Проверить UI head после пользовательского сообщения, конфликты revisions и потерю MCP session.

### 5. Внешние custom tools — необязательное расширение

Если действительно нужен Python/другой процесс как исполнитель: добавить register/request/result API с owner, schema, deadlines и дублированием ответов без повторного исполнения. До этого локального IMcpTool достаточно, чтобы доказать расширяемость.

## Сборка

В исследованном upstream сборка использует JDK 25, Maven 3.9+, Tycho 5. Плагин компилируется с release 17/BREE JavaSE-17; это не означает, что build с JDK 17 подходит для EDT 2026.2. CI запускает Maven из `mcp`:

```powershell
# После создания отдельного checkout и настройки JDK 25:
mvn -f mcp/pom.xml clean verify --batch-mode --no-transfer-progress
```

Точный CI использует `.github/scripts/mvn-retry.sh ... -T 1C`. В этом исследовании сборка не запускалась. Учесть разрешение p2 dependencies и целевую версию EDT. Для проверки UI нужен отдельный workspace/профиль установки; не подменять сразу работающий плагин пользователя.

## Уже имеющиеся тесты, которые стоит расширить

Корень: `mcp/tests/com.ditrix.edt.mcp.server.tests/src/com/ditrix/edt/mcp/server/`.

- tools/impl/AskWorkmateToolTest.java — совместимость схем и исходных режимов.
- utils/WorkmateGatewayContinuationTest.java — внутренние продолжения; добавить сохранение сессии между внешними вызовами.
- utils/WorkmateUnknownToolTest.java — неизвестные локально и серверные инструменты.
- utils/WorkmateVersionCompatTest.java — версии Напарника.
- utils/WorkmateChatSessionPublisherTest.java — публикация сессии панели; не считать её полной двусторонней синхронизацией.
- utils/WorkmateIdleTimeoutTest.java, WorkmateFinalityNoteTest.java — timeout/finality.
- utils/BackgroundJobsTest.java, BackgroundJobsRemainingBudgetTest.java — lifecycle и бюджет ожидания.
- bridge/EdtMcpBridgeTest.java, protocol/McpSessionRegistryTest.java — bridge и transport.
- tools/McpToolRegistryTest.java, McpToolRegistryVisibilityTest.java — регистрация/доступность новых tools.

## Матрица приёмки

| Проверка | Признак успеха |
|---|---|
| Два хода | Второй запрос без подсказки использует факт из первого, тот же conversationId, новый reply UUID |
| Фактический инструмент | Зафиксированы call ID, вызов обработчика, результат и дальнейший ответ |
| Карточка и подтверждение | Есть interactionId; approve и reject дают различные ожидаемые результаты; ничего не подтверждается автоматически |
| Custom tool | Случайная локальная proof неизвестна модели и точно возвращена после одного вызова |
| Серверные tools | Search_ITS/Fetch_ITS/Task и точный mcp__web__fetch дают реальные результаты; отсутствие отдельного Search отражается честно |
| Повтор send | Один clientRequestId не даёт второго хода/изменения |
| Отмена | После терминального cancelled нет новых локальных исполнений и незавершённых сетевых задач адаптера |
| Restart/reconnect | MCP ID обновлён, chat восстановлен или явно needs_reconciliation; старый reply не используется молча |
| UI и MCP | Изменение в панели обнаружено; конфликт или контролируемый выбор ветки |
| Ошибки потока | Null role, отрицательный index, несколько вызовов и fragmented arguments не смешиваются и не блокируют UI |

Главные неопределённости до оценки сроков: structured interactions, серверный контракт resumability и стабильность внутреннего API между версиями. На основании текущих опытов можно начинать текстовый MVP; оценивать полный продукт как уже доказанную простую обёртку нельзя.
