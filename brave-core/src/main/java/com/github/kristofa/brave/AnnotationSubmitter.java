package com.github.kristofa.brave;

import com.github.kristofa.brave.internal.Nullable;
import com.twitter.zipkin.gen.Annotation;
import com.twitter.zipkin.gen.BinaryAnnotation;
import com.twitter.zipkin.gen.Endpoint;
import com.twitter.zipkin.gen.Span;

import static com.github.kristofa.brave.internal.Util.checkNotNull;

/**
 * Used to submit application specific annotations.
 *
 * @author kristof
 */
public abstract class AnnotationSubmitter {

    /**
     * This interface is used to make the implementation to AnnotationSubmitter.currentTimeMicroseconds() contextual.
     * The clock is defined by the subclass's implementation of the `clock()` method.
     * A DefaultClock implementation is provided that simply returns `System.currentTimeMillis() * 1000`.
     */
    public interface Clock {
        /**
         * Epoch microseconds used for {@link zipkin.Span#timestamp} and {@link zipkin.Annotation#timestamp}.
         *
         * <p>This should use the most precise value possible. For example, {@code gettimeofday} or multiplying
         * {@link System#currentTimeMillis} by 1000.
         *
         * <p>See <a href="http://zipkin.io/pages/instrumenting.html">Instrumenting a service</a> for more.
         */
        long currentTimeMicroseconds();
    }

    static AnnotationSubmitter create(SpanAndEndpoint spanAndEndpoint) {
        return new AnnotationSubmitterImpl(spanAndEndpoint, DefaultClock.INSTANCE);
    }

    public static AnnotationSubmitter create(SpanAndEndpoint spanAndEndpoint, Clock clock) {
        return new AnnotationSubmitterImpl(spanAndEndpoint, clock);
    }

    abstract SpanAndEndpoint spanAndEndpoint();

    /** The implementation of Clock to use.
     * See {@link com.github.kristofa.brave.AnnotationSubmitter#currentTimeMicroseconds}
     * and {@link com.github.kristofa.brave.AnnotationSubmitter#Clock}
     **/
    abstract Clock clock();

    /**
     * Associates an event that explains latency with the current system time.
     *
     * @param value A short tag indicating the event, like "finagle.retry"
     */
    public void submitAnnotation(String value) {
        Span span = spanAndEndpoint().span();
        if (span != null) {
            Annotation annotation = Annotation.create(
                currentTimeMicroseconds(),
                value,
                spanAndEndpoint().endpoint()
            );
            addAnnotation(span, annotation);
        }
    }

    /**
     * Associates an event that explains latency with a timestamp.
     *
     * <p/> This is an alternative to {@link #submitAnnotation(String)}, when
     * you have a timestamp more precise or accurate than {@link System#currentTimeMillis()}.
     *
     * @param value     A short tag indicating the event, like "finagle.retry"
     * @param timestamp microseconds from epoch
     */
    public void submitAnnotation(String value, long timestamp) {
        Span span = spanAndEndpoint().span();
        if (span != null) {
            Annotation annotation = Annotation.create(
                timestamp,
                value,
                spanAndEndpoint().endpoint()
            );
            addAnnotation(span, annotation);
        }
    }

    /** This adds an annotation that corresponds with {@link Span#getTimestamp()} */
    void submitStartAnnotation(String annotationName) {
        Span span = spanAndEndpoint().span();
        if (span != null) {
            Annotation annotation = Annotation.create(
                currentTimeMicroseconds(),
                annotationName,
                spanAndEndpoint().endpoint()
            );
            synchronized (span) {
                span.setTimestamp(annotation.timestamp);
                span.addToAnnotations(annotation);
            }
        }
    }

    /**
     * This adds an annotation that corresponds with {@link Span#getDuration()}, and sends the span
     * for collection.
     *
     * @return true if a span was sent for collection.
     */
    boolean submitEndAnnotation(String annotationName, SpanCollector spanCollector) {
        Span span = spanAndEndpoint().span();
        if (span == null) {
          return false;
        }
        Annotation annotation = Annotation.create(
            currentTimeMicroseconds(),
            annotationName,
            spanAndEndpoint().endpoint()
        );
        synchronized (span) {
            span.addToAnnotations(annotation);
            Long timestamp = span.getTimestamp();
            if (timestamp != null) {
                span.setDuration(annotation.timestamp - timestamp);
            }
        }
        spanCollector.collect(span);
        return true;
    }

    /**
     * Internal api for submitting an address. Until a naming function is added, this coerces null
     * {@code serviceName} to "unknown", as that's zipkin's convention.
     *
     * @param ipv4        ipv4 host address as int. Ex for the ip 1.2.3.4, it would be (1 << 24) | (2 << 16) | (3 << 8) | 4
     * @param port        Port for service
     * @param serviceName Name of service. Should be lowercase and not empty. {@code null} will coerce to "unknown", as that's zipkin's convention.
     */
    void submitAddress(String key, int ipv4, int port, @Nullable String serviceName) {
        Span span = spanAndEndpoint().span();
        if (span != null) {
            serviceName = serviceName != null ? serviceName : "unknown";
            Endpoint endpoint = Endpoint.create(serviceName, ipv4, port);
            BinaryAnnotation ba = BinaryAnnotation.address(key, endpoint);
            addBinaryAnnotation(span, ba);
        }
    }

    /**
     * Binary annotations are tags applied to a Span to give it context. For
     * example, a key "your_app.version" would let you lookup spans by version.
     *
     * @param key Name used to lookup spans, such as "your_app.version"
     * @param value String value, should not be <code>null</code>.
     */
    public void submitBinaryAnnotation(String key, String value) {
        Span span = spanAndEndpoint().span();
        if (span != null) {
            BinaryAnnotation ba = BinaryAnnotation.create(key, value, spanAndEndpoint().endpoint());
            addBinaryAnnotation(span, ba);
        }
    }

    /**
     * Submits a binary (key/value) annotation with int value.
     *
     * @param key Key, should not be blank.
     * @param value Integer value.
     */
    public void submitBinaryAnnotation(String key, int value) {
        // Zipkin v1 UI and query only support String annotations.
        submitBinaryAnnotation(key, String.valueOf(value));
    }

    long currentTimeMicroseconds() {
        return clock().currentTimeMicroseconds();
    }

    private void addAnnotation(Span span, Annotation annotation) {
        synchronized (span) {
            span.addToAnnotations(annotation);
        }
    }

    private void addBinaryAnnotation(Span span, BinaryAnnotation ba) {
        synchronized (span) {
            span.addToBinary_annotations(ba);
        }
    }

    AnnotationSubmitter() {
    }

    private static final class AnnotationSubmitterImpl extends AnnotationSubmitter {

        private final SpanAndEndpoint spanAndEndpoint;
        private final Clock clock;

        private AnnotationSubmitterImpl(SpanAndEndpoint spanAndEndpoint, Clock clock) {
            this.spanAndEndpoint = checkNotNull(spanAndEndpoint, "Null spanAndEndpoint");
            this.clock = clock;
        }

        @Override
        SpanAndEndpoint spanAndEndpoint() {
            return spanAndEndpoint;
        }

        @Override
        protected Clock clock() {
            return clock;
        }

    }

    static final class DefaultClock implements Clock {
        static final Clock INSTANCE = new DefaultClock();
        private DefaultClock() {}
        @Override
        public long currentTimeMicroseconds() {
            return System.currentTimeMillis() * 1000;
        }
    }
}
