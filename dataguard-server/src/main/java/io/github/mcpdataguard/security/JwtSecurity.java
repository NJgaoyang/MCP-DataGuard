package io.github.mcpdataguard.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class JwtSecurity {
 @Bean FilterRegistrationBean<NativeSessionFilter> nativeRegistration(NativeSessionFilter filter){
  FilterRegistrationBean<NativeSessionFilter> registration=new FilterRegistrationBean<>(filter);
  registration.setEnabled(false);
  return registration;
 }
 @Bean SecurityFilterChain filterChain(HttpSecurity http,NativeSessionFilter filter)throws Exception{
  return http.csrf(c->c.disable())
   .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a->a.requestMatchers("/api/v1/auth/login","/actuator/health","/actuator/health/**")
       .permitAll().anyRequest().authenticated())
   .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->res.sendError(401)))
   .addFilterBefore(filter,org.springframework.security.web.access.intercept.AuthorizationFilter.class)
   .build();
 }
}
