package io.github.mcpdataguard.standalone;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AgentMcpApi {
 private final AgentTokens tokens;
 private final ReadApi reader;
 private final AuditService audit;
 public AgentMcpApi(AgentTokens tokens,ReadApi reader,AuditService audit){
  this.tokens=tokens;this.reader=reader;this.audit=audit;
 }
 public record Request(String jsonrpc,Object id,String method,Map<String,Object> params){}
 @PostMapping(path="/mcp",consumes="application/json",produces="application/json")
 public ResponseEntity<Map<String,Object>> mcp(@RequestHeader(value="Authorization",required=false) String header,@RequestBody Request req){
  String raw=header!=null&&header.startsWith("Bearer ")?header.substring(7):null;
  String user=tokens.resolve(raw);
  if(user==null)return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error",Map.of("code",-32001,"message","Unauthenticated MCP token")));
  if(req==null||!"2.0".equals(req.jsonrpc())||req.method()==null)
   return ResponseEntity.badRequest().body(Map.of("error",Map.of("code",-32600,"message","Invalid JSON-RPC request")));
  Map<String,Object> result;
  try{
   switch(req.method()){
    case "initialize" -> result=Map.of("protocolVersion","2025-03-26","capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","DataGuard-MySQL","version","0.5.0"));
    case "tools/list" -> result=Map.of("tools",List.of(
      Map.of("name","list_datasources","description","List enabled MySQL sources","inputSchema",Map.of("type","object","properties",Map.of())),
      Map.of("name","get_schema","description","List positive allowlisted tables and approved visible columns","inputSchema",Map.of("type","object","properties",Map.of())),
      Map.of("name","execute_readonly","description","Read one allowlisted approved MySQL table; fields redacted unless explicitly approved","inputSchema",Map.of("type","object","properties",Map.of("sourceId",Map.of("type","string"),"tableName",Map.of("type","string"),"limit",Map.of("type","integer")),"required",List.of("sourceId","tableName"))))));
    case "tools/call" -> {
      Map<String,Object> p=req.params()==null?Map.of():req.params();
      String name=String.valueOf(p.getOrDefault("name",""));
      Object args=p.getOrDefault("arguments",Map.of());
      if(!(args instanceof Map))throw new IllegalArgumentException("Invalid arguments");
      Map<?,?> a=(Map<?,?>)args;
      switch(name){
       case "list_datasources" -> result=Map.of("content",List.of(Map.of("type","text","text",reader.mcpSources(user).toString())));
       case "get_schema" -> result=Map.of("content",List.of(Map.of("type","text","text",reader.mcpSchema(user,String.valueOf(a.get("sourceId"))).toString())));
       case "execute_readonly" -> {
        if(!a.keySet().stream().allMatch(k->List.of("sourceId","tableName","limit").contains(k)))
         throw new IllegalArgumentException("Only fixed bounded table reads supported");
        Object limit=a.getOrDefault("limit",10);
        if(!(limit instanceof Integer))throw new IllegalArgumentException("Invalid integer limit");
        var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user,"",List.of());
        Map<String,Object> response=reader.read(auth,new ReadApi.ReadQuery(String.valueOf(a.get("sourceId")),String.valueOf(a.get("tableName")),(Integer)limit));
        result=Map.of("content",List.of(Map.of("type","text","text",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(response))));
       }
       default -> throw new IllegalArgumentException("Unknown MCP tool");
      }
     }
     default -> throw new IllegalArgumentException("Unsupported MCP method");
   }
  }catch(Exception err){
    audit.log(user,"MCP_REQUEST","TOOL",req.method(),"DENIED","invalid or denied");
    return ResponseEntity.ok(Map.of("jsonrpc","2.0","id",req.id()==null?0:req.id(),"error",Map.of("code",-32602,"message","MCP operation denied")));
  }
  return ResponseEntity.ok(Map.of("jsonrpc","2.0","id",req.id()==null?0:req.id(),"result",result));
 }
}
