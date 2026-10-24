package io.github.mcpdataguard.standalone;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class BootstrapAdmin {
 @Bean ApplicationRunner firstAdmin(JdbcTemplate jdbc,PasswordEncoder encoder,
  @Value("${dataguard.bootstrap.enabled:true}") boolean enabled){
  return args->{
   if(!enabled)return;
   // No resets on restart. Only a truly empty database gets this temporary account.
   Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM app_users",Integer.class);
   if(n!=null && n==0) {
    jdbc.update("INSERT INTO app_users(id,username,display_name,password_hash,role_code,enabled,must_change_password) VALUES(?,?,?,?,?,1,1)",
       UUID.randomUUID().toString(),"admin","系统管理员",encoder.encode("123456"),"ADMIN");
    jdbc.update("INSERT INTO audit_logs(actor_username,action,resource_type,resource_id,outcome,detail) VALUES('system','BOOTSTRAP','USER','admin','SUCCESS','first-start only, forced password change')");
    System.err.println("DataGuard: temporary admin account created. Login admin/123456 on LOOPBACK ONLY and change password immediately.");
   }
  };
 }
}