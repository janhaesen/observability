package io.github.aeshen.observability;

import io.github.aeshen.observability.codec.EncodedEvent;
import io.github.aeshen.observability.config.sink.Console;
import io.github.aeshen.observability.config.sink.Http;
import io.github.aeshen.observability.config.sink.SinkConfig;
import io.github.aeshen.observability.diagnostics.ObservabilityDiagnostics;
import io.github.aeshen.observability.key.KeyGroups;
import io.github.aeshen.observability.key.NamespacedKeys;
import io.github.aeshen.observability.key.StringKey;
import io.github.aeshen.observability.sink.EventLevel;
import io.github.aeshen.observability.sink.ObservabilitySink;
import io.github.aeshen.observability.sink.decorator.BackoffStrategy;
import io.github.aeshen.observability.sink.registry.SinkRegistry;

import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Verifies that the public API is ergonomic and fully usable from Java.
 * Each test targets a specific Java interop improvement.
 */
public class JavaInteropTest {

    // ---------------------------------------------------------------------------
    // Phase 1 – @JvmStatic / @JvmField
    // ---------------------------------------------------------------------------

    @Test
    public void staticObservabilityContextFactory() {
        // No ".Companion." required
        ObservabilityContext ctx = ObservabilityContext.builder()
                .put(StringKey.PATH, "/api")
                .build();
        assertEquals("/api", ctx.get(StringKey.PATH));

        ObservabilityContext empty = ObservabilityContext.empty();
        assertNotNull(empty);
    }

    @Test
    public void noopDiagnosticsIsStaticField() {
        // Should be accessible as a plain static field, not via Companion.getNOOP()
        ObservabilityDiagnostics noop = ObservabilityDiagnostics.NOOP;
        assertNotNull(noop);
    }

    @Test
    public void backoffStrategyStaticFactories() {
        BackoffStrategy fixed = BackoffStrategy.fixed(50L);
        assertEquals(50L, fixed.nextDelayMillis(1));

        BackoffStrategy exp = BackoffStrategy.exponential(10L, 2.0, 1000L);
        assertTrue(exp.nextDelayMillis(1) >= 0);
    }

    @Test
    public void sinkRegistryStaticFactories() {
        SinkRegistry def = SinkRegistry.getDefault();
        assertNotNull(def);

        SinkRegistry empty = SinkRegistry.empty();
        assertNotNull(empty);

        SinkRegistry.Builder builder = SinkRegistry.builder();
        assertNotNull(builder);

        SinkRegistry.Builder defaultBuilder = SinkRegistry.defaultBuilder();
        assertNotNull(defaultBuilder);
    }

    @Test
    public void observabilityFactoryStaticCreate() throws Exception {
        // ObservabilityFactory.create() without .INSTANCE.
        Observability obs = ObservabilityFactory.create(
                new ObservabilityFactory.Config.Builder(List.of(Console.INSTANCE)).build()
        );
        assertNotNull(obs);
        obs.close();
    }

    // ---------------------------------------------------------------------------
    // Phase 2 – @JvmOverloads on constructors
    // ---------------------------------------------------------------------------

    @Test
    public void httpConfigWithDefaultsViaOverload() {
        // Only required param; method/headers/timeoutMillis should have defaults
        Http http = new Http("https://example.com/logs");
        assertEquals("https://example.com/logs", http.getEndpoint());
    }

    @Test
    public void backoffStrategyExponentialWithDefaults() {
        // All params have defaults; no-arg Java call
        BackoffStrategy exp = BackoffStrategy.exponential();
        assertTrue(exp.nextDelayMillis(1) >= 0);
    }

    // ---------------------------------------------------------------------------
    // Phase 3 – Config.Builder
    // ---------------------------------------------------------------------------

    @Test
    public void configBuilderWithDefaults() {
        ObservabilityFactory.Config config = new ObservabilityFactory.Config.Builder(List.of(Console.INSTANCE))
                .build();
        assertNotNull(config);
        assertEquals(ObservabilityFactory.Profile.STANDARD, config.getProfile());
        assertFalse(config.getFailOnSinkError());
    }

    @Test
    public void configBuilderWithCustomProfile() {
        ObservabilityFactory.Config config = new ObservabilityFactory.Config.Builder(List.of(Console.INSTANCE))
                .profile(ObservabilityFactory.Profile.AUDIT_DURABLE)
                .failOnSinkError(true)
                .diagnostics(ObservabilityDiagnostics.NOOP)
                .build();

        assertEquals(ObservabilityFactory.Profile.AUDIT_DURABLE, config.getProfile());
        assertTrue(config.getFailOnSinkError());
    }

    // ---------------------------------------------------------------------------
    // Phase 4 – SinkRegistry.Builder.register(Class<T>, Function<T, Sink>)
    // ---------------------------------------------------------------------------

    @Test
    public void registerSinkViaClassAndFunction() throws Exception {
        List<EncodedEvent> received = new ArrayList<>();
        ObservabilitySink captureSink = event -> received.add(event);

        SinkRegistry registry = SinkRegistry.defaultBuilder()
                .register(CustomSinkConfig.class, cfg -> captureSink)
                .build();

        Observability obs = ObservabilityFactory.create(
                new ObservabilityFactory.Config.Builder(List.of(new CustomSinkConfig()))
                        .sinkRegistry(registry)
                        .build()
        );
        obs.info(TestEvent.TEST);
        obs.close();

        assertEquals(1, received.size());
    }

    // ---------------------------------------------------------------------------
    // Phase 5 – Observability interface convenience overloads
    // ---------------------------------------------------------------------------

    @Test
    public void infoWithNameOnly() {
        AtomicReference<ObservabilityEvent> captured = new AtomicReference<>();
        Observability obs = new Observability() {
            @Override public void emit(ObservabilityEvent event) { captured.set(event); }
            @Override public void close() {}
        };

        obs.info(TestEvent.TEST);

        assertNotNull(captured.get());
        assertEquals(EventLevel.INFO, captured.get().getLevel());
        assertNull(captured.get().getMessage());
    }

    @Test
    public void infoWithNameAndMessage() {
        AtomicReference<ObservabilityEvent> captured = new AtomicReference<>();
        Observability obs = new Observability() {
            @Override public void emit(ObservabilityEvent event) { captured.set(event); }
            @Override public void close() {}
        };

        obs.info(TestEvent.TEST, "hello from Java");

        assertEquals("hello from Java", captured.get().getMessage());
    }

    @Test
    public void errorWithNameMessageAndThrowable() {
        AtomicReference<ObservabilityEvent> captured = new AtomicReference<>();
        Observability obs = new Observability() {
            @Override public void emit(ObservabilityEvent event) { captured.set(event); }
            @Override public void close() {}
        };

        RuntimeException ex = new RuntimeException("boom");
        obs.error(TestEvent.TEST, "something failed", ex);

        assertEquals(EventLevel.ERROR, captured.get().getLevel());
        assertSame(ex, captured.get().getError());
    }

    @Test
    public void warnWithNameMessageAndThrowable() {
        AtomicReference<ObservabilityEvent> captured = new AtomicReference<>();
        Observability obs = new Observability() {
            @Override public void emit(ObservabilityEvent event) { captured.set(event); }
            @Override public void close() {}
        };

        obs.warn(TestEvent.TEST, "degraded", new RuntimeException("cause"));

        assertEquals(EventLevel.WARN, captured.get().getLevel());
    }

    @Test
    public void traceAndDebugWithNameOnly() {
        AtomicReference<ObservabilityEvent> lastTrace = new AtomicReference<>();
        AtomicReference<ObservabilityEvent> lastDebug = new AtomicReference<>();
        Observability obs = new Observability() {
            @Override public void emit(ObservabilityEvent event) {
                if (event.getLevel() == EventLevel.TRACE) lastTrace.set(event);
                if (event.getLevel() == EventLevel.DEBUG) lastDebug.set(event);
            }
            @Override public void close() {}
        };

        obs.trace(TestEvent.TEST);
        obs.debug(TestEvent.TEST);

        assertEquals(EventLevel.TRACE, lastTrace.get().getLevel());
        assertEquals(EventLevel.DEBUG, lastDebug.get().getLevel());
    }

    // ---------------------------------------------------------------------------
    // Phase 6 – kotlin.time.Instant bridge
    // ---------------------------------------------------------------------------

    @Test
    public void getJavaTimestampReturnsJavaInstant() {
        AtomicReference<ObservabilityEvent> captured = new AtomicReference<>();
        Observability obs = new Observability() {
            @Override public void emit(ObservabilityEvent event) { captured.set(event); }
            @Override public void close() {}
        };
        obs.info(TestEvent.TEST);

        Instant javaTs = captured.get().getJavaTimestamp();
        assertNotNull(javaTs);
        // Should be within last few seconds
        assertTrue(Instant.now().toEpochMilli() - javaTs.toEpochMilli() < 5_000);
    }

    @Test
    public void eventBuilderAcceptsJavaInstant() {
        Instant fixedTs = Instant.ofEpochSecond(1_700_000_000L);
        ObservabilityEvent event = new ObservabilityEvent.EventBuilder(TestEvent.TEST)
                .timestamp(fixedTs)
                .build();

        assertEquals(fixedTs, event.getJavaTimestamp());
    }

    // ---------------------------------------------------------------------------
    // Phase 7 – @file:JvmName renames
    // ---------------------------------------------------------------------------

    @Test
    public void keyGroupsStaticPutIsReachable() {
        // KeyGroups (was KeyGroupKt) must be accessible
        ObservabilityContext.Builder builder = ObservabilityContext.builder();
        KeyGroups.put(builder, b -> b.put(StringKey.METHOD, "GET"));
        ObservabilityContext ctx = builder.build();
        assertEquals("GET", ctx.get(StringKey.METHOD));
    }

    @Test
    public void namespacedKeysStaticPutIsReachable() {
        // NamespacedKeys (was NamespacedKeyKt) must be accessible
        ObservabilityContext.Builder builder = ObservabilityContext.builder();
        NamespacedKeys.putNamespaced(builder, "request", StringKey.PATH, "/health");
        ObservabilityContext ctx = builder.build();
        assertNotNull(ctx.asMap());
    }

    @Test
    public void observabilityEventsEventFunctionIsReachable() {
        // ObservabilityEvents (was ObservabilityEventKt) must be accessible
        ObservabilityEvent event = ObservabilityEvents.event(TestEvent.TEST, builder -> {
            builder.message("from Java");
            return kotlin.Unit.INSTANCE;
        });
        assertEquals("from Java", event.getMessage());
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    enum TestEvent implements EventName {
        TEST;

        @Override
        public String getEventName() { return "test.event"; }

        @Override
        public String getName() { return name(); }
    }

    static class CustomSinkConfig implements SinkConfig {}
}
