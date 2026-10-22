package io.github.mcpdataguard.security;

public final class NativeAccessPolicy {
 private NativeAccessPolicy() {}
 public static boolean permitted(boolean mustChange,String path) {
  return !mustChange || "/api/v1/auth/password".equals(path) || "/api/v1/auth/logout".equals(path);
 }
}
