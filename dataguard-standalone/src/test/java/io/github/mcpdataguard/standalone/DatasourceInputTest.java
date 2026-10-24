package io.github.mcpdataguard.standalone;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class DatasourceInputTest {
 private DatasourceApi.NewSource input(String env,String password){return new DatasourceApi.NewSource("db01","测试数据库","192.168.197.129",3306,"ods","root",env,password);}
 @Test void acceptsPasswordAndLegacyEnvironmentReference(){
  assertDoesNotThrow(()->DatasourceApi.validateSource(input(null,"123456")));
  assertDoesNotThrow(()->DatasourceApi.validateSource(input("DATAGUARD_TARGET_TEST_PASSWORD",null)));
  assertFalse(input(null,"123456").toString().contains("123456"));
 }
 @Test void rejectsWrongFieldAndAmbiguousCredentialsWithUsefulMessage(){
  var error=assertThrows(ResponseStatusException.class,()->DatasourceApi.validateSource(input("123456",null)));
  assertTrue(error.getReason().contains("密码环境变量"));
  assertThrows(ResponseStatusException.class,()->DatasourceApi.validateSource(input(null,null)));
  assertThrows(ResponseStatusException.class,()->DatasourceApi.validateSource(input("DATAGUARD_TARGET_TEST_PASSWORD","123456")));
 }
}
