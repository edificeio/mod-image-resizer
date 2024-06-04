package fr.wseduc.resizer.metrics;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.micrometer.backends.BackendRegistries;

public class MicrometerImageResizerMetricsRecorder implements ImageResizerMetricsRecorder {
    private final Map<ImageResizerAction, Timer> timers;
    private final Map<ImageResizerAction, Counter> errorCounters;

    public MicrometerImageResizerMetricsRecorder(final Configuration configuration, 
                                                 final Supplier<Number> nbCurrentTasks,
                                                 final Supplier<Number> nbPendingTasks,
                                                 final int maxConcurrentTasks) {
        final MeterRegistry registry = BackendRegistries.getDefaultNow();
        if(registry == null) {
            throw new IllegalStateException("micrometer.registries.empty");
        }
        timers = new HashMap<>();
        errorCounters = new HashMap<>();
        for (final ImageResizerAction value : ImageResizerAction.values()) {
            final String action = value.name();
            final Timer.Builder builder = Timer.builder("image.resizer.process.time")
                .tag("action", action)
                .description("time to execute the action " + action);
            if(configuration.sla.isEmpty()) {
                builder.publishPercentileHistogram()
                       .maximumExpectedValue(Duration.ofSeconds(30L));
            } else {
                builder.sla(configuration.sla.toArray(new Duration[0]));
            }
            timers.put(value, builder.register(registry));
            errorCounters.put(value, Counter.builder("image.resizer.error")
                            .description("number of errors while processing " + action)
                            .tag("action", action)
                            .register(registry));
        }
        Gauge.builder("image.resizer.concurrent.actions.number", nbCurrentTasks)
        .description("Number of concurrent actions")
        .register(registry);
        Gauge.builder("image.resizer.pending.actions.number", nbPendingTasks)
        .description("Number of pending actions")
        .register(registry);
    }

    @Override
    public void onActionEnd(ImageResizerAction action, long duration) {
        timers.get(action).record(duration, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onError(ImageResizerAction action) {
        errorCounters.get(action).increment();
    }

    public static class Configuration {
        private final List<Duration> sla;

        public Configuration(final List<Duration> sla) {
            this.sla = sla;
        }

        /**
         * Create the recorder's configuration from the attribute {@code metrics} of the parameter {@code conf} (or send
         * back a default configuration if conf is {@code null} or if it has no {@code metrics} field.
         * @param conf an object with the following keys :
         *          <ul>
         *               <li>sla, an ordered list of longs whose values are the bucket boundaries for the exported Timer which records the time
         *               spent waiting for a response from OVH</li>
         *           </ul>
         * @return The built configuration
         */
        public static Configuration fromJson(final JsonObject conf) {
            final List<Duration> sla;
            if(conf == null || !conf.containsKey("metrics")) {
                sla = Collections.emptyList();
            } else {
                final JsonObject metrics = conf.getJsonObject("metrics");
                sla = metrics.getJsonArray("sla", new JsonArray()).stream()
                    .mapToLong(long.class::cast)
                    .sorted()
                    .mapToObj(Duration::ofMillis)
                    .collect(Collectors.toList());
            }
            return new Configuration(sla);
        }
    }

}
