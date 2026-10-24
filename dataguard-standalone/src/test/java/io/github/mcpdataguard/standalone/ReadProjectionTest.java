package io.github.mcpdataguard.standalone;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReadProjectionTest {
 @Test void constructsFiniteSingleTableSelect(){
  assertEquals("SELECT `amount`,`order_id` FROM `orders` LIMIT 10",
    ReadProjection.select("orders",Set.of("order_id","amount"),10));
 }
 @Test void rejectsInjectionAndMissingGrants(){
  assertThrows(Exception.class,()->ReadProjection.select("orders; DROP TABLE users",Set.of("amount"),10));
  assertThrows(Exception.class,()->ReadProjection.select("orders",Set.of("amount,secret"),10));
  assertThrows(Exception.class,()->ReadProjection.select("orders",Set.of("amount"),101));
  assertThrows(Exception.class,()->ReadProjection.select("orders",Set.of(),10));
 }
}
