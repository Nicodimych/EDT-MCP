# Технические выводы

Статус: результаты исследования 08.10.2026. Проверенная версия Напарника: `1.0.8.v202609241142`. Термин «внутренний API» означает Java-классы установленного плагина, а не документированный внешний REST API 1С.

## 1. Три разных сессии

| Сессия | Идентификатор/объект | Назначение и время жизни |
|---|---|---|
| MCP transport | HTTP `Mcp-Session-Id` | Связь внешнего клиента с EDT-MCP; после перезапуска нужно initialize |
| JShell | `repl_session_id`, использовался alias `edt-mcp` | Хранение Java-переменных внутри процесса EDT; теряется при перезапуске |
| Диалог Напарника | `ConversationSession` | `conversationId` и `replyToMessageUuid`; облачная беседа может быть видна в панели |

Нельзя использовать jobId, repl_session_id или MCP session ID вместо идентификатора беседы. После каждого хода необходимо сохранять **возвращённую новую** ConversationSession: conversationId обычно тот же, replyToMessageUuid изменяется.

`forceNewConversation=false` с корректной сессией продолжает её. Продолжение по старому replyToMessageUuid может дать ветку; автоматическое обнаружение изменений из панели пока не реализовано и не проверено.

## 2. Внешний вход

Проверенная точка входа: `POST http://127.0.0.1:8765/mcp`, MCP `2025-11-25`.

Для современной session-based ветки транспорта при initialize передавался заголовок `MCP-Protocol-Version: 2025-11-25`. Ответ содержит `Mcp-Session-Id`; далее он возвращается в заголовке запросов. В контрольном восстановлении initialize без version header не выдал новый session header, и сохранённый старый ID продолжал давать 404. Клиент должен явно проверять получение сессии, а не оставлять старое значение.

Ответ может быть JSON либо SSE (`data: {...}`). Успешный HTTP-ответ не равен успешному результату tool: нужно проверять JSON-RPC error, MCP isError, статус background job, compilation_errors/runtime_errors JShell.

Вызов Java выполнялся так:

```json
{
  "name": "ask_workmate",
  "arguments": {
    "workmateTool": "JShell",
    "workmateArgs": "{\"scope\":\"eclipse\",\"repl_session_id\":\"edt-mcp\",\"manual_ids\":[\"eclipse_overview\"],\"request_description\":\"Диагностика\",\"response_description\":\"Результат\",\"code\":\"System.out.println(1+1);\"}",
    "waitSeconds": 5,
    "timeoutSeconds": 30
  }
}
```

workmateArgs — **JSON-строка**, не вложенный объект. В проверенной версии требуются request_description и response_description. Доступен manual `eclipse_overview`; ранее предполагаемый `jshell_edt_canonical_imports` в этой установке отсутствовал.

## 3. Native диалог

Объект получался через:

```java
var facade = com.e1c.edt.ai.ui.BaseActivator.getInstance(
    com.e1c.edt.ai.ConversationFacade.class);
```

Нужные типы и методы:

```java
// com.e1c.edt.ai.assistent
new SendUserMessageRequest(
    IProject project, String message, ConversationSession session,
    boolean forceNewConversation, String skillName, Boolean chat,
    Integer maxToolRounds, Set<String> allowedTools);

// com.e1c.edt.ai
facade.sendAsync(request, cancellationToken, progressListener);

// результат CompletableFuture<SendMessageResult>
result.getText();
result.getSession();
result.getAssistantMessageCount();

// ConversationSession
session.getConversationId();
session.getReplyToMessageUuid();
new ConversationSession(conversationId, replyToMessageUuid);
```

Существуют и другие перегрузки конструктора, включая SkillCompletionPolicy; см. сохранённые сигнатуры. Для простого чата в опыте использовался восьмиаргументный конструктор без completion policy.

Успешно проверено: `skillName="custom"`, `chat=true`, maxToolRounds=3 для короткой задачи. `raw` с chat=true прошёл контроль контекста, но не использовался для самостоятельного исследования конфигурации. Из единичных успехов нельзя выводить, что chat=true устраняет все причины зависаний.

**Не смешивать** внутренний chat=true и внешний ask_workmate mode="chat". Последний вызывает путь панели (`IChat.askQuestion`) и не возвращает через MCP полноценный результат беседы.

## 4. Потоки и ожидание

В данном scope JShell исполняется на SWT UI thread. Нельзя ждать завершения сетевого запроса через blocking get/join на этом потоке. Запуск:

```java
var future = CompletableFuture
    .supplyAsync(() -> facade.sendAsync(request, () -> cancel.get(), listener))
    .thenCompose(f -> f);
```

Следующим MCP-вызовом проверяется isDone. Только после true можно неблокирующе получить завершённый результат/ошибку. Отмена AtomicBoolean — запрос cooperative cancellation, не доказательство фактического прекращения HTTP/серверной задачи.

Для реализации плагина предпочтительнее управляемый executor с жизненным циклом, таймаутами и освобождением ресурсов, а не бесконтрольное создание задач в common pool. Это рекомендация, не текущая реализация.

## 5. Три семейства инструментов

### Родные локальные инструменты Напарника

`IMcpTools.getSpecifications()` вернул 26 имён:

```text
GetCommandCategories LocalChanges JShellManual Glob GetCommands
1C_GetObject Delete JShellReflection JShellSession NavigationHistory
1C_EditMetadata JGit SetMarkers Write SearchText JShell Read Edit Svg
LocalHistory GetProjects GetVisualContext Execute 1C_Find DeleteMarkers GetMarkers
```

Исполнители JShell/Read/GetProjects — `com.e1c.edt.ai.ui.common`; 1C_GetObject — `com.e1c.edt.ai.context`.

ConversationFacade.getToolsDefinitions получает спецификации, фильтрует allowedTools, преобразует в name/description/parameters, добавляет extraTools. В этой установке extraTools был пуст. Неизвестное имя в allowedTools приводит к `IllegalArgumentException: Unknown tools in allowed-tools`.

allowedTools=null означает отсутствие такого фильтра; пустой Set убирает локальные описания, но не доказывает отсутствие серверных инструментов. **Это фильтр объявлений, не полноценная ACL:** McpTools.callTools диспетчеризует по полному локальному реестру.

### Инструменты EDT-MCP

`codexNativeBridge` было именем нашей переменной. Реальный класс:

```text
com.ditrix.edt.mcp.server.bridge.EdtMcpBridge
OSGi bundle: com.ditrix.edt.mcp.server, version 2.20.1
```

Сервис зарегистрирован с `(edt.mcp.bridge=v1)` под IEdtMcpBridge, BiFunction и Supplier. Получение через JDK-интерфейс не требует импорта пакета моста:

```java
var ctx = FrameworkUtil.getBundle(Platform.class).getBundleContext();
var refs = ctx.getServiceReferences(BiFunction.class, "(edt.mcp.bridge=v1)");
var bridge = (BiFunction<String,String,String>) ctx.getService(refs.iterator().next());
bridge.apply("get_configuration_properties", "{\"projectName\":\"ПроектТест\"}");
```

apply → callTool → JSON-RPC tools/call → McpProtocolHandler.processRequest → инструмент EDT-MCP → API EDT. Этот участок проходит **внутри процесса, без HTTP**, но использует dispatch и форматирование EDT-MCP. Неизвестное имя возвращает `-32601 Tool not found`.

Напарнику в нашем тесте объявлялся только родной JShell. get_configuration_properties был в тексте инструкции/Java-кода, а не отдельной спецификацией родного инструмента.

### Серверные инструменты

В McpTools имеется SERVER_TOOLS с websearch/webfetch/task/todowrite, но это не полный реальный каталог. В потоке наблюдались также TaskResult и mcp__knowledge-hub__Search_ITS/Fetch_ITS. Пользователь увидел mcp__web__fetch в панели.

Если локальная map не содержит имя, McpTools возвращает его в unknownCalls. Conversations.createToolRequest преобразует такие вызовы в `role=tool`, элементы с исходным tool_call_id и `status=accepted`. Исполненного локального результата при этом нет. Для полностью вымышленного имени дальнейшее поведение сервера не проверено.

В живом опыте сервер после этих подтверждений вернул реальные результаты поиска, чтения ИТС и подзадачи. Поэтому будущий адаптер должен сохранять штатный путь неизвестных локально вызовов, но не считать accepted подтверждением успешного исполнения.

## 6. Свой инструмент

Нужны обе части:

1. `IMcpTool.getSpecification()` — описание и JSON-схема.
2. `IMcpTool.call(McpToolCall, ICancellationToken)` — локальный обработчик.

Создан отдельный McpTools со Set<IMcpTool> из родных инструментов и CodexHarmlessProbe. Он передан отдельным экземплярам Conversations и ConversationFacade. Исходный singleton не изменён: 26 инструментов в нём, 27 в тестовом экземпляре.

Фактический путь: cloud tool_call → локальный McpTools → наш call → ToolCallMessage с совпадающим tool_call_id → сервер → ответ модели. Отдельный callback URL не регистрировался.

Схема данных probe: input `{label:string}`, output `{label,proof,call_number,xelgo_version}`. Уникальная proof создавалась локально и не присутствовала в запросе модели. Исполнитель вызван один раз, модель вернула точную proof. Пример реализации включён в examples.

Добавление в extraTools одного имени не добавляет обработчик. Для внешнего исполнителя за пределами JVM нужен отдельный протокол tool-request/tool-result и ожидание коррелированного ответа; это только предложение.

Штатный MCPToolsModule использует Guice Multibinder<IMcpTool>. Наличие универсальной поддерживаемой публичной extension point для стороннего плагина не доказано. Эксперимент с отдельным dispatcher нельзя выдавать за готовую регистрацию в обычной панели.

## 7. Xelgo

Установлен bundle `ru.xelgo.edt.contextlinks.ui`, `1.1.22.v202608042058`. В plugin.xml есть org.eclipse.ui.commands и handlers:

```text
ru.xelgo.edt.contextlinks.ui.commands.configureContextLinks
ru.xelgo.edt.contextlinks.ui.commands.generateQueryResultComment
ru.xelgo.edt.contextlinks.ui.commands.configureApplicationUpdateProjects
ru.xelgo.edt.contextlinks.ui.commands.forceUpdateApplicationExtension
ru.xelgo.edt.contextlinks.ui.commands.changeAndValidateBatchMerge
ru.xelgo.edt.contextlinks.ui.commands.toggleGitChangedNavigatorFilter
```

Команды перечислены через ICommandService, проверены isEnabled/isHandled. Часть требует выбранного приложения/редактора. Вызов возможен через IHandlerService.executeCommand в подходящем UI-контексте; выполнение этих изменяющих команд не проверялось. Безвредный custom tool прочитал только версию bundle.

## 8. UI-путь и ограничения

Панель ChatView содержит JavaFX WebView; альтернативная DOM-автоматизация известна из предыдущих работ. Чтение/отправка текста проверялись; элементы карточек доступны для дальнейшего исследования, но полный цикл всех типов карточек здесь не подтверждён. Пользователь изначально просил **native MCP**, поэтому DOM-путь не считается завершением основной задачи.

IChat.askQuestion/continueChat, window.chatApi и элементы карточек — точки дальнейшего исследования, а не подтверждённый native API ответов на карточки. Для FX необходимо runLater и отдельный последующий опрос Future; не блокировать UI. Данные Chat.lastChatKey.token не читать и не экспортировать.

Пользователь открыл созданную API беседу в панели и продолжил её. Это подтверждает общую облачную беседу, но не обратную синхронизацию API-курсора после UI-ходов.

## 9. Известные ограничения

- Пустой ответ, отсутствие tool callback или 200 HTTP не доказывают ни отсутствие возможностей, ни успешное завершение задачи.
- Отрицательный index tool-call delta может уронить Conversations.applyDelta; обработчик не проверяет index<0. Причина генерации index=-1 не установлена. Не применять вслепую замену -1→0: можно склеить разные вызовы и аргументы.
- Парсер processStream обрабатывает role=assistant; в реальных промежуточных delta встречается role=null. Финальные content восстанавливают часть данных, но полнота стриминга/событий требует тестов.
- Серия инструментов может превышать 10 раундов. Recovery должен отличать серверное accepted от реально выполненной локальной операции и не повторять side effects.
- После перезапуска EDT потеряны экспериментальные Java-объекты. Восстановление с сохранёнными conversationId/replyToMessageUuid ещё не проверено.
- Пользователь сообщил о необходимости перезапуска после опытов. Не установлено, что виноват конкретный API/плагин. Нужны диагностика потоков, таймаутов, disposal и контролируемые нагрузочные испытания на отдельном workspace.
