# Проверка варианта Gemini: можно ли продолжать диалог через 1cedtcli ask

Дата: 08.10.2026. Исследована фактически загруженная версия `com.e1c.edt.ai.cli 1.0.8.v202609241142`.

**Вывод:** ConversationSession внутри CLI существует и используется для автоматического продолжения одного задания. Готового интерфейса, чтобы следующим запуском CLI передать новую реплику в существующий диалог, в этой версии не найдено. Это подтверждается полным списком параметров и начальным состоянием runTurns, а не только отсутствием примера в документации.

## Что подтвердилось и что нет

| Утверждение | Проверка |
|---|---|
| Есть штатный AskCommand и JSON output | Да, классы и обработка output присутствуют |
| JSON содержит conversationId | Да, AskCommand$Response имеет это поле |
| Значит можно передать ID следующему CLI запуску | Нет такого входного параметра; каждый runTurns начинается с session=null, forceNew=true |
| autoContinueCount показывает продолжение | Да, но это внутренние автоматические ходы в рамках одной команды |
| Сессия полноценно сохраняется в JSON | Нет: сохраняется conversationId, но не replyToMessageUuid из ConversationSession |
| CLI готов к работе в текущей установке | Нет: live ISettings.isExperimental()=false; factory.create() возвращает DisabledAskCommand |
| --prompt — объявленный параметр AskCommand | В 1.0.8 prompt позиционный; именованного --prompt в аннотациях нет |
| Решение «100% стабильно» и готово из коробки | Такой вывод не подтверждается ни текущей доступностью команды, ни контрактом продолжения |

## Точные аргументы установленного класса

Две перегрузки c1_ask/c2_ask:

- --project либо --project-name;
- --max-tool-rounds;
- --token;
- --output;
- --skill;
- --params;
- заключительный позиционный prompt.

Нет --session, --conversation-id, --resume, --reply-to или загрузки ConversationSession из output-файла. --params передаёт параметры skill, а не общую сессию диалога.

Пример формы команды **по аннотациям**, не подтверждённый успешным AI-вызовом в текущей установке:

```text
1cedtcli.exe -data <отдельный-workspace> -command ask --project-name <проект> --output <result.json> "Текст запроса"
```

Не запускать второй процесс EDT с тем же workspace, который открыт в рабочей GUI-сессии. Совпадение каталога workspace само по себе не задаёт conversationId.

## Где используется сессия

Упрощённое представление прочитанного bytecode, не исходный Java-файл:

```java
ConversationSession session = null; // заново при каждом runTurns
boolean forceNew = true;
String prompt = initialPrompt;

// Внутренний цикл одной команды CLI:
result = facade.sendAsync(new SendUserMessageRequest(
    project, prompt, session, forceNew, "custom", false,
    maxToolRounds, allowedTools, completionPolicy), token).get(...);

response.conversationId = result.getSession().getConversationId();
if (AutoContinuePolicy.shouldAutoContinue(result, count)) {
    session = result.getSession();
    forceNew = false;
    prompt = FIXED_CONTINUATION_INSTRUCTION;
    // Следующая итерация того же вызова.
}
```

AutoContinuePolicy проверяет результат на пустой/промежуточный ответ и маркеры продолжения. Это не чтение следующей реплики пользователя, не ожидание stdin и не обработка произвольной карточки подтверждения. Входной session в runTurns отсутствует.

Поля Response: prompt, project, conversationId, finalText, reasoning, autoContinueCount, toolCalls. Поле reasoning не требуется для восстановления диалога; содержимое рассуждений в доказательства не собиралось.

## Живая проверка доступности

Через MCP в уже работающем EDT получено:

```text
cliBundle=1.0.8.v202609241142
experimental=false
registeredCommand=com.e1c.edt.ai.cli.AskCommandExtensionFactory$DisabledAskCommand
```

Статически factory.create() действительно выбирает DisabledAskCommand при false. Флаг не меняли, рабочий AskCommand в обход factory не запускали. Поэтому двухходовый AI-тест через штатный CLI в текущей установке не состоялся.

Отдельно проверен launcher `1cedtcli -help`: exit 0; подтверждены -data, -command, -file, -timeout. Затем `-command help ask` в отдельном временном workspace вернул «Команда не найдена.» и exit 0. Этот нулевой код относится к help, а не к успешному AI-заданию.

Первоначальный пробный запуск с --prompt из присланного примера не дал result.json и не подтвердил выполнение ask; собственные диагностические процессы остановлены. Он не является доказательством ошибки именно модели или именно feature flag: загрузка CLI и корректность параметров — отдельные этапы. После завершения проверки запущенных нами CLI-процессов не осталось. Детали см. [cli-launch-check.json](evidence/cli-launch-check.json).

## Что означает наличие conversationId

Это полезный идентификатор облачной беседы, его можно сохранять для связи с журналом/панелью. Возможность продолжения лежит уровнем ниже: native SendUserMessageRequest принимает ConversationSession. Именно этот путь уже дал два хода в прежних экспериментах.

Для адаптера мы сохраняли пару conversationId + replyToMessageUuid. CLI выводит только первое, поэтому не даёт готового checkpoint последнего ответа. Работа только с conversationId без определения актуального parent UUID отдельно не проверена; нельзя обещать отсутствие ветвления или продолжение последнего UI-хода.

## Как превратить идею CLI в рабочий вариант

Предлагаемая доработка: --session-in/--session-out либо --resume с устойчивым state store. При наличии сохранённой сессии подставлять её в первый SendUserMessageRequest и forceNew=false; после каждого завершённого хода сохранять возвращённую сессию. Дополнительно нужны защита от старого cursor, ошибок связи, повторных side effects и структурированных вопросов.

Это требует изменения/расширения реализации команды, а не добавления уже существующего скрытого флага в вызов. Включение experimental само по себе параметра resume не добавит.

Для нашего форка EDT-MCP разумно переиспользовать проверенный native facade и реализовать состояние чата в мосте, как описано в [API_PROPOSAL](API_PROPOSAL.md). CLI можно сделать клиентом такого API. Тогда запуск короткой команды снаружи не требует каждый раз загружать отдельный EDT и импортировать проект.

## Доказательства

- [cli-static-evidence.txt](evidence/cli-static-evidence.txt): SHA256 установленного jar, полные параметры, поля Response, ключевые инструкции runTurns и factory.
- [cli-runtime-check.json](evidence/cli-runtime-check.json): свежие результаты read-only MCP-проверки.
- [cli-launch-check.json](evidence/cli-launch-check.json): отдельно результат проверки launcher; не смешивать с проверкой внутреннего API.
- [EXPERIMENTS](EXPERIMENTS.md): ранее подтверждённое native продолжение диалога.
