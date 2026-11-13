package com.carrental.modules.ai.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.carrental.modules.ai.AiDiagnostics;
import com.carrental.modules.ai.AiProviderCapacity;
import com.carrental.modules.ai.AiProviderCapacity.Provider;
import com.carrental.modules.ai.AiRequestDeadline;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;

/** Driving estimates from a locally prepared OSRM graph, never a live-traffic promise. */
@org.springframework.stereotype.Component
public final class OsrmRoutingClient {
    public record Point(double latitude,double longitude) {
        public Point {
            if(!Double.isFinite(latitude)||!Double.isFinite(longitude)||Math.abs(latitude)>90||Math.abs(longitude)>180)
                throw new IllegalArgumentException("Tọa độ không hợp lệ.");
        }
        String coordinates(){return Double.toString(longitude)+","+Double.toString(latitude);}
    }
    public enum Status { VERIFIED, NO_ROUTE, UNAVAILABLE }
    public record Leg(double distanceMeters, double durationSeconds) {}
    public record Estimate(Status status, Double distanceMeters, Double durationSeconds, String attribution, List<Leg> legs) {
        public Estimate(Status status, Double distanceMeters, Double durationSeconds, String attribution) {
            this(status, distanceMeters, durationSeconds, attribution, List.of());
        }
    }
    private final URI base;
    private final ObjectMapper json;
    private final AiProviderCapacity capacity;
    private final AiDiagnostics diagnostics;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    @org.springframework.beans.factory.annotation.Autowired
    public OsrmRoutingClient(
            ObjectMapper json,
            ObjectProvider<AiProviderCapacity> capacity,
            AiDiagnostics diagnostics) {
        this(json, URI.create("http://127.0.0.1:5000"),
            capacity.getIfAvailable(AiProviderCapacity::new), diagnostics);
    }
    OsrmRoutingClient(ObjectMapper json){this(json, URI.create("http://127.0.0.1:5000"), new AiProviderCapacity(), new AiDiagnostics());}
    public OsrmRoutingClient(ObjectMapper json,URI base){this(json, base, new AiProviderCapacity(), new AiDiagnostics());}
    public OsrmRoutingClient(ObjectMapper json, URI base, AiProviderCapacity capacity){
        this(json, base, capacity, new AiDiagnostics());
    }
    public OsrmRoutingClient(ObjectMapper json, URI base, AiProviderCapacity capacity, AiDiagnostics diagnostics){
        if(!"http".equals(base.getScheme())||!"127.0.0.1".equals(base.getHost())||base.getUserInfo()!=null
            ||base.getQuery()!=null||base.getFragment()!=null||!List.of("","/").contains(base.getPath()))
            throw new IllegalArgumentException("OSRM phải là dịch vụ loopback.");
        this.json=json;this.base=base;this.capacity=capacity;this.diagnostics=diagnostics;
    }
    public Estimate route(List<Point> points){
        return route(points, AiRequestDeadline.start(AiRequestDeadline.Operation.ROUTE_PREVIEW));
    }
    public Estimate route(List<Point> points, AiRequestDeadline deadline){
        if(points==null||points.size()<2||points.size()>8||points.stream().anyMatch(p->p==null))
            throw new IllegalArgumentException("Tuyến cần từ 2 đến 8 tọa độ.");
        AiProviderCapacity.Permit permit;
        try {
            deadline.requireRemaining();
        } catch (AiRequestDeadline.DeadlineExceededException error) {
            record(deadline, AiDiagnostics.Cause.DEADLINE);
            return failed(Status.UNAVAILABLE);
        }
        try {
            permit = capacity.acquire(Provider.OSRM);
        } catch (AiProviderCapacity.CapacityExceededException error) {
            record(deadline, AiDiagnostics.Cause.CAPACITY);
            throw error;
        }
        AiDiagnostics.Cause cause = AiDiagnostics.Cause.UNKNOWN;
        try (permit) {
            String coordinates=String.join(";",points.stream().map(Point::coordinates).toList());
            Duration callTimeout=deadline.timeoutFor(Duration.ofSeconds(5));
            var request=HttpRequest.newBuilder(base.resolve("/route/v1/driving/"+coordinates+"?overview=false&steps=false&alternatives=false&radiuses="
                +String.join(";",points.stream().map(p->"1000").toList())))
                .timeout(callTimeout).GET().build();
            deadline.requireRemaining();
            var response=com.carrental.modules.ai.LocalHttp.send(client,request,Duration.ofSeconds(5),32768,deadline);
            deadline.requireRemaining();
            if(response.body().length()>32768) {
                cause=AiDiagnostics.Cause.RESPONSE_TOO_LARGE;
                return failed(Status.UNAVAILABLE);
            }
            if(response.status()!=200 && response.status()!=400) {
                cause=AiDiagnostics.Cause.PROVIDER_HTTP;
                return failed(Status.UNAVAILABLE);
            }
            com.fasterxml.jackson.databind.JsonNode body;
            try {
                body=json.readTree(response.body());
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                cause=response.status()==200?AiDiagnostics.Cause.ENVELOPE_INVALID:AiDiagnostics.Cause.PROVIDER_HTTP;
                return failed(Status.UNAVAILABLE);
            }
            if((response.status()==200||response.status()==400)
                    && ("NoRoute".equals(body.path("code").asText())||"NoSegment".equals(body.path("code").asText()))) {
                cause=AiDiagnostics.Cause.NO_ROUTE;
                return failed(Status.NO_ROUTE);
            }
            if(response.status()!=200) {
                cause=AiDiagnostics.Cause.PROVIDER_HTTP;
                return failed(Status.UNAVAILABLE);
            }
            var routes=body.path("routes");
            if(!"Ok".equals(body.path("code").asText())||!routes.isArray()||routes.size()!=1) {
                cause=AiDiagnostics.Cause.SCHEMA_INVALID;
                return failed(Status.UNAVAILABLE);
            }
            var route=routes.get(0);
            if(!route.path("distance").isNumber()||!route.path("duration").isNumber()) {
                cause=AiDiagnostics.Cause.SCHEMA_INVALID;
                return failed(Status.UNAVAILABLE);
            }
            double meters=route.path("distance").doubleValue(),seconds=route.path("duration").doubleValue();
            if(!Double.isFinite(meters)||!Double.isFinite(seconds)||meters<0||seconds<0) {
                cause=AiDiagnostics.Cause.SCHEMA_INVALID;
                return failed(Status.UNAVAILABLE);
            }
            List<Leg> legs = new java.util.ArrayList<>();
            var legsNode = route.path("legs");
            if (legsNode.isArray()) {
                if (legsNode.size() != points.size() - 1) {
                    cause=AiDiagnostics.Cause.SCHEMA_INVALID;
                    return failed(Status.UNAVAILABLE);
                }
                for (var legNode : legsNode) {
                    var distNode = legNode.path("distance");
                    var durNode = legNode.path("duration");
                    if (!distNode.isNumber() || !durNode.isNumber()) {
                        cause=AiDiagnostics.Cause.SCHEMA_INVALID;
                        return failed(Status.UNAVAILABLE);
                    }
                    double d = distNode.doubleValue();
                    double s = durNode.doubleValue();
                    if (!Double.isFinite(d) || d < 0 || !Double.isFinite(s) || s < 0) {
                        cause=AiDiagnostics.Cause.SCHEMA_INVALID;
                        return failed(Status.UNAVAILABLE);
                    }
                    legs.add(new Leg(d, s));
                }
            }
            cause=AiDiagnostics.Cause.SUCCESS;
            return new Estimate(Status.VERIFIED,meters,seconds,"© OpenStreetMap contributors; OSRM driving estimate",List.copyOf(legs));
        } catch(AiRequestDeadline.DeadlineExceededException error){cause=AiDiagnostics.Cause.DEADLINE;return failed(Status.UNAVAILABLE);}
        catch(InterruptedException error){cause=AiDiagnostics.Cause.INTERRUPTED;Thread.currentThread().interrupt();return failed(Status.UNAVAILABLE);}
        catch(Exception error){cause=AiDiagnostics.transportCause(error);return failed(Status.UNAVAILABLE);}
        finally {record(deadline,cause);}
    }
    private void record(AiRequestDeadline deadline, AiDiagnostics.Cause cause) {
        diagnostics.record(deadline.operation(), AiDiagnostics.Provider.OSRM, "NOT_PINNED", cause, deadline.elapsedNanos());
    }
    private static Estimate failed(Status status){return new Estimate(status,null,null,"© OpenStreetMap contributors",List.of());}
}
