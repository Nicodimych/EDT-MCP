// Requires 00-bootstrap. Starts a NEW cloud conversation.
wmFuture = wmStart(wmFacade, null, true, "raw", java.util.Set.of(), "Контроль контекста. Запомни метку ЛИПА-6821. Ответь только: запомнил. Инструменты не нужны.");
System.out.println("started; poll in a separate MCP call");
