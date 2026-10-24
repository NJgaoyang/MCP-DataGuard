package io.github.mcpdataguard.standalone;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@Configuration
public class AuthSecurity {
 @Bean PasswordEncoder encoder(){return new BCryptPasswordEncoder(12);}
 @Bean UserDetailsService userDetails(JdbcTemplate jdbc){
  return username -> {
   var list=jdbc.query("SELECT password_hash,role_code,enabled,lock_until FROM app_users WHERE username=?",
    (rs,i)->new Object[]{rs.getString(1),rs.getString(2),rs.getBoolean(3),rs.getTimestamp(4)},username);
   if(list.size()!=1)throw new org.springframework.security.core.userdetails.UsernameNotFoundException("Invalid credentials");
   Object[] u=list.get(0);
   if(!((Boolean)u[2]))throw new DisabledException("Account disabled");
   if(u[3]!=null && ((java.sql.Timestamp)u[3]).toInstant().isAfter(java.time.Instant.now()))
    throw new LockedException("Account temporarily locked");
   return User.withUsername(username).password((String)u[0]).roles((String)u[1]).build();
  };
 }
 @Bean SecurityFilterChain filterChain(HttpSecurity http, JdbcTemplate jdbc, AuditService audit,
   ForcedPasswordFilter force) throws Exception {
  http
   .exceptionHandling(e->e.defaultAuthenticationEntryPointFor(
       new HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED),
       PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
   .authorizeHttpRequests(a->a
      .requestMatchers("/login.html","/api/csrf","/favicon.ico","/login","/error").permitAll()
      .anyRequest().authenticated())
   .formLogin(f->f.loginPage("/login.html").loginProcessingUrl("/auth/login")
      .successHandler((request,response,auth)->{
       jdbc.update("UPDATE app_users SET failed_login_count=0,lock_until=NULL WHERE username=?",auth.getName());
       audit.log(auth.getName(),"LOGIN","USER",auth.getName(),"SUCCESS","browser");
       response.sendRedirect("/index.html");
      })
      .failureHandler((request,response,ex)->{
       String u=request.getParameter("username");
       if(u!=null && u.matches("[a-zA-Z0-9_.-]{3,64}")){
        jdbc.update("UPDATE app_users SET failed_login_count=failed_login_count+1,lock_until=CASE WHEN failed_login_count>=4 THEN DATE_ADD(NOW(3), INTERVAL 15 MINUTE) ELSE lock_until END WHERE username=?",u);
        audit.log(u,"LOGIN","USER",u,"DENIED","invalid credentials");
       }
       response.sendRedirect("/login.html?error");
      }).permitAll())
   .logout(l->l.logoutUrl("/auth/logout").logoutSuccessUrl("/login.html?logout")
      .invalidateHttpSession(true).deleteCookies("JSESSIONID"))
   .sessionManagement(sm->sm.sessionFixation(sf->sf.migrateSession()))
   .addFilterAfter(force,org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
   .headers(h->h.frameOptions(f->f.deny()).contentTypeOptions(c->{})
      .httpStrictTransportSecurity(t->t.includeSubDomains(true).maxAgeInSeconds(31536000)));
  return http.build();
 }
 @RestController
 public static class CsrfEndpoint {
  @GetMapping("/api/csrf")
  public Map<String,String> csrf(CsrfToken token) {
   return Map.of("header",token.getHeaderName(),"token",token.getToken());
  }
 }
}