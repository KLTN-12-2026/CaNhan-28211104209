package com.carrental.modules.operation.controller;

import com.carrental.modules.operation.service.OperationalReportService;
import com.carrental.security.Actor;
import com.carrental.exception.DomainException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/reports")
public class OperationalReportController {
 private final OperationalReportService reports;
 public OperationalReportController(OperationalReportService reports){this.reports=reports;}
 @GetMapping("/operations")
 public OperationalReportService.Report read(@AuthenticationPrincipal Actor actor,@RequestParam MultiValueMap<String,String> query){
  if(query.size()!=2||!query.containsKey("from")||!query.containsKey("to")||query.get("from").size()!=1||query.get("to").size()!=1)throw DomainException.bad("Cần đúng hai tham số from và to, mỗi tham số một giá trị.");
  return reports.read(actor,query.getFirst("from"),query.getFirst("to"));
 }
}
