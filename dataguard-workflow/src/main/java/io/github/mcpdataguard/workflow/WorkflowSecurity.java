package io.github.mcpdataguard.workflow;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;
@Configuration public class WorkflowSecurity {
 @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
  return http.csrf(c -> c.disable())
    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health").permitAll().anyRequest().denyAll())
    .build();
 }
}