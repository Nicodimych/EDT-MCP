// join is called ONLY when the future is already complete; no waiting on SWT.
if (wmFuture == null) {
    System.out.println("not started");
} else if (!wmFuture.isDone()) {
    System.out.println("running; events=" + wmEvents);
} else {
    try {
        var result = wmFuture.join();
        wmSession = result.getSession();
        System.out.println("text=" + result.getText());
        System.out.println("assistantMessageCount=" + result.getAssistantMessageCount());
        System.out.println("conversationId=" + (wmSession == null ? "null" : wmSession.getConversationId()));
        System.out.println("replyToMessageUuid=" + (wmSession == null ? "null" : wmSession.getReplyToMessageUuid()));
        System.out.println("events=" + wmEvents);
    } catch (java.util.concurrent.CompletionException error) {
        System.out.println("failed=" + error.getCause());
    } catch (java.util.concurrent.CancellationException error) {
        System.out.println("future cancelled; verify underlying operation separately");
    }
}
