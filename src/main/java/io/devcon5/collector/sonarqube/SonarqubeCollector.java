package io.devcon5.collector.sonarqube;

import io.devcon5.collector.ResultHandler;
import io.devcon5.collector.ServiceClient;
import io.devcon5.collector.ServiceClientFactory;
import io.devcon5.measure.BinaryEncoding;
import io.devcon5.measure.Digester;
import io.devcon5.measure.Encoder;
import io.devcon5.measure.Measurement;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.slf4j.Logger;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.reducing;
import static org.slf4j.LoggerFactory.getLogger;

public class SonarqubeCollector extends AbstractVerticle {

    private static final Logger LOG = getLogger(SonarqubeCollector.class);

    private Encoder<Buffer> encoder;
    private WebClient webclient;
    private ResultHandler<Measurement[]> resultHandler;

    @Override
    public void start() throws Exception {

        this.encoder = BinaryEncoding.encoder();
        this.webclient = WebClient.create(vertx);
        this.resultHandler = ResultHandler.create(Measurement[].class)
                                          .onSuccess(ms -> vertx.eventBus()
                                                                .publish(Digester.DIGEST_ADDR, encoder.encode(ms)))
                                          .orElse(t -> LOG.error("Error fetching metrics", t));

        //default interval for all servers
        final long interval = config().getLong("interval", 60000L);

        final ServiceClientFactory factory = ServiceClientFactory.newFactory(webclient);

        config().getJsonArray("servers")
                .stream()
                .map(JsonObject.class::cast)
                .forEach(config -> vertx.setPeriodic(config().getLong("interval", interval),
                        pollStatus(factory.createClient(config))));

    }

    @Override
    public void stop() throws Exception {
        this.webclient.close();
    }

    private Handler<Long> pollStatus(ServiceClient client) {

        return l -> {
            client.newGetRequest("/api/components/search?qualifiers=BRC,DIR,FIL,TRK,UTS&ps=100000")
                  .send(this::processProjects);
            client.newGetRequest("/api/ce/activity")
                  .send(this::processCeActivity);
        };
    }

    private void processCeActivity(AsyncResult<HttpResponse<Buffer>> resp) {
        resultHandler.accept(resp.map(HttpResponse::bodyAsJsonObject)
                                 .map(body -> body.getJsonArray("tasks")
                                                  .stream()
                                                  .map(JsonObject.class::cast)
                                                  .map(o -> Measurement.builder()
                                                                       .name("tasks")
                                                                       .value("type", o.getString("type"))
                                                                       .value("component", o.getString("componentKey"))
                                                                       .value("status", o.getString("status"))
                                                                       .value("submitter", o.getString("submitterLogin"))
                                                                       .value("executionTime", o.getInteger("executionTimeMs", 0))
                                                                       .value("type", o.getString("type"))
                                                                       .build())
                                                  .toArray(Measurement[]::new)));

    }


    private void processProjects(AsyncResult<HttpResponse<Buffer>> resp) {
        resultHandler.accept(resp.map(HttpResponse::bodyAsJsonObject)
                                 .map(body -> body.getJsonArray("components")
                                                  .stream()
                                                  .map(JsonObject.class::cast)
                                                  .map(o -> o.getString("qualifier"))
                                                  .map(this::mapComponentQualifier)
                                                  .collect(groupingBy(identity(),
                                                          reducing(0, o -> 1, Integer::sum))))
                                 .map(stats -> stats.entrySet()
                                                    .stream()
                                                    .map(e -> Measurement.builder()
                                                                         .name("components")
                                                                         .tag("qualifier", e.getKey())
                                                                         .value("count", e.getValue())
                                                                         .build())
                                                    .toArray(Measurement[]::new)));
    }

    private String mapComponentQualifier(String qualifier) {
        switch (qualifier) {
            case "TRK":
                return "projects";
            case "BRC":
                return "branches";
            case "DIR":
                return "directories";
            case "FIL":
                return "files";
            case "UTS":
                return "tests";
            default:
                return "unknown";
        }
    }
}
