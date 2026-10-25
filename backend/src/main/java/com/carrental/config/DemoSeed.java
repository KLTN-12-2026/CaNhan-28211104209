package com.carrental.config;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.UUID;
@Component @ConditionalOnProperty(name="app.seed-enabled",havingValue="true")
public class DemoSeed implements ApplicationRunner {
 private final JdbcTemplate jdbc;private final PasswordEncoder passwords;private final String password;
 public DemoSeed(JdbcTemplate j,PasswordEncoder p,@Value("${app.seed-password}")String s){jdbc=j;passwords=p;password=s;}
 @Transactional public void run(ApplicationArguments args){
  if(password.length()<12)throw new IllegalStateException("SEED_PASSWORD cần tối thiểu 12 ký tự. Seed chỉ dùng môi trường phát triển.");
  if(jdbc.queryForObject("select count(*) from users",Long.class)>0)return;
  String hash=passwords.encode(password);
  for(String[] a:new String[][]{{"demo-user","user@example.test","Khách mẫu","USER"},{"demo-staff-dn","staff.dn@example.test","Staff Đà Nẵng","STAFF"},{"demo-staff-hn","staff.hn@example.test","Staff Hà Nội","STAFF"},{"demo-admin","admin@example.test","Admin mẫu","ADMIN"}})
   jdbc.update("insert into users(id,email,password_hash,full_name,role,status,token_version,version) values(?,?,?,?,?,'ACTIVE',0,0)",a[0],a[1],hash,a[2],a[3]);
  jdbc.update("insert into branches(id,name,address,status,version) values('dn','Đà Nẵng · mẫu','Điểm giao nhận mẫu Đà Nẵng','ACTIVE',0),('hn','Hà Nội · mẫu','Điểm giao nhận mẫu Hà Nội','ACTIVE',0)");
  jdbc.update("insert into staff_branches(user_id,branch_id) values('demo-staff-dn','dn'),('demo-staff-hn','hn')");
  for(String from:new String[]{"dn","hn"})for(String to:new String[]{"dn","hn"})
   jdbc.update("insert into branch_routes(id,pickup_branch_id,return_branch_id,fee,enabled,version) values(?,?,?,?,true,0)",UUID.randomUUID().toString(),from,to,from.equals(to)?0:1500000);
  jdbc.update("insert into vehicle_brands(id,name) values('toyota','Toyota'),('hyundai','Hyundai'),('mitsubishi','Mitsubishi')");
  jdbc.update("insert into vehicle_categories(id,name) values('sedan','Sedan'),('mpv','MPV'),('suv','SUV')");
  jdbc.update("insert into vehicles(id,name,plate,brand_id,category_id,home_branch_id,current_branch_id,seats,transmission,daily_price,status,description,version) values('v1','Toyota Vios','DEMO-001','toyota','sedan','dn','dn',5,'AUTO',650000,'ACTIVE','Xe mẫu cho kiểm thử.',0),('v2','Mitsubishi Xpander','DEMO-002','mitsubishi','mpv','hn','hn',7,'AUTO',850000,'ACTIVE','Xe mẫu cho kiểm thử.',0),('v3','Hyundai Accent','DEMO-003','hyundai','sedan','dn','dn',5,'AUTO',600000,'ACTIVE','Xe mẫu cho kiểm thử.',0)");
 }
}
