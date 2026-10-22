package io.github.mcpdataguard.api;

import io.github.mcpdataguard.api.ManagementCommands.*;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Safe administrative metadata mutations.
 * - All queries are tenant-scoped using the trusted server-derived principal.
 * - Data sources begin DISABLED; no connection or credential is established.
 * - Agents begin DISABLED; registration alone is not machine identity enrollment.
 * - Every successful change generates a dg_system_event in the same transaction.
 */
@Service
public class ManagementMutationService {
 private final JdbcTemplate jdbc;
 public ManagementMutationService(JdbcTemplate jdbc){this.jdbc=jdbc;}

 @Transactional
 public WriteResult createAgent(UserPrincipal user,NewAgent input) {
  String code=ManagementValidators.code(input.code());
  String name=ManagementValidators.name(input.name());
  String description=ManagementValidators.description(input.description());
  int rate=ManagementValidators.bounded(input.rateLimitPerMinute(),1,100000,"rateLimitPerMinute");
  int concurrency=ManagementValidators.bounded(input.concurrencyLimit(),1,1000,"concurrencyLimit");
  int rows=ManagementValidators.bounded(input.maxRows(),1,100000,"maxRows");
  int timeout=ManagementValidators.bounded(input.maxExecutionMs(),100,3600000,"maxExecutionMs");
  UUID id=UUID.randomUUID();
  try {
   jdbc.update("""
    INSERT INTO dg_agent(id,org_id,owner_user_id,code,name,description,status,
      rate_limit_per_minute,concurrency_limit,max_rows,max_execution_ms)
    VALUES (?,?,?,?,?,?,'DISABLED',?,?,?,?)
    """,id,user.orgId(),user.userId(),code,name,description,rate,concurrency,rows,timeout);
  } catch (DataIntegrityViolationException e) { throw conflict(); }
  event(user,"AGENT_CREATED","AGENT",id);
  return new WriteResult(id,"DISABLED",0);
 }

 @Transactional
 public WriteResult updateAgent(UserPrincipal user,UUID id,UpdateAgent input) {
  long version=ManagementValidators.version(input.expectedVersion());
  String name=ManagementValidators.name(input.name());
  String description=ManagementValidators.description(input.description());
  int rate=ManagementValidators.bounded(input.rateLimitPerMinute(),1,100000,"rateLimitPerMinute");
  int concurrency=ManagementValidators.bounded(input.concurrencyLimit(),1,1000,"concurrencyLimit");
  int rows=ManagementValidators.bounded(input.maxRows(),1,100000,"maxRows");
  int timeout=ManagementValidators.bounded(input.maxExecutionMs(),100,3600000,"maxExecutionMs");
  int updated=jdbc.update("""
    UPDATE dg_agent SET name=?, description=?, rate_limit_per_minute=?, concurrency_limit=?,
      max_rows=?, max_execution_ms=?, updated_at=now(), version=version+1
    WHERE org_id=? AND id=? AND version=? AND status<>'REVOKED'
    """,name,description,rate,concurrency,rows,timeout,user.orgId(),id,version);
  if(updated!=1) missingOrConflict("dg_agent",user.orgId(),id);
  event(user,"AGENT_UPDATED","AGENT",id);
  return new WriteResult(id,status("dg_agent",user.orgId(),id),version+1);
 }

 @Transactional
 public WriteResult revokeAgent(UserPrincipal user,UUID id) {
  int updated=jdbc.update("""
    UPDATE dg_agent SET status='REVOKED', updated_at=now(), version=version+1
    WHERE org_id=? AND id=? AND status<>'REVOKED'
    """,user.orgId(),id);
  if(updated!=1) missingOrConflict("dg_agent",user.orgId(),id);
  jdbc.update("""
    UPDATE dg_agent_identity SET status='REVOKED', revoked_at=now()
    WHERE org_id=? AND agent_id=? AND status='ACTIVE'
    """,user.orgId(),id);
  event(user,"AGENT_REVOKED","AGENT",id);
  long version=currentVersion("dg_agent",user.orgId(),id);
  return new WriteResult(id,"REVOKED",version);
 }

 @Transactional
 public WriteResult createDatasource(UserPrincipal user,NewDatasource input) {
  String code=ManagementValidators.code(input.code());
  String name=ManagementValidators.name(input.name());
  String type=ManagementValidators.type(input.dbType());
  String env=ManagementValidators.environment(input.environment());
  String host=ManagementValidators.host(input.host());
  int port=ManagementValidators.bounded(input.port(),1,65535,"port");
  String database=ManagementValidators.database(input.databaseName());
  UUID id=UUID.randomUUID();
  try {
   jdbc.update("""
    INSERT INTO dg_datasource(id,org_id,owner_user_id,code,name,db_type,
      environment,host,port,database_name,status)
    VALUES (?,?,?,?,?,?,?,?,?,?,'DISABLED')
    """,id,user.orgId(),user.userId(),code,name,type,env,host,port,database);
  } catch (DataIntegrityViolationException e) { throw conflict(); }
  event(user,"DATASOURCE_CREATED","DATASOURCE",id);
  return new WriteResult(id,"DISABLED",0);
 }

 @Transactional
 public WriteResult updateDatasource(UserPrincipal user,UUID id,UpdateDatasource input) {
  long version=ManagementValidators.version(input.expectedVersion());
  String name=ManagementValidators.name(input.name());
  String host=ManagementValidators.host(input.host());
  int port=ManagementValidators.bounded(input.port(),1,65535,"port");
  String database=ManagementValidators.database(input.databaseName());
  int updated=jdbc.update("""
    UPDATE dg_datasource SET name=?,host=?,port=?,database_name=?,updated_at=now(),
     version=version+1
    WHERE org_id=? AND id=? AND version=? AND status='DISABLED'
    """,name,host,port,database,user.orgId(),id,version);
  if(updated!=1) missingOrConflict("dg_datasource",user.orgId(),id);
  event(user,"DATASOURCE_UPDATED","DATASOURCE",id);
  return new WriteResult(id,"DISABLED",version+1);
 }

 @Transactional
 public WriteResult disableDatasource(UserPrincipal user,UUID id) {
  int changed=jdbc.update("""
   UPDATE dg_datasource SET status='DISABLED',version=version+1,updated_at=now()
   WHERE org_id=? AND id=? AND status<>'DISABLED'
   """,user.orgId(),id);
  if(changed!=1) missingOrConflict("dg_datasource",user.orgId(),id);
  event(user,"DATASOURCE_DISABLED","DATASOURCE",id);
  return new WriteResult(id,"DISABLED",currentVersion("dg_datasource",user.orgId(),id));
 }

 private void event(UserPrincipal actor,String operation,String kind,UUID resource) {
  // No SQL, secrets or host information is placed in the audit detail.
  jdbc.update("""
   INSERT INTO dg_system_event(id,org_id,actor,operation,resource_type,resource_id,detail)
   VALUES (?,?,?,?,?,?,'{}'::jsonb)
   """,UUID.randomUUID(),actor.orgId(),actor.userId().toString(),operation,kind,resource.toString());
 }
 private void missingOrConflict(String table,UUID org,UUID id) {
  // Table name is never user input.
  if(!table.equals("dg_agent") && !table.equals("dg_datasource"))
   throw new IllegalArgumentException("Unexpected table");
  Boolean present=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM "+table+
   " WHERE org_id=? AND id=?)",Boolean.class,org,id);
  if(!Boolean.TRUE.equals(present))
   throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Resource not found");
  throw conflict();
 }
 private static ResponseStatusException conflict() {
  return new ResponseStatusException(HttpStatus.CONFLICT,"Resource conflict or outdated version");
 }
 private long currentVersion(String table,UUID org,UUID id) {
  if(!table.equals("dg_agent") && !table.equals("dg_datasource"))
   throw new IllegalArgumentException("Unexpected table");
  return jdbc.queryForObject("SELECT version FROM "+table+
   " WHERE org_id=? AND id=?",Long.class,org,id);
 }
 private String status(String table,UUID org,UUID id) {
  if(!table.equals("dg_agent") && !table.equals("dg_datasource"))
   throw new IllegalArgumentException("Unexpected table");
  return jdbc.queryForObject("SELECT status FROM "+table+
   " WHERE org_id=? AND id=?",String.class,org,id);
 }
}
