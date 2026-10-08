# Как воспроизвести результаты

Для исследованной установки: EDT 2026.2.1.3, Напарник 1.0.8.v202609241142, EDT-MCP 2.20.1. Примеры адаптированы из прошедших опытов и используют внутренний, зависящий от версии Java API. Они не устанавливают постоянный сервис чата.

## 1. Проверка транспорта без обращения к модели

Открыть PowerShell в этом каталоге. MCP должен быть запущен в EDT, Напарник авторизован штатным способом. Python 3.10+; внешние библиотеки не нужны.

```powershell
python .\examples\mcp_probe.py inspect
```

Ожидается running=true, версия плагина и имена tools. Клиент каждый раз делает initialize с `MCP-Protocol-Version`, получает новый HTTP session, отправляет initialized, поддерживает JSON/SSE и закрывает свой transport session. Это не удаляет облачный чат и не сбрасывает JShell alias.

При 404 нельзя автоматически повторять исходный send: сначала восстановить соединение и выяснить, был ли ход принят. Файл не хранит transport ID на диске и не читает пользовательскую авторизацию.

## 2. Подготовка Java-объектов

В [00-bootstrap.jsh](examples/00-bootstrap.jsh) указано имя существовавшего тестового проекта `АвтодействияТест`. При другом workspace заменить имя на нужный открытый тестовый проект.

```powershell
python .\examples\mcp_probe.py jshell .\examples\00-bootstrap.jsh
```

Этот шаг не отправляет сообщение модели. Он получает facade, создаёт token/listener и helper для фонового вызова. Проверить вывод compilation_errors/runtime_errors, а не только exit code Python.

ask_workmate может вернуть running и jobId. Тогда опрашивать **тот же job**, не повторять Java-фрагмент:

```powershell
python .\examples\mcp_probe.py job <jobId> --wait 5
```

Не запускать bootstrap повторно во время генерации: его новые переменные изменят ссылку на cancellation token. Каждый пример выполняется в том же `repl_session_id=edt-mcp`, но с отдельным новым MCP transport. После restart EDT начать bootstrap заново.

## 3. Два хода с общим контекстом

Следующие команды действительно обращаются к облачному Напарнику и создают чат:

```powershell
python .\examples\mcp_probe.py jshell .\examples\10-start-raw.jsh
python .\examples\mcp_probe.py jshell .\examples\11-poll.jsh
# Повторять только 11-poll, пока не получен результат и ConversationSession.
python .\examples\mcp_probe.py jshell .\examples\12-followup.jsh
python .\examples\mcp_probe.py jshell .\examples\11-poll.jsh
```

Во втором prompt нет самой метки; ожидается ЛИПА-6821. Проверить одинаковый conversationId и новый replyToMessageUuid. Пустой ответ не считать успехом. allowedTools=empty убирает объявления локальных инструментов, но не является защитой от серверных вызовов/реальной ACL; сами prompts просят не использовать инструменты.

wmStart переносит начало sendAsync с SWT в фон. wmFuture.join вызывается только после isDone=true. Для продукта нужен управляемый executor, а не common pool из диагностического примера.

## 4. Свой инструмент

После bootstrap:

```powershell
python .\examples\mcp_probe.py jshell .\examples\20-custom-setup.jsh
python .\examples\mcp_probe.py jshell .\examples\21-custom-send.jsh
python .\examples\mcp_probe.py jshell .\examples\11-poll.jsh
# После завершения:
python .\examples\mcp_probe.py jshell .\examples\22-custom-check.jsh
```

20-custom-setup только создаёт отдельные объекты с расширенным реестром. 21-custom-send объявляет модели CodexHarmlessProbe и инициирует вызов. 22-custom-check читает локальную proof и счётчик. Успех: один фактический вызов, та же proof в финальном ответе, исходный реестр 26 и изолированный 27 в исследованной версии.

Исполнитель возвращает label, случайную proof, call_number и версию Xelgo. Не выполняет команды расширения и не пишет файлы/метаданные. На новой установке размеры реестров и версия могут измениться: важна разница +1 и неизменность исходного реестра.

Пример 20 использует reflection для чтения зависимостей внутренних объектов. При несовместимой версии остановиться на ошибке, обновить адаптер по сигнатурам; не угадывать имена полей и не подменять глобальные singleton.

## 5. Отмена и следующая диагностика

```powershell
python .\examples\mcp_probe.py jshell .\examples\90-cancel.jsh
python .\examples\mcp_probe.py jshell .\examples\11-poll.jsh
```

Это cooperative request. Пока не доказано прекращение фоновых/серверных действий, нельзя выдавать его за гарантированную отмену. Не повторять зависший send только потому, что истёк HTTP timeout.

Сценарии ИТС, точного web fetch и вопроса вынесены в [server-tools-prompts.md](examples/server-tools-prompts.md). Для них следует отдельно записать фактические события и сверить terminal result. Одного сообщения модели «сделано» недостаточно.

## Проверка при подготовке этого пакета

Свежие проверки и результаты записаны в [evidence/validation.md](evidence/validation.md). Там отдельно указаны компиляция/инициализация адаптированных примеров и историческое полное исполнение. Новые облачные беседы только ради оформления документации не запускались.

## Что намеренно не автоматизировано

Восстановление по старым conversation IDs из отчёта; подтверждение изменений; обход ошибок delta index; извлечение browser/session credentials; ручной accepted неизвестным вызовам. Эти действия требуют полноценной реализации состояния, а не копирования диагностического обхода в production.
