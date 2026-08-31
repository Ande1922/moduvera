package io.github.ande1922.moduvera.authorization;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;

public final class UseCaseAuthorizer {

    public void require(PermissionCode permission) {
        if (!ExecutionContextHolder.require().actor().hasPermission(permission.value())) {
            throw new PermissionDeniedException(permission);
        }
    }
}
