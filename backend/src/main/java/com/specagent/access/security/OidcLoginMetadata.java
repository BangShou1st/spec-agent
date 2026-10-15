package com.specagent.access.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/** Infrastructure projection: HTTP controllers never depend on a registration repository. */
@Component
public class OidcLoginMetadata {
    private final ObjectProvider<ClientRegistrationRepository> registrations;
    public OidcLoginMetadata(ObjectProvider<ClientRegistrationRepository> registrations) {
        this.registrations=registrations;
    }
    public String loginUrl() {
        var repository=registrations.getIfAvailable();
        if(repository instanceof Iterable<?> clients) {
            for(Object client:clients) if(client instanceof ClientRegistration registration)
                return "/oauth2/authorization/"+registration.getRegistrationId();
        }
        return null;
    }
}
