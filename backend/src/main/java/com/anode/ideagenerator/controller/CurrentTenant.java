package com.anode.ideagenerator.controller;

import com.anode.ideagenerator.AppError;
import com.anode.ideagenerator.config.Mode;
import com.anode.ideagenerator.config.Security;
import com.anode.ideagenerator.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The signed-in tenant ("local" without login). Every route works on this tenant's data only. */
@Component
@RequiredArgsConstructor
class CurrentTenant {
    private final Mode mode;

    String id() {
        if (!mode.oauth()) return TenantRepository.LOCAL;
        if (SecurityContextHolder.getContext().getAuthentication() instanceof Authentication a && a.getPrincipal() instanceof Security.TenantUser u) {
            return u.tenantId();
        }
        throw new AppError(401, "unauthenticated", "Sign in first");
    }
}
