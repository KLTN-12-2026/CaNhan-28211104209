package com.carrental.modules.booking.service;
import com.carrental.modules.branch.service.BranchService;
import java.util.List;
import org.springframework.stereotype.Service;

/** Typed rental pickup/return catalog for consumers outside the branch module. */
@Service
public class RentalBranchCatalog {
    public record Item(String id,String name) {}
    private final BranchService branches;
    public RentalBranchCatalog(BranchService branches){this.branches=branches;}
    public List<Item> active(){return branches.list().stream().filter(b->"ACTIVE".equals(b.status())).map(b->new Item(b.id(),b.name())).toList();}
}
