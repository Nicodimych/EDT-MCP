# Состав доказательств

| Файл | Происхождение |
|---|---|
| [server-status.json](server-status.json) | Свежий get_server_status после перезапуска EDT и ручного запуска MCP пользователем; без HTTP session header |
| [selected-tool-schemas.json](selected-tool-schemas.json) | Отобранные 5 спецификаций из tools/list с общим числом 99; получены при подготовке пакета |
| [projects.json](projects.json) | list_projects при подготовке пакета; содержит локальные имена/пути проектов |
| [api-signatures.txt](api-signatures.txt) | javap -p установленного jar Напарника, версия и SHA256 |
| [bootstrap-validation.json](bootstrap-validation.json), [custom-setup-validation.json](custom-setup-validation.json) | Свежие завершённые jobs: компиляция bootstrap и создание отдельного facade/реестра |
| [poll-validation.json](poll-validation.json), [custom-check-validation.json](custom-check-validation.json) | Проверка без активного модельного хода: poll=not started, исходный реестр 26, изолированный 27 |
| [validation.md](validation.md) | Проверки файлов и адаптированных примеров при подготовке |
| [../EXPERIMENTS.md](../EXPERIMENTS.md) | Реконструкция исторических результатов; не raw trace |
| [cli-static-evidence.txt](cli-static-evidence.txt) | Свежая проверка установленного AskCommand: параметры, Response, управление сессией и factory |
| [cli-runtime-check.json](cli-runtime-check.json) | В работающем EDT experimental=false и factory возвращает DisabledAskCommand |
| [cli-launch-check.json](cli-launch-check.json) | Проверки самостоятельного launcher в отдельном временном workspace |

capturedAt в JSON — время сохранения снимка. В server-status запрос сделан непосредственно при сохранении; каталог/проекты прочитаны немного раньше в той же подготовке.

Токены, cookies, Authorization, содержимое Chat.lastChatKey, reasoning и полные статьи ИТС не включены. Внутренние названия проектов и идентификаторы старых бесед оставлены в техническом досье для локального продолжения. Для первого публичного обращения достаточно UPSTREAM_REQUEST.md.
