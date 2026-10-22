package io.github.mcpdataguard.security;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;
@Configuration
public class JwtSecurity {
 @Bean JwtDecoder jwtDecoder(@Value("${dataguard.security.issuer}") String issuer,
                             @Value("${dataguard.security.audience}") String audience,
                             @Value("${dataguard.security.jwk-set-uri:}") String jwkSetUri) {
  NimbusJwtDecoder decoder = jwkSetUri.isBlank()
   ? NimbusJwtDecoder.withIssuerLocation(issuer).build()
   : NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
  OAuth2TokenValidator<Jwt> aud = jwt -> jwt.getAudience().contains(audience)
    ? OAuth2TokenValidatorResult.success()
    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
  decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), aud));
  return decoder;
 }
 @Bean SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
  return http.csrf(c -> c.disable())
   .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health", "/actuator/health/**").permitAll().anyRequest().authenticated())
   .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
   .build();
 }
}