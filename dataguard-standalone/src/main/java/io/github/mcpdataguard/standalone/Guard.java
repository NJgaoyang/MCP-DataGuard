package io.github.mcpdataguard.standalone;

import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.regex.Pattern;

final class Guard {
 private Guard(){}
 static void requireAdmin(Authentication a){
  if(a==null||a.getAuthorities().stream().noneMatch(x->"ROLE_ADMIN".equals(x.getAuthority())))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Admin required");
 }
 static String identifier(String s){
  if(s==null||!Pattern.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}",s))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid identifier");
  return s;
 }
 static String username(String s){
  if(s==null||!Pattern.matches("[a-zA-Z0-9_.-]{3,64}",s))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid username");
  return s;
 }
 static void complex(String password){
  if(password==null||password.length()<12||password.length()>72||
     !password.matches("(?s).*?[A-Z].*")||!password.matches("(?s).*?[a-z].*")||
     !password.matches("(?s).*?[0-9].*")||!password.matches("(?s).*?[^A-Za-z0-9].*"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use 12-72 characters with upper/lower/digit/symbol");
 }
 static String role(String role){
  if(!java.util.Set.of("ADMIN","APPROVER","READER").contains(role))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid role");
  return role;
 }
}