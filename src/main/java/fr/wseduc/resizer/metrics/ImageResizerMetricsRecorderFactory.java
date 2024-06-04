package fr.wseduc.resizer.metrics;

import java.util.function.Supplier;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.metrics.MetricsOptions;

public class ImageResizerMetricsRecorderFactory {
    private static JsonObject config;
    private static MetricsOptions metricsOptions;
    private static ImageResizerMetricsRecorder metricsRecorder;
    private ImageResizerMetricsRecorderFactory() {}

    public static void init(final Vertx vertx, final JsonObject config) {
        ImageResizerMetricsRecorderFactory.config = config;
        final String metricsOptionsName = "metricsOptions";
        if(config.getJsonObject(metricsOptionsName) == null) {
            final String metricsOptions = (String) vertx.sharedData().getLocalMap("server").get(metricsOptionsName);
            if(metricsOptions == null){
                ImageResizerMetricsRecorderFactory.metricsOptions = new MetricsOptions().setEnabled(false);
            }else{
                ImageResizerMetricsRecorderFactory.metricsOptions = new MetricsOptions(new JsonObject(metricsOptions));
            }
        } else {
            metricsOptions = new MetricsOptions(config.getJsonObject(metricsOptionsName));
        }
    }


    /**
     * @return The backend to record metrics. If metricsOptions is defined in the configuration then the backend used
     * is MicroMeter. Otherwise a dummy registrar is returned and it collects nothing.
     */
    public static ImageResizerMetricsRecorder getInstance(final int maxConcurrentTasks, final Supplier<Number> nbCurrentTasks, final Supplier<Number> nbPendingTasks) {
        if(metricsRecorder == null) {
            if(metricsOptions == null) {
                throw new IllegalStateException("sms.metricsrecorder.factory.not.set");
            }
            if(metricsOptions.isEnabled()) {
                metricsRecorder = new MicrometerImageResizerMetricsRecorder(
                    MicrometerImageResizerMetricsRecorder.Configuration.fromJson(config),
                    nbCurrentTasks,
                    nbPendingTasks,
                    maxConcurrentTasks);
            } else {
                metricsRecorder = new ImageResizerMetricsRecorder.NoopImageResizerMetricsRecorder();
            }
        }
        return metricsRecorder;
    }

}
