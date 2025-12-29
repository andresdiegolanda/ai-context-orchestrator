# Sample Document

This is a sample document for testing the AI Context Orchestrator.

## Virtual Threads

Virtual threads are lightweight threads managed by the JVM. They were introduced in Java 21 as part of Project Loom (JEP 444).

### Key Benefits

- **Lightweight**: Virtual threads have a small memory footprint (~1KB vs ~1MB for platform threads)
- **Scalability**: You can create millions of virtual threads
- **Blocking is cheap**: When a virtual thread blocks, it unmounts from its carrier thread

### Example

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    executor.submit(() -> {
        // This runs on a virtual thread
        System.out.println("Hello from virtual thread!");
    });
}
```

## Rate Limiting

Rate limiting controls how many requests a client can make in a given time period.

### Common Algorithms

1. **Token Bucket**: Tokens accumulate over time, each request consumes a token
2. **Leaky Bucket**: Requests flow out at a constant rate
3. **Fixed Window**: Count requests in fixed time windows
4. **Sliding Window**: Weighted combination of current and previous windows
