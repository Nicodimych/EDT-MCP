# Источники и воспроизводимость

Проверено/подготовлено 08.10.2026. При расхождении версия установленного bundle и commit источника указываются отдельно. «Исходники Напарника» в этом пакете означают исследование сигнатур/bytecode поставленных Java-классов; публичного исходного Java-проекта Напарника мы не получали.

## EDT-MCP

- [Upstream и описание in-process bridge](https://github.com/DitriXNew/EDT-MCP#1cworkmate-in-process-bridge).
- [Документация ask_workmate](https://github.com/DitriXNew/EDT-MCP/blob/6d1853136226ccf50c2d68994f6bc94c57c1adb7/docs/tools/ask_workmate.md).
- [AskWorkmateTool.java](https://github.com/DitriXNew/EDT-MCP/blob/6d1853136226ccf50c2d68994f6bc94c57c1adb7/mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/tools/impl/AskWorkmateTool.java): параметры, direct tool, инструкция мосту.
- [WorkmateGateway.java: request construction](https://github.com/DitriXNew/EDT-MCP/blob/6d1853136226ccf50c2d68994f6bc94c57c1adb7/mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/utils/WorkmateGateway.java#L797): request, session и skill/chat. [Путь панели](https://github.com/DitriXNew/EDT-MCP/blob/6d1853136226ccf50c2d68994f6bc94c57c1adb7/mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/utils/WorkmateGateway.java#L2249): IChat.askQuestion и отсутствие возврата полного ответа этим путём.
- [EdtMcpBridge.java](https://github.com/DitriXNew/EDT-MCP/blob/6d1853136226ccf50c2d68994f6bc94c57c1adb7/mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/bridge/EdtMcpBridge.java#L126): apply/callTool, вход в тот же McpProtocolHandler без HTTP.
- [McpHttpHandler.java](https://github.com/DitriXNew/EDT-MCP/blob/6d1853136226ccf50c2d68994f6bc94c57c1adb7/mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/transport/McpHttpHandler.java): transport sessions и protocol header.
- [Build workflow](https://github.com/DitriXNew/EDT-MCP/blob/6d1853136226ccf50c2d68994f6bc94c57c1adb7/.github/workflows/build.yml): JDK/Maven/Tycho и рабочая директория mcp.

Локальный source snapshot: `E:\projects\Автодействия\.tmp\edt-mcp-src`; HEAD `6d1853136226ccf50c2d68994f6bc94c57c1adb7`, чистый при подготовке. Рабочий форк: `E:\projects\EDT-MCP`, remote `origin=https://github.com/Nicodimych/EDT-MCP.git`, `upstream=https://github.com/DitriXNew/EDT-MCP.git`. Не доказано, что установленная сборка 2.20.1 собрана именно из старого snapshot. Список точек изменений/тестов — [FORK_PLAN](FORK_PLAN.md).

## Напарник

- [Официальное руководство](https://code.1c.ai/support/) описывает работу пользователя, инструменты, навыки и разрешения. Оно не является спецификацией исследованных внутренних ConversationFacade/McpTools.
- Установленный jar: `C:\Program Files\1C\1CE\components\1c-edt-2026.2.1+3-x86_64\plugins\com.e1c.edt.ai_1.0.8.v202609241142.jar`.
- Соседние bundles: com.e1c.edt.ai.ui.common, com.e1c.edt.ai.context той же версии.
- [API signatures](evidence/api-signatures.txt): свежий javap -p, SHA256 jar, методы и private field names. Это структурные сведения; бинарники и полная декомпиляция не включены.

Ключевые исследованные классы: ConversationFacade, assistent.Conversations, SendUserMessageRequest, SendMessageResult, ConversationSession, IMcpTools/McpTools, IMcpTool, ToolCallMessage, McpToolCalls, McpToolCallSpecification. Написание пакета `assistent` именно такое в поставке.

## Xelgo

- [Репозиторий](https://github.com/Xelgo/edt-extension-tweaks).
- [plugin.xml на просмотренной ревизии](https://github.com/Xelgo/edt-extension-tweaks/blob/7ba661a8455e6d9e5c85e01e6734d268871cd772/bundles/ru.xelgo.edt.contextlinks.ui/plugin.xml): Eclipse commands/handlers. Эта ревизия не объявляется исходником установленного бинарника.
- Локальный bundle `ru.xelgo.edt.contextlinks.ui_1.1.22.v202608042058.jar`, каталог `C:\Program Files\Common Files\.p2\pool\plugins`.
- Command IDs подтверждались также по установленному bundle и через ICommandService. Выполнение изменяющих команд не проверялось.

## Локальные материалы и пользовательские наблюдения

- Заметка пользователя: `C:\Users\nicodim\.zcode\cli\memories\projects\project-acad559381b63634\memory\edt-mcp-workmate-chat-access.md`. Из неё использовались ориентиры DOM/WebView, JShell alias и ограничения UI потоков. Метод GUI пользователь уже знал; он не считается решением native MCP задачи.
- Предыдущий диагностический helper: `E:\projects\Автодействия\.tmp\mcp_jshell.py`. Вместо зависимости от его текущего состояния подготовлен самостоятельный [mcp_probe.py](examples/mcp_probe.py).
- Сообщение пользователя о фактическом mcp__web__fetch в панели и отсутствии mcp__web__search отражено в [EXPERIMENTS](EXPERIMENTS.md). Это отдельный уровень USER-UI, не raw trace нашего внешнего клиента.
- Сообщение пользователя о перезапуске EDT и ручном запуске MCP записано как наблюдение без установленной причины.

## Сохранённые доказательства

[evidence/README.md](evidence/README.md) различает свежие captures, статические сигнатуры и реконструированное описание исторических опытов. Исходные потоки облачных ответов целиком в пакет не включены; не следует выдавать пересказ эксперимента за исходный сетевой дамп.
