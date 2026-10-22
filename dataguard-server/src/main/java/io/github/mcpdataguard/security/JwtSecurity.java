package io.github.mcpdataguard.security;

import org.springframework.context.annotation.Bean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** Native token auth is the only enabled login mechanism. No remote IdP/JWKS. */
@Configuration
public class JwtSecurity {
 @Bean FilterRegistrationBean<NativeSessionFilter> nativeFilterRegistration(NativeSessionFilter nativeFilter){
  FilterRegistrationBean<NativeSessionFilter> reg=new FilterRegistrationBean<>(nativeFilter);
  reg.setEnabled(false); // Only SecurityFilterChain controls this authentication filter.
  return reg;
 }
 @Bean SecurityFilterChain filterChain(HttpSecurity http,NativeSessionFilter nativeFilter) throws Exception {
  return http.csrf(c->c.disable())
   .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a->a
      .requestMatchers("/api/v1/auth/login","/actuator/health","/actuator/health/**").permitAll()
      .anyRequest().authenticated())
   .exceptionHandling(e->e.authenticationEntryPoint((req,res,err)->res.sendError(401)))
   .addFilterBefore(nativeFilter,UsernamePasswordAuthenticationFilter.class)
   .build();
 }
}
