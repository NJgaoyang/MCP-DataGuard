package io.github.mcpdataguard.security;
import java.util.UUID;
public record AgentPrincipal(UUID orgId, UUID agentId) { }
