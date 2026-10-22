package io.github.mcpdataguard.api;

import io.github.mcpdataguard.api.ManagementCommands.*;
import io.github.mcpdataguard.security.UserPrincipalResolver;
import io.github.mcpdataguard.security.UserPrincipalResolver.UserPrincipal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** CRUD without privilege escalation: identities, tenants and status cannot be supplied by clients. */
@RestController
@RequestMapping("/api/v1")
public class ManagementMutationController {
 private final UserPrincipalResolver users;
 private final ManagementMutationService service;
 public ManagementMutationController(UserPrincipalResolver users,ManagementMutationService service) {
  this.users=users;this.service=service;
 }
 private UserPrincipal authorize(Jwt jwt,String permission){
  UserPrincipal actor=users.requireUser(jwt);
  users.requirePermission(actor,permission);
  return actor;
 }
 @PostMapping("/agents")
 @ResponseStatus(HttpStatus.CREATED)
 public WriteResult newAgent(@AuthenticationPrincipal Jwt jwt,@RequestBody NewAgent input){
  return service.createAgent(authorize(jwt,"agent:write"),input);
 }
 @PutMapping("/agents/{id}")
 public WriteResult updateAgent(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,
                               @RequestBody UpdateAgent input){
  return service.updateAgent(authorize(jwt,"agent:write"),id,input);
 }
 @PostMapping("/agents/{id}/revoke")
 public WriteResult revokeAgent(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){
  return service.revokeAgent(authorize(jwt,"agent:write"),id);
 }
 @PostMapping("/datasources")
 @ResponseStatus(HttpStatus.CREATED)
 public WriteResult newDatasource(@AuthenticationPrincipal Jwt jwt,@RequestBody NewDatasource input){
  return service.createDatasource(authorize(jwt,"datasource:write"),input);
 }
 @PutMapping("/datasources/{id}")
 public WriteResult updateDatasource(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,
                                    @RequestBody UpdateDatasource input){
  return service.updateDatasource(authorize(jwt,"datasource:write"),id,input);
 }
 @PostMapping("/datasources/{id}/disable")
 public WriteResult disableDatasource(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){
  return service.disableDatasource(authorize(jwt,"datasource:write"),id);
 }
}
