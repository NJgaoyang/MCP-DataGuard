package io.github.mcpdataguard.standalone;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A finite SQL template, not a SQL passthrough. All identifiers must pass a
 * narrow allowlist; no client-provided SQL is concatenated or parsed.
 * ReadApi checks ownership, approval, agent grants and target DB privileges.
 */
final class ReadProjection {
 private ReadProjection(){}
 static String select(String table,Set<String> approvedColumns,int limit){
  String safeTable=Guard.identifier(table);
  if(limit<1||limit>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Read limit must be 1..100");
  if(approvedColumns==null||approvedColumns.isEmpty())
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"No authorized columns");
  String columns=approvedColumns.stream().map(Guard::identifier).sorted()
    .map(c->"`"+c+"`").collect(Collectors.joining(","));
  return "SELECT "+columns+" FROM `"+safeTable+"` LIMIT "+limit;
 }
}
