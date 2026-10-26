package com.carrental.modules.ai.routing;
import com.carrental.exception.DomainException;
import com.carrental.modules.location.service.LocationService;
import com.carrental.security.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LocationRouteService {
    private final LocationService locations;
    private final OsrmRoutingClient routing;
    private final boolean enabled;
    private final java.util.concurrent.Semaphore capacity=new java.util.concurrent.Semaphore(2);
    @org.springframework.beans.factory.annotation.Autowired
    public LocationRouteService(LocationService locations,ObjectMapper json,@Value("${app.ai-routing-enabled:false}") boolean enabled){
        this(locations,new OsrmRoutingClient(json),enabled);
    }
    LocationRouteService(LocationService locations,OsrmRoutingClient routing,boolean enabled){this.locations=locations;this.routing=routing;this.enabled=enabled;}
    public OsrmRoutingClient.Estimate preview(Actor actor,List<String> ids){
        actor.require("USER");
        if(!enabled)throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,"Tính năng xem tuyến chưa được bật.");
        if(ids==null||ids.size()<2||ids.size()>8||ids.stream().anyMatch(id->id==null||id.isBlank())||ids.stream().distinct().count()!=ids.size())
            throw DomainException.bad("Chọn từ 2 đến 8 địa điểm khác nhau.");
        Map<String,LocationService.PublicView> catalog=locations.activeCatalog(actor).stream().collect(Collectors.toMap(LocationService.PublicView::id,Function.identity()));
        var points=ids.stream().map(id->{
            var item=catalog.get(id);if(item==null)throw DomainException.bad("Địa điểm không còn hoạt động hoặc không tồn tại.");
            return new OsrmRoutingClient.Point(item.latitude().doubleValue(),item.longitude().doubleValue());
        }).toList();
        if(!capacity.tryAcquire())throw new DomainException(HttpStatus.TOO_MANY_REQUESTS,"Đang xử lý nhiều tuyến; vui lòng thử lại.");
        try{return routing.route(points);}finally{capacity.release();}
    }
}
