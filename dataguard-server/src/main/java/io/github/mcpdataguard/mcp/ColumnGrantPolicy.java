package io.github.mcpdataguard.mcp;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/** Restrictive authorization for basic SELECT preflight (not execution).
 * Table/schema wildcard permissions cannot grant implicit column access.
 * DENY wins over ALLOW; a column must have an exact explicit ALLOW.
 */
public final class ColumnGrantPolicy {
 private ColumnGrantPolicy(){}
 public record Grant(String schema,String table,String column,String decision,Set<String> operations){}
 public static boolean allows(String table,Collection<String> columns,Collection<Grant> grants){
  if(table==null || columns==null || columns.isEmpty() || grants==null)return false;
  for(String column:columns){
   if(column==null || !column.matches("[a-z][a-z0-9_]*"))return false;
   boolean allowed=false;
   for(Grant grant:grants){
    if(grant==null || !"*".equals(grant.schema()) ||
        !Set.of(table,"*").contains(grant.table()) ||
        !grant.operations().contains("SELECT"))continue;
    if(!"*".equals(grant.column()) && !column.equals(grant.column()))continue;
    if("DENY".equals(grant.decision()))return false;
    if("ALLOW".equals(grant.decision()) &&
       table.equals(grant.table()) && column.equals(grant.column()))allowed=true;
   }
   if(!allowed)return false;
  }
  return true;
 }
}
