package com.carrental.modules.ai.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;

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
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    @org.springframework.beans.factory.annotation.Autowired
    public OsrmRoutingClient(ObjectMapper json){this(json,URI.create("http://127.0.0.1:5000"));}
    OsrmRoutingClient(ObjectMapper json,URI base){
        if(!"http".equals(base.getScheme())||!"127.0.0.1".equals(base.getHost())||base.getUserInfo()!=null
            ||base.getQuery()!=null||base.getFragment()!=null||!List.of("","/").contains(base.getPath()))
            throw new IllegalArgumentException("OSRM phải là dịch vụ loopback.");
        this.json=json;this.base=base;
    }
    public Estimate route(List<Point> points){
        if(points==null||points.size()<2||points.size()>8||points.stream().anyMatch(p->p==null))
            throw new IllegalArgumentException("Tuyến cần từ 2 đến 8 tọa độ.");
        String coordinates=String.join(";",points.stream().map(Point::coordinates).toList());
        var request=HttpRequest.newBuilder(base.resolve("/route/v1/driving/"+coordinates+"?overview=false&steps=false&alternatives=false&radiuses="
            +String.join(";",points.stream().map(p->"1000").toList())))
            .timeout(Duration.ofSeconds(5)).GET().build();
        try {
            var response=com.carrental.modules.ai.LocalHttp.send(client,request,Duration.ofSeconds(5),32768);
            if(response.body().length()>32768)return failed(Status.UNAVAILABLE);
            var body=json.readTree(response.body());
            if((response.status()==200||response.status()==400) && ("NoRoute".equals(body.path("code").asText())||"NoSegment".equals(body.path("code").asText())))return failed(Status.NO_ROUTE);
            if(response.status()!=200)return failed(Status.UNAVAILABLE);
            var routes=body.path("routes");
            if(!"Ok".equals(body.path("code").asText())||!routes.isArray()||routes.size()!=1)return failed(Status.UNAVAILABLE);
            var route=routes.get(0);
            if(!route.path("distance").isNumber()||!route.path("duration").isNumber())return failed(Status.UNAVAILABLE);
            double meters=route.path("distance").doubleValue(),seconds=route.path("duration").doubleValue();
            if(!Double.isFinite(meters)||!Double.isFinite(seconds)||meters<0||seconds<0)return failed(Status.UNAVAILABLE);
            List<Leg> legs = new java.util.ArrayList<>();
            var legsNode = route.path("legs");
            if (legsNode.isArray()) {
                if (legsNode.size() != points.size() - 1) return failed(Status.UNAVAILABLE);
                for (var legNode : legsNode) {
                    var distNode = legNode.path("distance");
                    var durNode = legNode.path("duration");
                    if (!distNode.isNumber() || !durNode.isNumber()) return failed(Status.UNAVAILABLE);
                    double d = distNode.doubleValue();
                    double s = durNode.doubleValue();
                    if (!Double.isFinite(d) || d < 0 || !Double.isFinite(s) || s < 0) return failed(Status.UNAVAILABLE);
                    legs.add(new Leg(d, s));
                }
            }
            return new Estimate(Status.VERIFIED,meters,seconds,"© OpenStreetMap contributors; OSRM driving estimate",List.copyOf(legs));
        } catch(InterruptedException error){Thread.currentThread().interrupt();return failed(Status.UNAVAILABLE);}
        catch(Exception error){return failed(Status.UNAVAILABLE);}
    }
    private static Estimate failed(Status status){return new Estimate(status,null,null,"© OpenStreetMap contributors",List.of());}
}
