package io.github.mcpdataguard.standalone;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fail-closed runtime conditions only for explicitly selected production mode. */
@Configuration
public class MysqlProductionGate {
 static void validate(String url,boolean secureCookie,String bind){
  if(url==null||!url.toLowerCase(java.util.Locale.ROOT).contains("sslmode=verify_identity"))
   throw new IllegalStateException("PRODUCTION_NO_GO: platform MySQL requires TLS VERIFY_IDENTITY");
  if(!secureCookie)throw new IllegalStateException("PRODUCTION_NO_GO: Secure session cookies required");
  if(!"127.0.0.1".equals(bind)&&!"::1".equals(bind))
   throw new IllegalStateException("PRODUCTION_NO_GO: Spring Boot must bind only loopback behind trusted HTTPS reverse proxy");
 }
 @Bean ApplicationRunner productionGate(JdbcTemplate jdbc,
    @Value("${DATAGUARD_PRODUCTION:false}") boolean production,
    @Value("${DATAGUARD_MYSQL_URL:}") String url,
    @Value("${DATAGUARD_COOKIE_SECURE:false}") boolean secure,
    @Value("${DATAGUARD_BIND:127.0.0.1}") String bind) {
  return args->{
   if(!production)return;
   validate(url,secure,bind);
   Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE username='admin' AND must_change_password=1",Integer.class);
   if(n!=null&&n>0)throw new IllegalStateException("PRODUCTION_NO_GO: bootstrap admin password change pending");
  };
 }
}
