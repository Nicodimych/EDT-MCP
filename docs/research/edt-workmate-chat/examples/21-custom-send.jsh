// Requires 00-bootstrap and 20-custom-setup. Starts a NEW cloud conversation.
wmFuture = wmStart(wmCustomFacade, null, true, "custom", java.util.Set.of("CodexHarmlessProbe"), "Выполни ровно один вызов CodexHarmlessProbe с label ГВОЗДЬ-8042. Верни точный JSON из результата инструмента. Не выдумывай proof. Ничего другого не делай.");
System.out.println("custom probe started; use 11-poll, then 22-custom-check");
