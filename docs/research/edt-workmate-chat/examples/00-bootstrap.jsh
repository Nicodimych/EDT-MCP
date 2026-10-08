// Adapted handoff example for Workmate 1.0.8. Does not send a model request.
// Change this project name to an existing open test project if necessary.
var wmProject = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getProject("АвтодействияТест");
if (!wmProject.exists() || !wmProject.isOpen()) throw new IllegalStateException("Test project is absent or closed");
var wmFacade = com.e1c.edt.ai.ui.BaseActivator.getInstance(com.e1c.edt.ai.ConversationFacade.class);
var wmCancel = new java.util.concurrent.atomic.AtomicBoolean(false);
var wmEvents = new java.util.concurrent.CopyOnWriteArrayList<String>();
var wmProgress = new com.e1c.edt.ai.IConversationProgressListener() {
    public void onProgress(int a, int b) {}
    public void onToolCallStart(java.util.List<String> names) { wmEvents.add("start=" + names); }
    public void onToolCallEnd(java.util.List<String> names) { wmEvents.add("end=" + names); }
};
com.e1c.edt.ai.assistent.ConversationSession wmSession = null;
java.util.concurrent.CompletableFuture<com.e1c.edt.ai.assistent.SendMessageResult> wmFuture = null;
java.util.concurrent.CompletableFuture<com.e1c.edt.ai.assistent.SendMessageResult> wmStart(com.e1c.edt.ai.ConversationFacade facade, com.e1c.edt.ai.assistent.ConversationSession session, boolean fresh, String skill, java.util.Set<String> allowed, String message) {
    if (wmFuture != null && !wmFuture.isDone()) throw new IllegalStateException("Previous turn is still running");
    wmCancel.set(false);
    wmEvents.clear();
    var request = new com.e1c.edt.ai.assistent.SendUserMessageRequest(wmProject, message, session, fresh, skill, true, 5, allowed);
    return java.util.concurrent.CompletableFuture.supplyAsync(() -> facade.sendAsync(request, () -> wmCancel.get(), wmProgress)).thenCompose(f -> f);
}
System.out.println("bootstrap ready; project=" + wmProject.getName());
