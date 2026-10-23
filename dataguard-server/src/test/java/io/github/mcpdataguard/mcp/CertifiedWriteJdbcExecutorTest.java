package io.github.mcpdataguard.mcp;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** All failure cases are rejected before a target database connection. */
class CertifiedWriteJdbcExecutorTest {
 @Test void disabledHostAndUnsupportedMutationsFailClosed(){
  var executor=new CertifiedWriteJdbcExecutor("trusted.example.org");
  var target=new CertifiedWriteJdbcExecutor.Target("other.example.org",5432,
    "dataguard","limited_writer","temporary-only");
  assertThrows(SecurityException.class,()->executor.execute(target,
    "UPDATE orders SET status=? WHERE id=?",List.of("complete",7)));
 }
 @Test void nonParameterizedOrUnboundedWritesCannotConnect(){
  var executor=new CertifiedWriteJdbcExecutor("trusted.example.org");
  var target=new CertifiedWriteJdbcExecutor.Target("trusted.example.org",5432,
    "dataguard","limited_writer","temporary-only");
  assertThrows(SecurityException.class,()->executor.execute(target,
    "DELETE FROM orders",List.of()));
  assertThrows(SecurityException.class,()->executor.execute(target,
    "UPDATE orders SET status='paid' WHERE id=?",List.of(1)));
  assertThrows(SecurityException.class,()->executor.execute(target,
    "UPDATE orders SET status=? WHERE id=?",List.of("ok")));
 }
}
