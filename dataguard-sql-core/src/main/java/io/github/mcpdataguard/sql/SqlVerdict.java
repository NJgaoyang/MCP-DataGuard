package io.github.mcpdataguard.sql;
public record SqlVerdict(boolean allowed, String operation, String reason) {
 public static SqlVerdict deny(String reason) { return new SqlVerdict(false, "UNKNOWN", reason); }
}