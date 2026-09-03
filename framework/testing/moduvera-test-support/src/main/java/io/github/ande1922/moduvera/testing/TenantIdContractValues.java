package io.github.ande1922.moduvera.testing;

import java.util.List;

public final class TenantIdContractValues {

    public static final String MINIMUM_LENGTH = "a";
    public static final String MAXIMUM_LENGTH = "t".repeat(64);
    public static final String REPRESENTATIVE = "Tenant_01.eu-west";
    public static final String OVER_MAXIMUM_LENGTH = "t".repeat(65);
    public static final String ILLEGAL_CHARACTER = "tenant/unsafe";

    private TenantIdContractValues() {}

    public static List<String> validTenantIds() {
        return List.of(MINIMUM_LENGTH, MAXIMUM_LENGTH, REPRESENTATIVE);
    }

    public static List<String> invalidTenantIds() {
        return List.of("", OVER_MAXIMUM_LENGTH, ILLEGAL_CHARACTER);
    }
}
