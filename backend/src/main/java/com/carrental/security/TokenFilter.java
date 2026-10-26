package com.carrental.security;
import com.carrental.modules.user.service.AccountService;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
@Component public class TokenFilter extends OncePerRequestFilter {
 private final TokenService tokens;private final AccountService accounts;
 public TokenFilter(TokenService tokens,AccountService accounts){this.tokens=tokens;this.accounts=accounts;}
 protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
  String auth=req.getHeader("Authorization");
  if(auth!=null&&auth.startsWith("Bearer ")){
   try{
    var jwt=tokens.decode(auth.substring(7));Number v=jwt.getClaim("tv");
    if(v==null)throw new IllegalArgumentException();
    Actor actor=accounts.activeActor(jwt.getSubject(),v.longValue());
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("ROLE_"+actor.role()))));
   }catch(org.springframework.security.oauth2.jwt.JwtException|com.carrental.exception.DomainException|IllegalArgumentException e){
    SecurityContextHolder.clearContext();res.setStatus(401);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"message\":\"Phiên đăng nhập không hợp lệ hoặc đã hết hạn.\"}");return;
   }
  }
  chain.doFilter(req,res);
 }
}
