package io.github.mcpdataguard.standalone;

import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Exact host allowlist for ALL outbound JDBC probes and read requests. */
final class ReadSourceHostPolicy {
 private ReadSourceHostPolicy(){}
 static boolean allowed(String host,String csv){
  if(host==null||!host.matches("[A-Za-z0-9.-]{1,253}"))return false;
  if(csv==null||csv.isBlank())return false;
  return Arrays.stream(csv.split(",")).map(String::strip)
    .filter(v->v.matches("[A-Za-z0-9.-]{1,253}")).anyMatch(host::equals);
 }
 static void requireAllowed(String host){
  if(!allowed(host,System.getenv().getOrDefault("DATAGUARD_READ_HOSTS","127.0.0.1,localhost")))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Datasource host not approved by outbound policy");
 }
}
