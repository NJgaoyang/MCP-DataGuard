package io.github.mcpdataguard.mcp;

import io.github.mcpdataguard.security.AgentPrincipal;
import io.github.mcpdataguard.security.AgentPrincipalResolver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthorizedSchemaServiceTest {
 private JdbcTemplate jdbc;
 private AgentPrincipalResolver agents;
 private AuthorizedSchemaService service;
 private final UUID org=UUID.randomUUID(), agent=UUID.randomUUID(), datasource=UUID.randomUUID();

 @BeforeEach void setup(){
  jdbc=mock(JdbcTemplate.class);
  agents=mock(AgentPrincipalResolver.class);
  when(agents.requireActiveAgent()).thenReturn(new AgentPrincipal(org,agent));
  service=new AuthorizedSchemaService(jdbc,agents);
 }
 private Map<String,Object> get(String cursor,Integer limit){
  return service.get(datasource.toString(),"public","reports",cursor,limit);
 }
 @Test void invalidArgumentsNeverQueryTheDatabase(){
  assertEquals("INVALID_DATASOURCE_ID",service.get("invalid","public","reports",null,10).get("code"));
  assertEquals("EXACT_TABLE_REQUIRED",service.get(datasource.toString(),"public","*",null,10).get("code"));
  assertEquals("INVALID_CURSOR",get("-1",10).get("code"));
  assertEquals("INVALID_PAGE_SIZE",get(null,101).get("code"));
  verifyNoInteractions(jdbc);
  verify(agents,atLeastOnce()).requireActiveAgent();
 }
 @Test void unauthorizedDatasourceNeverReadsGrants(){
  when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(false);
  assertEquals("METADATA_NOT_AUTHORIZED",get(null,10).get("code"));
  verify(jdbc,never()).query(anyString(),any(RowMapper.class),any(Object[].class));
 }
 @Test void emptyGrantsAreDenied(){
  when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(true);
  when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
  assertEquals("NO_AUTHORIZED_COLUMNS",get(null,10).get("code"));
 }
 @Test void explicitGrantResultsArePaginatedAndMarkedUnverified(){
  when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(true);
  when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
    .thenReturn(List.of("customer_id","order_id","total"));
  Map<String,Object> result=get(null,2);
  assertEquals("ALLOW",result.get("decision"));
  assertEquals(List.of("customer_id","order_id"),result.get("columns"));
  assertEquals("2",result.get("nextCursor"));
  assertEquals(true,result.get("hasMore"));
  assertEquals(false,result.get("physicalSchemaVerified"));
  assertEquals("CONFIGURED_GRANTS_ONLY",result.get("metadataSource"));
 }
}
