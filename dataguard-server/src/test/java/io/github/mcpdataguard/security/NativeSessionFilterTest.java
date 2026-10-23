package io.github.mcpdataguard.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Verifies actual filter authentication before authorization and enforced password rotation. */
class NativeSessionFilterTest {
 @AfterEach void clean(){SecurityContextHolder.clearContext();}
 private static JdbcTemplate validUser(boolean mustChange) throws Exception {
  JdbcTemplate jdbc=mock(JdbcTemplate.class);
  ResultSet rs=mock(ResultSet.class);
  when(rs.getObject(1,UUID.class)).thenReturn(UUID.randomUUID());
  when(rs.getTimestamp(2)).thenReturn(java.sql.Timestamp.from(Instant.now().minusSeconds(60)));
  when(rs.getTimestamp(3)).thenReturn(java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
  when(rs.getBoolean(4)).thenReturn(mustChange);
  when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
    .thenAnswer(inv->{
      String sql=inv.getArgument(0);
      if(sql.contains("FROM dg_native_session")){
        RowMapper<?> mapper=inv.getArgument(1);
        return List.of(mapper.mapRow(rs,0));
      }
      return List.of();
    });
  return jdbc;
 }
 private static void check(String path,boolean mustChange,boolean authenticated)throws Exception {
  JdbcTemplate jdbc=validUser(mustChange);
  String token=NativeTokens.random();
  HttpServletRequest request=mock(HttpServletRequest.class);
  HttpServletResponse response=mock(HttpServletResponse.class);
  FilterChain chain=mock(FilterChain.class);
  when(request.getHeader("Authorization")).thenReturn("Bearer "+token);
  when(request.getServletPath()).thenReturn(path);
  // The OncePerRequestFilter wrapper may inspect request markers and dispatch kind.
  new NativeSessionFilter(jdbc).doFilterInternal(request,response,(req,res)->{
   var current=SecurityContextHolder.getContext().getAuthentication();
   if(authenticated){
    assertInstanceOf(JwtAuthenticationToken.class,current);
    assertTrue(current.isAuthenticated());
    assertEquals("local-user:",((JwtAuthenticationToken)current).getToken().getSubject().substring(0,11));
   }else assertNull(current);
  });
  assertNull(SecurityContextHolder.getContext().getAuthentication(),
    "Security context must not leak between requests");
 }
 @Test void passwordChangeEndpointRecognizesFirstLoginToken()throws Exception{
  check("/api/v1/auth/password",true,true);
 }
 @Test void firstLoginCannotReadBusinessApis()throws Exception{
  check("/api/v1/me",true,false);
 }
 @Test void rotatedAccountCanReadBusinessApis()throws Exception{
  check("/api/v1/me",false,true);
 }
 @Test void malformedBearerNeverCreatesAuthentication()throws Exception{
  JdbcTemplate jdbc=mock(JdbcTemplate.class);
  HttpServletRequest request=mock(HttpServletRequest.class);
  when(request.getHeader("Authorization")).thenReturn("Bearer forged");
  when(request.getServletPath()).thenReturn("/api/v1/me");
  new NativeSessionFilter(jdbc).doFilterInternal(request,mock(HttpServletResponse.class),
    (req,res)->assertNull(SecurityContextHolder.getContext().getAuthentication()));
  verifyNoInteractions(jdbc);
 }
}
