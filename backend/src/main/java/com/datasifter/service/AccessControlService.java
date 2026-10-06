package com.datasifter.service;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AccessControlService {
    private static final Map<String, List<String>> ROLE_PERMISSIONS = Map.of(
            "admin", List.of("workflow:read", "workflow:write", "connector:read", "connector:write", "job:read", "job:write", "secret:read", "secret:write"),
            "operator", List.of("workflow:read", "workflow:write", "connector:read", "job:read", "job:write"),
            "auditor", List.of("workflow:read", "connector:read", "job:read", "audit:read"),
            "viewer", List.of("workflow:read", "connector:read", "job:read")
    );

    public List<String> getRoles() {
        return List.of("admin", "operator", "auditor", "viewer");
    }

    public boolean isAllowed(String role, String action) {
        if (role == null || role.isBlank() || action == null || action.isBlank()) {
            return false;
        }
        String normalizedRole = role.trim().toLowerCase();
        String normalizedAction = action.trim().toLowerCase();
        List<String> permissions = ROLE_PERMISSIONS.getOrDefault(normalizedRole, List.of());
        return permissions.contains(normalizedAction);
    }

    public Map<String, Object> describeAccess(String role) {
        String normalizedRole = role == null ? "viewer" : role.trim().toLowerCase();
        List<String> permissions = ROLE_PERMISSIONS.getOrDefault(normalizedRole, ROLE_PERMISSIONS.get("viewer"));
        return Map.of(
                "role", normalizedRole,
                "allowedActions", permissions,
                "isRestricted", !"admin".equals(normalizedRole)
        );
    }
}
