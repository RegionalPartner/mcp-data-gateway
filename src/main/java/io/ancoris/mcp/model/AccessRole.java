package io.ancoris.mcp.model;

public enum AccessRole {
    READ_ONLY,
    ADMIN;

    public boolean canAccessSalary() {
        return this == ADMIN;
    }

    public boolean canAccessConfidential() {
        return this == ADMIN;
    }
}
