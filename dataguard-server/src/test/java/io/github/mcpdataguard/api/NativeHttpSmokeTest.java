package io.github.mcpdataguard.api;

import io.github.mcpdataguard.security.NativeTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP integration against an isolated booted gateway and static frontend. */
class NativeHttpSmokeTest {
 private final HttpClient http=HttpClient.newBuilder()
   .connectTimeout(Duration.ofSeconds(3)).build();
 private record Reply(int status,String text){}
 private Reply call(String method,String url,String token,String payload)throws Exception{
  var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6))
    .header("Accept","application/json");
  if(token!=null)request.header("Authorization","Bearer "+token);
  if(payload!=null)request.header("Content-Type","application/json");
  request.method(method,payload==null?HttpRequest.BodyPublishers.noBody():
    HttpRequest.BodyPublishers.ofString(payload));
  var result=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
  return new Reply(result.statusCode(),result.body());
 }
 private static String token(String json){
  int key=json.indexOf("token");
  assertTrue(key>=0);
  int colon=json.indexOf(':',key);
  int start=json.indexOf('"',colon);
  int end=json.indexOf('"',start+1);
  assertTrue(end>start);
  return json.substring(start+1,end);
 }
 @Test void realFirstLoginPasswordRotationAuthorizationMcpAndConsoleHttp()throws Exception{
  String base=System.getenv("DG_HTTP_E2E_URL"),web=System.getenv("DG_WEB_E2E_URL"),
    jdbc=System.getenv("DG_DB_E2E_URL");
  Assumptions.assumeTrue(base!=null && base.startsWith("http://127.0.0.1:") &&
    web!=null && web.startsWith("http://127.0.0.1:") &&
    jdbc!=null && jdbc.startsWith("jdbc:postgresql://127.0.0.1:5432/"));
  JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource(jdbc,"postgres","test-only"));
  UUID org=UUID.randomUUID(),user=UUID.randomUUID(),role=UUID.randomUUID();
  String organization="http_"+org.toString().substring(0,8),username="admin";
  String initial="TestInit-"+org.toString().substring(0,8)+"!Aa9";
  String changed="TestChanged-"+org.toString().substring(0,8)+"!Bb8";
  db.update("INSERT INTO dg_organization(id,code,name) VALUES(?,?,?)",
    org,organization,"HTTP Acceptance");
  db.update("""
    INSERT INTO dg_user(id,org_id,username,display_name,status,password_hash,must_change_password)
    VALUES(?,?,?,?,'ACTIVE',?,true)
    """,user,org,username,"Acceptance Admin",NativeTokens.passwords().encode(initial));
  db.update("""
    INSERT INTO dg_role(id,org_id,code,name,permissions,system_role)
    VALUES(?,?,'org-admin','Org Admin',CAST(? AS jsonb),true)
    """,role,org,"[\"identity:admin\",\"agent:permission:write\",\"agent:read\",\"datasource:read\"]");
  db.update("INSERT INTO dg_user_role(org_id,user_id,role_id,assigned_by) VALUES(?,?,?,?)",
    org,user,role,user);
  assertEquals(200,call("GET",base+"/actuator/health",null,null).status());
  assertEquals(401,call("GET",base+"/api/v1/me",null,null).status());
  assertEquals(401,call("GET",base+"/mcp",null,null).status());
  String loginJson="{\"organization\":\""+organization+"\",\"username\":\""+
    username+"\",\"password\":\""+initial+"\"}";
  Reply first=call("POST",base+"/api/v1/auth/login",null,loginJson);
  assertEquals(200,first.status());
  assertTrue(first.text().contains("\"mustChangePassword\":true"));
  String firstToken=token(first.text());
  Reply changedResult=call("POST",base+"/api/v1/auth/password",firstToken,
    "{\"currentPassword\":\""+initial+"\",\"newPassword\":\""+changed+"\"}");
  assertEquals(200,changedResult.status());
  assertEquals(401,call("POST",base+"/api/v1/auth/login",null,loginJson).status());
  Reply second=call("POST",base+"/api/v1/auth/login",null,
    "{\"organization\":\""+organization+"\",\"username\":\""+
    username+"\",\"password\":\""+changed+"\"}");
  assertEquals(200,second.status());
  assertTrue(second.text().contains("\"mustChangePassword\":false"));
  String newToken=token(second.text());
  assertEquals(401,call("GET",base+"/api/v1/me",firstToken,null).status());
  assertEquals(200,call("GET",base+"/api/v1/me",newToken,null).status());
  assertEquals(200,call("GET",base+"/api/v1/operations/summary",newToken,null).status());
  assertEquals(200,call("GET",base+"/api/v1/workflow-events/dead-letters",newToken,null).status());
  assertEquals(200,call("GET",base+"/api/v1/security-alerts",newToken,null).status());
  assertEquals(200,call("GET",base+"/api/v1/audit-integrity",newToken,null).status());
  Reply html=call("GET",web+"/",null,null);
  assertEquals(200,html.status());
  var asset=Pattern.compile("src=\"(/assets/[^\"]+\\.js)\"").matcher(html.text());
  assertTrue(asset.find(),"Web login frontend JavaScript should be emitted");
  assertEquals(200,call("GET",web+asset.group(1),null,null).status());
 }
}
