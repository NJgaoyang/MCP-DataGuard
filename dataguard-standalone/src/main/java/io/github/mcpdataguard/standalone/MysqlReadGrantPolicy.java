package io.github.mcpdataguard.standalone;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Refuse unsafe external MySQL grants before executing a single read. */
final class MysqlReadGrantPolicy {
 private MysqlReadGrantPolicy(){}
 static boolean onlySelectOrUsage(List<String> grants){
  if(grants==null||grants.isEmpty())return false;
  boolean hasSelect=false;
  for(String raw:grants){
   if(raw==null)return false;
   String g=raw.toUpperCase(Locale.ROOT).replaceAll("\\s+"," ").trim();
   if(!g.startsWith("GRANT ")||!g.contains(" ON ")||!g.contains(" TO "))return false;
   if(g.contains(" WITH GRANT OPTION")||g.contains(" PROXY ")||g.contains("ALL PRIVILEGES"))return false;
   String privilege=g.substring(6,g.indexOf(" ON ")).trim();
   for(String item:privilege.split(",")){
    String p=item.trim();
    if(!p.equals("SELECT")&&!p.equals("USAGE"))return false;
    if(p.equals("SELECT"))hasSelect=true;
   }
   // Global SELECT grants are broader than the explicit table allowlist.
   if(privilege.contains("SELECT")&&g.substring(g.indexOf(" ON ")+4,g.indexOf(" TO ")).trim().equals("*.*"))
    return false;
  }
  return hasSelect;
 }
 static void requireReadOnlyGrants(Connection c) throws SQLException {
  java.util.ArrayList<String> grants=new java.util.ArrayList<>();
  try(var s=c.createStatement();var r=s.executeQuery("SHOW GRANTS FOR CURRENT_USER()")){
   while(r.next())grants.add(r.getString(1));
  }
  if(!onlySelectOrUsage(grants))
   throw new SQLException("Target MySQL account has unsafe or excessive grants");
 }
}
