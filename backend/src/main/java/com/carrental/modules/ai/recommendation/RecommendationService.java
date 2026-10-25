package com.carrental.modules.ai.recommendation;

import com.carrental.exception.DomainException;
import com.carrental.modules.booking.service.VehicleSearchService;
import com.carrental.security.Actor;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import static com.carrental.modules.ai.recommendation.RecommendationModels.*;

@Service
public class RecommendationService {
    public static final String VERSION = "rules-2026-10-18-v1";
    private final VehicleSearchService search;
    private final Clock clock;
    private final boolean enabled;
    public RecommendationService(VehicleSearchService search, Clock clock,
                                 @Value("${app.ai-recommendation-enabled:false}") boolean enabled) {
        this.search=search; this.clock=clock; this.enabled=enabled;
    }
    public Response recommend(Actor actor, Request request) {
        actor.require("USER");
        if (!enabled) throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,"Gợi ý xe chưa được bật.");
        if (request==null || request.requestId()==null || request.requestId().isBlank()
                || request.requestId().length()>64 || !request.requestId().matches("[A-Za-z0-9_-]+"))
            throw DomainException.bad("Mã yêu cầu không hợp lệ.");
        Criteria c=request.criteria();
        if(c==null) return response(request,"NEEDS_CLARIFICATION",List.of("criteria"),List.of(),clock.instant());
        if(c.passengers()!=null && (c.passengers()<2 || c.passengers()>9)
                || c.budgetVndTotal()!=null && (c.budgetVndTotal()<0 || c.budgetVndTotal()>9007199254740991L))
            throw DomainException.bad("Số người từ 2–9 gồm tài xế; ngân sách phải là số nguyên VND hợp lệ.");
        for(String id:Arrays.asList(c.pickupBranchId(),c.returnBranchId()))
            if(id!=null && (id.isBlank() || id.length()>36)) throw DomainException.bad("Chi nhánh không hợp lệ.");
        Preferences p=c.preferences();
        if(p!=null && (p.transmission()!=null && !Set.of("AUTO","MANUAL").contains(p.transmission())
                || p.amenityIds()!=null && (p.amenityIds().size()>20 || p.amenityIds().stream().anyMatch(id->id==null || id.isBlank() || id.length()>36)
                || new HashSet<>(p.amenityIds()).size()!=p.amenityIds().size())))
            throw DomainException.bad("Sở thích không hợp lệ.");
        List<String> missing=new ArrayList<>();
        if(c.pickupDate()==null)missing.add("pickupDate"); if(c.returnDate()==null)missing.add("returnDate");
        if(c.pickupBranchId()==null)missing.add("pickupBranchId"); if(c.returnBranchId()==null)missing.add("returnBranchId");
        if(c.passengers()==null)missing.add("passengers"); if(c.budgetVndTotal()==null)missing.add("budgetVndTotal");
        if(!missing.isEmpty())return response(request,"NEEDS_CLARIFICATION",missing,List.of(),clock.instant());
        var facts=search.search(new VehicleSearchService.Input(null,null,c.pickupBranchId(),c.returnBranchId(),
                c.budgetVndTotal(),c.passengers(),null,null,null,null,c.pickupDate(),c.returnDate()));
        var ranked=facts.items().stream().map(item->rank(c,item)).sorted(
                Comparator.comparingInt(Item::score).reversed().thenComparing(Item::vehicleId)).limit(20).toList();
        return response(request,ranked.isEmpty()?"NO_MATCH":"OK",List.of(),ranked,facts.checkedAt());
    }
    private Response response(Request r,String status,List<String> missing,List<Item> items,java.time.Instant at){
        return new Response(r.requestId(),status,VERSION,r.criteria(),List.copyOf(missing),items,at,
                List.of("Kết quả theo quy tắc, không giữ chỗ. Giá và lịch được kiểm tra lại khi đặt xe."));
    }
    static Item rank(Criteria c,VehicleSearchService.Item item){
        var v=item.vehicle();var p=c.preferences();List<String> reasons=new ArrayList<>();
        reasons.add("WITHIN_TOTAL_BUDGET"); reasons.add("ENOUGH_SEATS");
        int wanted=0,matched=0;
        if(p!=null && p.transmission()!=null){wanted++;if(p.transmission().equals(v.transmission())){matched++;reasons.add("TRANSMISSION_MATCH");}}
        if(p!=null && p.amenityIds()!=null)for(String id:p.amenityIds()){
            wanted++;if(v.amenities().stream().anyMatch(a->a.id().equals(id))){matched++;reasons.add("AMENITY_MATCH:"+id);}
        }
        double budget=c.budgetVndTotal()==0 ? 1 : 1-(double)item.total()/c.budgetVndTotal();
        int score=(int)Math.round(40*budget+25*(double)c.passengers()/v.seats()+35*(wanted==0?1:(double)matched/wanted));
        return new Item(v.id(),v.name(),v.seats(),v.transmission(),item.rentalDays(),item.rentalTotal(),
                item.oneWayFee(),item.total(),score,List.copyOf(reasons));
    }
}
