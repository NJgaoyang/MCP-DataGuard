package io.github.mcpdataguard.standalone;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StarRocksReadGrantPolicyTest {
 @Test void acceptsExactTableSelect() {
  assertTrue(StarRocksReadGrantPolicy.onlyDirectTableSelect(List.of(
    "GRANT USAGE ON CATALOG default_catalog TO USER 'reader'@'%'",
    "GRANT 'public' TO 'reader'@'%'",
    "GRANT SELECT ON TABLE `ads`.`ads_order_kpi_day` TO USER 'reader'@'%'"
  ),"ads","ads_order_kpi_day"));
 }
 @Test void rejectsRolesWildcardsAndWrites() {
  assertFalse(StarRocksReadGrantPolicy.onlyDirectTableSelect(List.of(
    "GRANT role_g, public TO 'reader'@'%'",
    "GRANT SELECT ON TABLE ads.ads_order_kpi_day TO USER 'reader'@'%'"
  ),"ads","ads_order_kpi_day"));
  assertFalse(StarRocksReadGrantPolicy.onlyDirectTableSelect(List.of(
    "GRANT SELECT ON TABLE ads.* TO USER 'reader'@'%'"
  ),"ads","ads_order_kpi_day"));
  assertFalse(StarRocksReadGrantPolicy.onlyDirectTableSelect(List.of(
    "GRANT SELECT, DELETE ON TABLE ads.ads_order_kpi_day TO USER 'reader'@'%'"
  ),"ads","ads_order_kpi_day"));
  assertFalse(StarRocksReadGrantPolicy.onlyDirectTableSelect(List.of(
    "GRANT SELECT ON TABLE ads.ads_order_kpi_day TO USER 'reader'@'%' WITH GRANT OPTION"
  ),"ads","ads_order_kpi_day"));
  assertFalse(StarRocksReadGrantPolicy.onlyDirectTableSelect(List.of(
    "GRANT SELECT ON TABLE other.orders TO USER 'reader'@'%'"
  ),"ads","ads_order_kpi_day"));
 }
 @Test void sourceTypeAndUrlsAreBounded() {
  assertEquals(ReadDatasourceDialect.STARROCKS,ReadDatasourceDialect.parse("starrocks"));
  assertEquals(ReadDatasourceDialect.MYSQL,ReadDatasourceDialect.parse(null));
  assertEquals(9030,ReadDatasourceDialect.STARROCKS.defaultPort());
  assertTrue(ReadDatasourceDialect.STARROCKS.url("starrocks.example",9030,"ads").contains("sslMode=VERIFY_IDENTITY"));
  assertThrows(Exception.class,()->ReadDatasourceDialect.parse("POSTGRESQL"));
  assertThrows(Exception.class,()->ReadDatasourceDialect.MYSQL.url("x/y",3306,"ads"));
 }
}
