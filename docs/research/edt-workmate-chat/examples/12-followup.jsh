// Run only after a successful 11-poll saved the session from 10-start-raw.
if (wmSession == null) throw new IllegalStateException("No completed session to continue");
wmFuture = wmStart(wmFacade, wmSession, false, "raw", java.util.Set.of(), "Какую метку я просил запомнить? Ответь только самой меткой. Инструменты не нужны.");
System.out.println("follow-up started; use 11-poll again");
