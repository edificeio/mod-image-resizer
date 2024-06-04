package fr.wseduc.resizer.metrics;

import static fr.wseduc.webutils.Utils.isEmpty;

public interface ImageResizerMetricsRecorder {
    void onError(final ImageResizerAction action);
    void onActionStart(final ImageResizerAction action);
    void onActionEnd(final ImageResizerAction action, final long duration);

    public enum ImageResizerAction {
        resize,
        crop,
        resizeMultiple,
        compress,
        unknown;
        public static ImageResizerAction fromString(final String value) {
            if(isEmpty(value)) {
                return unknown;
            }
            try {
                return ImageResizerAction.valueOf(value);
            } catch(Exception e) {
                return unknown;
            }
        }
    }
    class NoopImageResizerMetricsRecorder implements ImageResizerMetricsRecorder {

        @Override
        public void onActionEnd(ImageResizerAction action, long duration) {
            // Do nothing in this implementation
        }

        @Override
        public void onError(ImageResizerAction action) {
            // Do nothing in this implementation
        }

        @Override
        public void onActionStart(final ImageResizerAction action) {
            // Do nothing in this implementation
        }

    }

}
