package io.github.mcpdataguard.api;

import java.util.UUID;

/** Strict, explicit input DTOs. Neither status nor org/owner can be client-supplied. */
public final class ManagementCommands {
 private ManagementCommands(){}
 public record NewAgent(String code,String name,String description,Integer rateLimitPerMinute,
                        Integer concurrencyLimit,Integer maxRows,Integer maxExecutionMs){}
 public record UpdateAgent(String name,String description,Integer rateLimitPerMinute,
                           Integer concurrencyLimit,Integer maxRows,Integer maxExecutionMs,Long expectedVersion){}
 public record NewDatasource(String code,String name,String dbType,String environment,String host,
                             Integer port,String databaseName){}
 public record UpdateDatasource(String name,String host,Integer port,String databaseName,Long expectedVersion){}
 public record WriteResult(UUID id,String status,long version){}
}
