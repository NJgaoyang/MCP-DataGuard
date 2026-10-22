package io.github.mcpdataguard.api;

import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Validates only configuration metadata. No database connection is attempted. */
public final class ManagementValidators {
 private static final Pattern CODE=Pattern.compile("[a-z][a-z0-9_-]{2,79}");
 private static final Pattern HOST=Pattern.compile("[A-Za-z0-9][A-Za-z0-9.:-]{0,510}[A-Za-z0-9]");
 private static final Set<String> ENVIRONMENTS=Set.of("DEV","TEST","STAGING","PROD");
 private static final Set<String> DB_TYPES=Set.of("MYSQL","POSTGRESQL");
 private ManagementValidators(){}
 public static String code(String v) {
  if(v==null||!CODE.matcher(v).matches())throw bad("Code must be lowercase ASCII, 3-80 characters");
  return v;
 }
 public static String name(String v) {
  if(v==null||v.isBlank()||v.length()>200||v.chars().anyMatch(c->Character.isISOControl((char)c)))
   throw bad("Name must be 1-200 visible characters");
  return v.strip();
 }
 public static String description(String v) {
  if(v!=null&&(v.length()>2000||v.indexOf('\0')>=0))throw bad("Description too long or invalid");
  return v;
 }
 public static int bounded(Integer v,int min,int max,String field) {
  if(v==null||v<min||v>max)throw bad(field+" outside permitted range");
  return v;
 }
 public static long version(Long v) {
  if(v==null||v<0)throw bad("Expected version must be nonnegative");
  return v;
 }
 public static String enumValue(String v,Set<String> valid,String field) {
  if(v==null||!valid.contains(v))throw bad("Unsupported "+field);
  return v;
 }
 public static String environment(String v){return enumValue(v,ENVIRONMENTS,"environment");}
 public static String type(String v){return enumValue(v,DB_TYPES,"database type");}
 public static String host(String v){
  // Configuration only, never passed into a SQL statement or a live connector at this stage.
  if(v==null||v.length()<3||v.length()>512||!HOST.matcher(v).matches()||v.contains(".."))
   throw bad("Host must be a DNS name or IPv4/IPv6 literal (unbracketed)");
  return v;
 }
 public static String database(String v){
  if(v!=null && (v.isBlank()||v.length()>255||v.chars().anyMatch(c->Character.isISOControl((char)c))))
   throw bad("Invalid database name");
  return v;
 }
 private static ResponseStatusException bad(String reason){
  return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason);
 }
}
