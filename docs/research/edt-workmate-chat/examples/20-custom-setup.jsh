class WmHandoffProbe implements com.e1c.edt.ai.IMcpTool {
 final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
 final java.util.List<String> received=new java.util.concurrent.CopyOnWriteArrayList<>();
 final String proof="LOCAL-"+java.util.UUID.randomUUID();
 public com.e1c.edt.ai.assistent.model.McpToolCallSpecification getSpecification(){var s=new com.e1c.edt.ai.assistent.model.McpToolCallSpecification();s.type="function";s.function=new com.e1c.edt.ai.assistent.model.McpToolCallFunction();s.function.name="CodexHarmlessProbe";s.function.description="Read-only test tool implemented locally in the current EDT process. Takes label and returns that label, a unique proof generated locally, call number, and installed Xelgo extension version. Does not modify files, configuration, preferences or UI.";s.function.parameters=new com.e1c.edt.ai.assistent.model.McpToolCallParameters();s.function.parameters.type="object";var p=new com.e1c.edt.ai.assistent.model.McpToolCallProperty();p.type="string";p.description="Short test label to echo";s.function.parameters.properties=java.util.Map.of("label",p);s.function.parameters.required=java.util.List.of("label");return s;}
 public java.util.concurrent.CompletableFuture<com.e1c.edt.ai.ToolCallMessage> call(com.e1c.edt.ai.assistent.model.McpToolCall call,com.e1c.edt.ai.ICancellationToken cancel){int n=calls.incrementAndGet();received.add(call.function.arguments);var args=com.google.gson.JsonParser.parseString(call.function.arguments).getAsJsonObject();var out=new com.google.gson.JsonObject();out.addProperty("label",args.get("label").getAsString());out.addProperty("proof",proof);out.addProperty("call_number",n);var b=org.eclipse.core.runtime.Platform.getBundle("ru.xelgo.edt.contextlinks.ui");out.addProperty("xelgo_version",b==null?"not installed":b.getVersion().toString());var m=new com.e1c.edt.ai.ToolCallMessage();m.role="tool";m.tool_call_id=call.id;m.status="ok";m.content=out.toString();return java.util.concurrent.CompletableFuture.completedFuture(m);}
}
// Adapted from the successful isolated custom-tool experiment. Requires 00-bootstrap.
// Reflection reads dependencies; original singleton registry is not modified.
Object wmField(Object target, String name) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
}
var wmNativeConversations = wmField(wmFacade, "conversations");
var wmNativeTools = (com.e1c.edt.ai.IMcpTools) wmField(wmFacade, "mcpTools");
var wmTool = new WmHandoffProbe();
var wmToolSet = new java.util.HashSet<com.e1c.edt.ai.IMcpTool>(((java.util.Map<String, com.e1c.edt.ai.IMcpTool>) wmField(wmNativeTools, "tools")).values());
wmToolSet.add(wmTool);
var wmExecutor = new com.e1c.edt.ai.McpTools(
    (com.e1c.edt.ai.ILog) wmField(wmNativeTools, "log"),
    (com.e1c.edt.ai.ISettings) wmField(wmNativeTools, "settings"), wmToolSet,
    (com.e1c.edt.ai.IMcpToolsCallMessageFactory) wmField(wmNativeTools, "messageFactory"),
    (com.e1c.edt.ai.IJson) wmField(wmNativeTools, "json"),
    (com.e1c.edt.ai.IDevToolCallRecorder) wmField(wmNativeTools, "devRecorder"));
var wmJson = (com.e1c.edt.ai.IJson) wmField(wmNativeConversations, "json");
var wmConversations = new com.e1c.edt.ai.assistent.Conversations(
    (com.e1c.edt.ai.assistent.IHttpLog) wmField(wmNativeConversations, "log"),
    (com.e1c.edt.ai.ISettings) wmField(wmNativeConversations, "settings"),
    (com.e1c.edt.ai.assistent.IRequestBuilder) wmField(wmNativeConversations, "requestBuilder"),
    (com.e1c.edt.ai.assistent.IHttpClientBuilder) wmField(wmNativeConversations, "clientBuilder"), wmJson,
    (com.e1c.edt.ai.assistent.ISessionService) wmField(wmNativeConversations, "sessionService"), wmExecutor,
    (com.e1c.edt.ai.ILog) wmField(wmNativeConversations, "logDebug"));
var wmCustomFacade = new com.e1c.edt.ai.ConversationFacade(wmConversations, wmJson, wmExecutor,
    (com.e1c.edt.ai.ISettings) wmField(wmFacade, "settings"),
    (com.e1c.edt.ai.ILog) wmField(wmFacade, "log"));
System.out.println("isolated registry size=" + wmToolSet.size() + "; custom calls=" + wmTool.calls.get());
