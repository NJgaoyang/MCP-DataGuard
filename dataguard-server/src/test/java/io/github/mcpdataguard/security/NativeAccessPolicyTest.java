package io.github.mcpdataguard.security;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeAccessPolicyTest {
 @Test void passwordChangeIsOnlyPrivilegedFirstLoginAction() {
  assertTrue(NativeAccessPolicy.permitted(true,"/api/v1/auth/password"));
  assertTrue(NativeAccessPolicy.permitted(true,"/api/v1/auth/logout"));
  for(String path:new String[]{"/api/v1/me","/api/v1/admin/users","/api/v1/agents","/api/v1/datasources","/api/v1/auth/password/extra"})
   assertFalse(NativeAccessPolicy.permitted(true,path),path);
  assertTrue(NativeAccessPolicy.permitted(false,"/api/v1/me"));
 }
}
