package org.observability;

import com.couchbase.client.core.env.LoggerConfig;
import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.*;
import com.couchbase.client.java.codec.RawStringTranscoder;
import com.couchbase.client.java.env.ClusterEnvironment;
import com.couchbase.client.java.kv.GetOptions;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.client.java.query.QueryResult;
import com.couchbase.client.java.query.QueryScanConsistency;
import com.couchbase.client.tracing.opentelemetry.OpenTelemetryRequestSpan;
import com.couchbase.client.tracing.opentelemetry.OpenTelemetryRequestTracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.couchbase.client.java.kv.GetOptions.getOptions;
import static com.couchbase.client.java.query.QueryOptions.queryOptions;

/**
 * Example of Jaeger integration with couchbase.
 *
 * @author abhijeetbehera
 */
public class Main {

    private static final String OTEL_COLLECTOR_ENDPOINT = "http://localhost:4317";
//    private static final String OTEL_COLLECTOR_ENDPOINT = "http://performance.sdk.couchbase.com:4317";
    //http://localhost:16686  http://localhost:14250

    private static final String SERVICE_NAME = "observability-service";
    private static final String connectionString = "couchbases://cb.qk-hjde0zwax7hr3.cloud.couchbase.com";
    private static final String username = "abhijeet";
    private static final String password = "Password@P1";
    public static final String BUCKET = "travel-sample";
    public static final String SCOPE = "_default";
    public static final String COLLECTION = "_default";

    public static void main(String[] args) {

        Cluster cluster = getJaegerTrace();

/*        Logger rootLogger = Logger.getLogger("com.couchbase");
        rootLogger.setLevel(Level.OFF); // or INFO or whatever*/


//        Logger.getLogger("com.couchbase.client").setLevel(Level.OFF);
        // Get a bucket reference
        Bucket bucket = cluster.bucket(BUCKET);
        bucket.waitUntilReady(Duration.ofSeconds(10));
        Scope scope = bucket.scope(SCOPE);
        Collection collection = scope.collection(COLLECTION);

        bulkReadCollectionReactive(cluster, bucket, scope, collection);
    }


    private static Cluster getJaegerTrace() {

        OpenTelemetry openTelemetry = getOpenTelemetryCouchbaseMethod();

        Cluster cluster = Cluster.connect(connectionString, ClusterOptions.clusterOptions(username, password)
                .environment(env -> {
                    // Provide the OpenTelemetry object to the Couchbase SDK
                    env.requestTracer(OpenTelemetryRequestTracer.wrap(openTelemetry));
                }));

        return cluster;
    }

    private static OpenTelemetry getOpenTelemetryCouchbaseMethod() {

        // Set the OpenTelemetry SDK's SdkTracerProvider
        SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
                .setResource(Resource.getDefault()
                        .merge(Resource.builder()
                                // An OpenTelemetry service name generally reflects the name of your microservice,
                                // e.g. "shopping-cart-service"
                                .put("service.name", SERVICE_NAME)
                                .build()))
                .addSpanProcessor(BatchSpanProcessor.builder(OtlpGrpcSpanExporter.builder()
                        .setEndpoint(OTEL_COLLECTOR_ENDPOINT)
                        .build()).build())
                .setSampler(Sampler.alwaysOn())
                .build();

      // Set the OpenTelemetry SDK's OpenTelemetry
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(sdkTracerProvider)
                .buildAndRegisterGlobal();
        return openTelemetry;
    }

    private static void bulkReadCollectionReactive(Cluster cluster, Bucket bucket, Scope scope, Collection collection) {
        try {

            ReactiveCluster reactiveCluster = cluster.reactive();
            ReactiveBucket reactiveBucket = bucket.reactive();
            ReactiveScope reactiveScope = scope.reactive();
            ReactiveCollection reactiveCollection = collection.reactive();

            var query = "SELECT meta(c).id FROM `travel-sample`.`_default`._default c WHERE meta(c).id like '%0%' limit 10000";

            QueryResult result = cluster.query(query,
                    queryOptions().adhoc(false).maxParallelism(4).scanConsistency(QueryScanConsistency.NOT_BOUNDED).metrics(false));
            List<String> docsToFetch = result.rowsAsObject().stream().map(s -> s.getString("id")).collect(Collectors.toList());

            long startTime = System.currentTimeMillis();

          /*  List<GetResult> results = Flux.fromIterable(docsToFetch)
                    .flatMap(key -> reactiveCollection.get(key, GetOptions.getOptions().transcoder(RawStringTranscoder.INSTANCE)).onErrorResume(e -> Mono.empty())).collectList().block();
          */

            //If you want to set a parent for a SDK request, you can do it in the respective *Options:
            //getOptions().parentSpan(OpenTelemetryRequestSpan.wrap(parentSpan))

/*            Span parentSpan = getTracer(openTelemetry).spanBuilder("parentSpan").setNoParent().startSpan();
            System.out.println("In parent method. TraceID : "+ parentSpan.getSpanContext().getTraceId());*/

            // Perform bulk read by controlling number of threads in parallel function
            List<GetResult>  results =  Flux.fromIterable(docsToFetch)
                    .parallel(100)
                    .runOn(Schedulers.boundedElastic())
                    .flatMap(key -> reactiveCollection.get(key, getOptions().transcoder(RawStringTranscoder.INSTANCE))
                            .onErrorResume(e -> Mono.empty()))
                    .sequential()
                    .collectList()
                    .block();


            long networkLatency = System.currentTimeMillis() - startTime;
            System.out.println("Total TIME including Network latency in ms: " + networkLatency);

            System.out.println("Total Docs: " + results.size());

            String returned = results.get(0).contentAs(String.class);

            /*sleep for a bit to let everything settle*/
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            System.out.println("Done" + returned);
        } catch (DocumentNotFoundException ex) {
            System.out.println("Document not found!");
        }
    }

    private static void setSpan(Tracer tracer, OpenTelemetry openTelemetry) {
        //        Span parentSpan = tracer.spanBuilder("/").setSpanKind(SpanKind.CLIENT).startSpan();
        /*an automated way to propagate the parent span on the current thread*/
        for (int index = 0; index < 3; index++) {
            /*create a span by specifying the name of the span. The start and end time of the span is automatically set by the OpenTelemetry SDK*/
            Span parentSpan = tracer.spanBuilder("parentSpan").setNoParent().startSpan();
            System.out.println("In parent method. TraceID : {}"+ parentSpan.getSpanContext().getTraceId());

            /*put the span into the current Context*/
            try (io.opentelemetry.context.Scope scope = parentSpan.makeCurrent()) {

                /*annotate the span with attributes specific to the represented operation, to provide additional context*/
                parentSpan.setAttribute("parentIndex", index);
            } catch (Throwable throwable) {
                parentSpan.setStatus(StatusCode.ERROR, "Something wrong with the parent span");
            } finally {
                /*closing the scope does not end the span, this has to be done manually*/
                parentSpan.end();
            }
        }

        /*sleep for a bit to let everything settle*/
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static Tracer getTracer(OpenTelemetry openTelemetry) {
        Tracer tracer = openTelemetry.getTracer("org.observability.Main");
//        Tracer tracer = openTelemetry.getTracerProvider().get("opentel-observability", "1.0");
        return tracer;
    }
}