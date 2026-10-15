package com.specagent.access;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Every settings lookup has an explicit organization, including Worker lookups. */
@Component
public class OrganizationConfigurationScope {
    private final AuthorizationGuard guard;
    public OrganizationConfigurationScope(AuthorizationGuard guard) {this.guard=guard;}
    public UUID readOrg() {
        var actor=guard.ownershipActor();
        return guard.requireSelectedOrg(actor,null);
    }
    public UUID writeOrg() {
        var actor=guard.ownershipActor();
        if (actor.orgId()!=null && actor.userId()!=null)
            guard.lockSharedFailClosed(java.util.List.of(AuthorizationScopes.org(actor.orgId()),
                    AuthorizationScopes.user(actor.userId())));
        return guard.requireSelectedOrg(actor,Capability.CREDENTIAL_MANAGE);
    }
    public boolean installationDefaultsAllowed() {
        return guard.ownershipActor().source()==ActorSource.LOCAL;
    }
}
