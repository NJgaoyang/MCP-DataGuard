package io.github.mcpdataguard.security;

import java.time.Clock;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bounded, in-process defense against high-cardinality password spraying.
 *
 * Independent of per-user persistent account lockout (five failures) and
 * deliberately does not trust incoming X-Forwarded-For. Production edge
 * limits and multi-replica/distributed counters are still required when
 * this service is horizontally scaled.
 */
public final class NativeLoginThrottle {
 private static final long WINDOW_MS=60_000;
 private static final int MAX_IDENTITIES=4_096;
 private static final int MAX_PER_IDENTITY=12;
 private static final int MAX_TOTAL=600;
 private static final NativeLoginThrottle SHARED=new NativeLoginThrottle(Clock.systemUTC());
 private final Clock clock;
 private final Map<String,Integer> attempts=new HashMap<>();
 private long windowStart=-1;
 private int total;
 public NativeLoginThrottle(Clock clock){this.clock=clock;}
 public static NativeLoginThrottle shared(){return SHARED;}
 /** Returns false without revealing whether the account exists. */
 public synchronized boolean allow(String organization,String username){
  if(organization==null || username==null)return false;
  long now=clock.millis();
  if(windowStart<0 || now<windowStart || now-windowStart>=WINDOW_MS){
   attempts.clear();
   windowStart=now;
   total=0;
  }
  if(total>=MAX_TOTAL)return false;
  String key=organization.toLowerCase(Locale.ROOT)+"\u0000"+username.toLowerCase(Locale.ROOT);
  int current=attempts.getOrDefault(key,0);
  if(current>=MAX_PER_IDENTITY || (current==0 && attempts.size()>=MAX_IDENTITIES))
   return false;
  attempts.put(key,current+1);
  total++;
  return true;
 }
}
