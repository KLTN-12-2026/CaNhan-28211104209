package com.carrental.config;
import com.carrental.security.TokenFilter;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.web.cors.*;
import java.util.List;
@Configuration public class SecurityConfig {
 @Bean SecurityFilterChain security(HttpSecurity http,TokenFilter filter)throws Exception{
  return http.csrf(c->c.disable()).cors(c->{}).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a->a.requestMatchers("/api/v1/auth/login","/api/v1/auth/register","/api/v1/health").permitAll()
    .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/vehicles/**","/api/v1/branches","/api/v1/branch-routes","/api/v1/payments/vnpay/ipn").permitAll()
    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN").anyRequest().authenticated())
   .exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->{r.setStatus(401);r.setContentType("application/json;charset=UTF-8");r.getWriter().write("{\"message\":\"Vui lòng đăng nhập.\"}");})
    .accessDeniedHandler((q,r,x)->{r.setStatus(403);r.setContentType("application/json;charset=UTF-8");r.getWriter().write("{\"message\":\"Không có quyền truy cập.\"}");}))
   .addFilterBefore(filter,UsernamePasswordAuthenticationFilter.class).build();
 }
 @Bean CorsConfigurationSource cors(@Value("${app.cors-origin}")String origin){
  var c=new CorsConfiguration();c.setAllowedOrigins(List.of(origin));c.setAllowedMethods(List.of("GET","POST","PUT","PATCH","OPTIONS"));c.setAllowedHeaders(List.of("Authorization","Content-Type"));
  var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",c);return source;
 }
}
