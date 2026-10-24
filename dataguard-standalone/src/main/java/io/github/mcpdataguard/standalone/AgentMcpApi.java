package io.github.mcpdataguard.standalone;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** JSON-RPC MCP Streamable HTTP response mode; no SSE sessions are advertised. */
@RestController
public class AgentMcpApi {
 private static final String VERSION="2025-03-26";
 private final AgentTokens tokens;
 private final ReadApi reader;
 private final AuditService audit;
 private final tools.jackson.databind.json.JsonMapper json=new tools.jackson.databind.json.JsonMapper();
 public AgentMcpApi(AgentTokens tokens,ReadApi reader,AuditService audit){
  this.tokens=tokens;this.reader=reader;this.audit=audit;
 }
 public record Request(String jsonrpc,Object id,String method,Map<String,Object> params){}
 private static Map<String,Object> error(int code,String message){
  return Map.of("code",code,"message",message);
 }
 private static Map<String,Object> response(Object id,String type,Object data){
  Map<String,Object> out=new LinkedHashMap<>();
  out.put("jsonrpc","2.0");out.put("id",id);out.put(type,data);return out;
 }
 private ResponseEntity<Map<String,Object>> unauthorized(){
  return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
    .header(HttpHeaders.WWW_AUTHENTICATE,"Bearer")
    .body(Map.of("jsonrpc","2.0","error",error(-32001,"Unauthorized")));
 }
 private static boolean supports(String protocol){
  return protocol==null||VERSION.equals(protocol);
 }
 @PostMapping(path="/mcp",consumes="application/json")
 public ResponseEntity<Map<String,Object>> mcp(
    @RequestHeader(value="Authorization",required=false) String authorization,
    @RequestHeader(value="MCP-Protocol-Version",required=false) String protocol,
    @RequestHeader(value="Accept",required=false) String accept,
    @RequestBody(required=false) Request req) {
  String raw=authorization!=null&&authorization.startsWith("Bearer ")?authorization.substring(7):null;
  String user=tokens.resolve(raw);
  if(user==null)return unauthorized();
  if(!supports(protocol))return ResponseEntity.status(HttpStatus.BAD_REQUEST)
    .body(response(req==null?null:req.id(),"error",error(-32600,"Unsupported MCP protocol version")));
  if(accept!=null && !accept.contains("application/json") && !accept.contains("*/*"))
   return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
  if(req==null||!"2.0".equals(req.jsonrpc())||req.method()==null||req.method().isBlank())
   return ResponseEntity.badRequest().body(response(req==null?null:req.id(),"error",error(-32600,"Invalid JSON-RPC request")));
  // Notifications are one-way. Must not emit fabricated id=0 JSON-RPC results.
  if(req.id()==null){
   if(req.method().startsWith("notifications/"))
    return ResponseEntity.status(HttpStatus.ACCEPTED).build();
   return ResponseEntity.badRequest().body(response(null,"error",error(-32600,"Request ID required")));
  }
  try{
   Map<String,Object> result=switch(req.method()){
    case "initialize" -> Map.of("protocolVersion",VERSION,
      "capabilities",Map.of("tools",Map.of()),
      "serverInfo",Map.of("name","DataGuard-MySQL","version","0.5.0"));
    case "ping" -> Map.of();
    case "tools/list" -> toolList();
    case "tools/call" -> call(user,req.params());
    default -> throw new IllegalArgumentException("Method not found");
   };
   return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
      .body(response(req.id(),"result",result));
  }catch(IllegalArgumentException e){
   audit.log(user,"MCP_REQUEST","METHOD",req.method(),"DENIED","invalid MCP request");
   return ResponseEntity.ok(response(req.id(),"error",error(-32602,"Unsupported or invalid MCP arguments")));
  }catch(Exception e){
   audit.log(user,"MCP_REQUEST","METHOD",req.method(),"DENIED","access denied");
   return ResponseEntity.ok(response(req.id(),"error",error(-32003,"MCP operation denied")));
  }
 }
 private Map<String,Object> toolList(){
  Map<String,Object> empty=Map.of("type","object","properties",Map.of());
  Map<String,Object> source=Map.of("type","object",
    "properties",Map.of("sourceId",Map.of("type","string")),
    "required",List.of("sourceId"));
  Map<String,Object> bounded=Map.of("type","object",
    "properties",Map.of("sourceId",Map.of("type","string"),
      "tableName",Map.of("type","string"),"limit",Map.of("type","integer","minimum",1,"maximum",100)),
    "required",List.of("sourceId","tableName"));
  return Map.of("tools",List.of(
    Map.of("name","list_datasources","description","Only sources with current approved allowlisted table access","inputSchema",empty),
    Map.of("name","get_schema","description","Approved allowlisted tables for requester","inputSchema",source),
    Map.of("name","describe_table","description","Explicitly disclosed readable columns of an approved table (no physical metadata)","inputSchema",Map.of("type","object","properties",Map.of("sourceId",Map.of("type","string"),"tableName",Map.of("type","string")),"required",List.of("sourceId","tableName"))),
    Map.of("name","execute_readonly","description","Bounded SELECT for approved table and authorized columns","inputSchema",bounded)));
 }
 private Map<String,Object> call(String user,Map<String,Object> request) throws Exception {
  if(request==null)throw new IllegalArgumentException("Missing arguments");
  Object arg=request.getOrDefault("arguments",Map.of());
  if(!(arg instanceof Map<?,?> args))throw new IllegalArgumentException("Invalid arguments");
  String name=String.valueOf(request.getOrDefault("name",""));
  Object value;
  switch(name){
   case "list_datasources" -> {
    if(!args.isEmpty())throw new IllegalArgumentException("Unexpected args");
    value=reader.mcpSources(user);
   }
   case "get_schema" -> {
    if(!args.keySet().equals(java.util.Set.of("sourceId")))throw new IllegalArgumentException("Invalid schema args");
    value=reader.mcpSchema(user,String.valueOf(args.get("sourceId")));
   }
   case "describe_table" -> {
    if(!args.keySet().equals(java.util.Set.of("sourceId","tableName")))
      throw new IllegalArgumentException("Invalid table description arguments");
    value=reader.mcpColumns(user,String.valueOf(args.get("sourceId")),String.valueOf(args.get("tableName")));
   }
   case "execute_readonly" -> {
    if(!java.util.Set.of("sourceId","tableName","limit").containsAll(args.keySet())
       ||!args.containsKey("sourceId")||!args.containsKey("tableName"))
      throw new IllegalArgumentException("Invalid read args");
    Object n=args.containsKey("limit")?args.get("limit"):10;
    if(!(n instanceof Integer))throw new IllegalArgumentException("Invalid limit");
    var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user,"",List.of());
    value=reader.read(auth,new ReadApi.ReadQuery(String.valueOf(args.get("sourceId")),
      String.valueOf(args.get("tableName")),(Integer)n));
   }
   default -> throw new IllegalArgumentException("Unsupported tool");
  }
  return Map.of("content",List.of(Map.of("type","text","text",json.writeValueAsString(value))),
    "isError",false);
 }
}
