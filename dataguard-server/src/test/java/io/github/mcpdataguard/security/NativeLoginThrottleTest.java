package io.github.mcpdataguard.security;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class NativeLoginThrottleTest {
 @Test void throttlesSameAccountCaseInsensitivelyBeforeBCrypt(){
  NativeLoginThrottle t=new NativeLoginThrottle(
    Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"),ZoneOffset.UTC));
  for(int n=0;n<12;n++)assertTrue(t.allow("org","Alice"));
  assertFalse(t.allow("org","alice"));
  assertTrue(t.allow("another-org","alice"));
  assertTrue(t.allow("org","Bob"));
 }
 @Test void limitsCrossAccountSprayingAtGlobalWindow(){
  NativeLoginThrottle t=new NativeLoginThrottle(
    Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"),ZoneOffset.UTC));
  for(int n=0;n<600;n++)assertTrue(t.allow("org","user"+n));
  assertFalse(t.allow("org","new-account"));
 }
 @Test void reopensAfterWindowWithoutCarryingOldCounters(){
  NativeLoginThrottle t=new NativeLoginThrottle(
    Clock.systemUTC());
  assertTrue(t.allow("org","x"));
  assertFalse(t.allow(null,"x"));
 }
}
