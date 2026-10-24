package io.github.mcpdataguard.standalone;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReadProjectionTest {
 @Test void constructsFiniteSingleTableSelect(){
  assertEquals("SELECT `amount`,`order_id` FROM `orders` LIMIT 10",
    ReadProjection.select("orders",Set.of("order_id","amount"),10));
 }
 @Test void buildsBoundedPreparedFiltersAndSorts(){
  var result=ReadProjection.build("orders",Set.of("order_id","city","amount"),25,
     "city","北京' OR 1=1 --","amount","DESC");
  assertEquals("SELECT `amount`,`city`,`order_id` FROM `orders` WHERE `city` = ? ORDER BY `amount` DESC LIMIT 25",result.sql());
  assertEquals("北京' OR 1=1 --",result.predicateValue());
 }
 @Test void forbidsUnauthorizedPredicateSortAndMalformedDirection(){
  assertThrows(Exception.class,()->ReadProjection.build("orders",Set.of("order_id"),10,
    "secret","x",null,null));
  assertThrows(Exception.class,()->ReadProjection.build("orders",Set.of("order_id"),10,
    null,null,"secret","ASC"));
  assertThrows(Exception.class,()->ReadProjection.build("orders",Set.of("order_id"),10,
    null,null,"order_id","DESC; DROP TABLE users"));
  assertThrows(Exception.class,()->ReadProjection.build("orders",Set.of("order_id"),10,
    null,"hidden",null,null));
 }
 @Test void rejectsInjectionAndMissingGrants(){
  assertThrows(Exception.class,()->ReadProjection.select("orders; DROP TABLE users",Set.of("amount"),10));
  assertThrows(Exception.class,()->ReadProjection.select("orders",Set.of("amount,secret"),10));
  assertThrows(Exception.class,()->ReadProjection.select("orders",Set.of("amount"),101));
  assertThrows(Exception.class,()->ReadProjection.select("orders",Set.of(),10));
 }
}
