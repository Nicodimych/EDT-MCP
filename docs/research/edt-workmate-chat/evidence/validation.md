# Проверки пакета 08.10.2026

Это проверки, выполненные при подготовке файлов после перезапуска EDT. Полные модельные эксперименты до перезапуска описаны отдельно в [EXPERIMENTS](../EXPERIMENTS.md).

| Проверка | Результат |
|---|---|
| Python syntax: compile исходного текста mcp_probe.py | Успех; без создания __pycache__ |
| mcp_probe.py inspect | initialize + tools/list + get_server_status успешны; EDT-MCP 2.20.1, EDT 2026.2.1.3, 99 tools, running=true |
| 00-bootstrap.jsh через packaged Python client | job 98730227-6eea-4df9-8b17-5c72eed4d54b: done, compilation_errors=[], runtime_errors=[], bootstrap ready |
| 20-custom-setup.jsh через новый transport session | job a8580526-a3c1-4bca-a175-1814c4d5123e: done, compilation_errors=[], runtime_errors=[], registry=27, custom calls=0 |
| 11-poll.jsh до отправки | job 1c622779-0e34-4901-9107-87b885808f49: done, без ошибок, not started |
| 22-custom-check.jsh | job 09d52af5-98a2-4066-8a77-af5b885e40d5: done, без ошибок, native=26, isolated=27, calls=0 |
| get_job_status из другого MCP transport | Результаты bootstrap/custom-setup доступны; сохранены JSON captures |

Один JShell alias пережил закрытие/создание отдельных HTTP transport sessions. Внутренний UUID, возвращённый JShell: c3c18496-327d-440b-a177-869d427a8d7c. Alias и возвращённый UUID — разные представления REPL, не conversationId.

Сохранённые ответы: [bootstrap](bootstrap-validation.json), [custom setup](custom-setup-validation.json), [poll](poll-validation.json), [custom check](custom-check-validation.json).

10-start-raw, 12-followup и 21-custom-send **не запускались повторно** при упаковке. Их логика адаптирована из успешных ранних опытов. Текущая проверка подтверждает компиляцию bootstrap, класса инструмента и создание изолированного facade, но не новый полный цикл модели. 90-cancel не проверялся на активной генерации. Сборка/тесты upstream Java-плагина и Go-проекта не запускались: реализация этих проектов не изменялась.

В EDT оставлены только бездействующие диагностические Java-объекты wm* в REPL; сообщений модели и команд изменения конфигурации на этой стадии не отправлялось. Штатный реестр Напарника остался 26.

Заключительные проверки документов: все JSON читаются, локальные ссылки Markdown разрешаются; отдельно проверены новые текстовые файлы на хвостовые пробелы и конфликтные маркеры. Изменения ограничены docs/research/edt-workmate-chat. После переноса в форк материалы будут зафиксированы отдельным commit. Итоговый manifest содержит размеры и SHA256 всех файлов пакета, кроме самого manifest.
