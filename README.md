# ObservabilityIntegration with couchbase in Java 
 Integrate OpenTelemetry and collect traces in Jaeger

Step 1: Install Jaegerin docker so you do not need to worry about ports.

docker run -d --name jaeger \
  -e COLLECTOR_ZIPKIN_HOST_PORT=:9411 \
  -e COLLECTOR_OTLP_ENABLED=true \
  -p 6831:6831/udp \
  -p 6832:6832/udp \
  -p 5778:5778 \
  -p 16686:16686 \
  -p 4317:4317 \
  -p 4318:4318 \
  -p 14250:14250 \
  -p 14268:14268 \
  -p 14269:14269 \
  -p 9411:9411 \
  jaegertracing/all-in-one:1.41


Step 2: Copy the open telemetry code in your app and make sure to set OTEL endpoint to port 4317 in code 
(**Note**: This is super important or else you will get error because you need to set port for exporting OTLP data and OTLP port is 4317
 
 // Set the OpenTelemetry SDK's SdkTracerProvider
  String SERVICE_NAME = "observability-service";
        SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
                .setResource(Resource.getDefault()
                        .merge(Resource.builder()
                                // An OpenTelemetry service name generally reflects the name of your microservice,
                                // e.g. "shopping-cart-service"
                                .put("service.name", SERVICE_NAME)
                                .build()))
                .addSpanProcessor(BatchSpanProcessor.builder(OtlpGrpcSpanExporter.builder()
                        .setEndpoint("http://localhost:4317")
                        .build()).build())
                .setSampler(Sampler.alwaysOn())
                .build();
				
Step 3: Run you java program or application and then navigate to http://localhost:16686 to review the performance details.(Note: Select your service name from drop down in Jaeger. In this example service name is "observability-service")

Jaeger port details could be found here:
https://www.jaegertracing.io/docs/1.41/getting-started/

Common Errors below if collector endpoint port not set properly and when running Jaeger in mac directly instead of docker:

ERROR 1:
SEVERE: Failed to export spans. The request could not be executed. Full error message: Failed to connect to localhost/[0:0:0:0:0:0:0:1]:4317
Jan 23, 2023 8:41:04 PM io.opentelemetry.sdk.internal.ThrottlingLogger log
SEVERE: Too many log messages detected. Will only log once per minute from now on.
Jan 23, 2023 8:41:04 PM io.opentelemetry.sdk.internal.ThrottlingLogger doLog
SEVERE: Failed to export spans. The request could not be executed. Full error message: Failed to connect to localhost/[0:0:0:0:0:0:0:1]:4317

OR

ERROR 2:
Jan 23, 2023 8:43:54 PM io.opentelemetry.sdk.internal.ThrottlingLogger doLog
SEVERE: Failed to export spans. The request could not be executed. Full error message: stream was reset: NO_ERROR