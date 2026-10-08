// Local proof must match the model's completed answer from 11-poll.
// A calls count alone does not prove the server consumed the result.
System.out.println("local calls=" + wmTool.calls.get());
System.out.println("local proof=" + wmTool.proof);
System.out.println("received arguments=" + wmTool.received);
System.out.println("native registry size=" + ((java.util.Map<?, ?>) wmField(wmNativeTools, "tools")).size());
System.out.println("isolated registry size=" + wmToolSet.size());
