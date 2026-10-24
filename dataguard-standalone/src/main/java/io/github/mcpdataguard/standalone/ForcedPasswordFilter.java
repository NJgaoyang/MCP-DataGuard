package io.github.mcpdataguard.standalone;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public final class ForcedPasswordFilter extends OncePerRequestFilter {
 private final JdbcTemplate jdbc;
 public ForcedPasswordFilter(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse resp,
  FilterChain chain) throws ServletException,IOException {
  String path=req.getServletPath();
  Authentication a=SecurityContextHolder.getContext().getAuthentication();
  if(a!=null && a.isAuthenticated() && !"anonymousUser".equals(a.getPrincipal())
      && path.startsWith("/api/") && !path.equals("/api/csrf")
      && !path.equals("/api/me") && !path.equals("/api/me/password")){
    Integer n=jdbc.queryForObject("SELECT count(*) FROM app_users WHERE username=? AND enabled=1 AND must_change_password=0",Integer.class,a.getName());
    if(n==null||n!=1){
      resp.setStatus(428);
      resp.setContentType("application/json;charset=UTF-8");
      resp.getWriter().write("{\"error\":\"PASSWORD_CHANGE_REQUIRED\"}");
      return;
    }
  }
  chain.doFilter(req,resp);
 }
}