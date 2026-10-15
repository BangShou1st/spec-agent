package com.specagent.access;

import com.specagent.access.identity.IdentityRepository;
import com.specagent.modelsettings.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ModelSettingsIsolationIntegrationTest {
    @Autowired IdentityRepository identities;
    @Autowired OpenCodeSettingsRepository settings;
    @Autowired ModelProviderRepository providers;
    @Autowired ModelProviderSettingsRepository active;
    @Autowired ModelCredentialEncryptionMigrator migrator;
    @Autowired ModelCredentialCrypto crypto;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationConfigurationScope configurationScope;
    @Autowired com.specagent.workspace.profile.ProfileRepository profiles;
    private UUID a,b,user,member;

    @BeforeEach void fixture() {
        a=UUID.randomUUID();b=UUID.randomUUID();user=UUID.randomUUID();member=UUID.randomUUID();
        identities.insertUserIfAbsent(user,"Model owner");
        identities.insertUserIfAbsent(member,"Model member");
        identities.insertOrganizationIfAbsent(a,"Model A","STANDARD");
        identities.insertOrganizationIfAbsent(b,"Model B","STANDARD");
        identities.upsertMembership(a,user,OrgRole.OWNER);
        identities.upsertMembership(b,user,OrgRole.OWNER);
        identities.upsertMembership(a,member,OrgRole.MEMBER);
    }
    @AfterEach void clear() {ActorContextHolder.clear();}
    @Test void profileIdsNamesAndListsAreOrganizationScopedAndOnlyBuiltinTemplatesCanBeCopied() {
        UUID first;
        try(var ignored=ActorContextHolder.bind(ActorContext.oidc(user,a))) {
            first=profiles.ensureOrgDefaultProfile(a,com.specagent.workspace.profile.ProfileService.DEFAULT_PROFILE_ID);
        }
        try(var ignored=ActorContextHolder.bind(ActorContext.oidc(user,b))) {
            assertThat(profiles.findById(first)).isEmpty();
            assertThat(profiles.findAll()).isEmpty();
            assertThatThrownBy(()->profiles.ensureOrgDefaultProfile(a,com.specagent.workspace.profile.ProfileService.DEFAULT_PROFILE_ID))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->profiles.ensureOrgDefaultProfile(b,first)).isInstanceOf(IllegalArgumentException.class);
            UUID second=profiles.ensureOrgDefaultProfile(b,com.specagent.workspace.profile.ProfileService.DEFAULT_PROFILE_ID);
            assertThat(second).isNotEqualTo(first);
            assertThat(profiles.findAll()).extracting(com.specagent.workspace.profile.Profile::id).containsExactly(second);
            assertThat(profiles.findByName(profiles.findById(second).orElseThrow().name()).orElseThrow().id()).isEqualTo(second);
        }
    }
    @Test void searchEnvironmentFallbackIsLocalOnlyAndExplicitOrganizationKeysNeverMix() {
        var search=new com.specagent.assistant.config.SearchSettings(jdbc,crypto,"synthetic-installation-key",configurationScope);
        try(var ignored=ActorContextHolder.bind(ActorContext.oidc(user,a))) {
            assertThat(search.snapshot().configured()).isFalse();
            assertThat(search.view().get("environmentAvailable")).isEqualTo(false);
            assertThatThrownBy(()->search.save(true,null,true,0L)).isInstanceOf(IllegalArgumentException.class);
            search.save(true,"synthetic-search-a",false,0L);
        }
        try(var ignored=ActorContextHolder.bind(ActorContext.oidc(user,b))) {
            assertThat(search.snapshot().configured()).isFalse();
            assertThat(search.snapshot().revision()).isZero();
            search.save(true,"synthetic-search-b",false,0L);
        }
        try(var ignored=ActorContextHolder.bind(ActorContext.oidc(user,a))) {
            assertThat(search.snapshot().key()).isEqualTo("synthetic-search-a");
            search.clear(1L);
            assertThat(search.snapshot().key()).isNull();
        }
        try(var ignored=ActorContextHolder.bind(ActorContext.oidc(user,b))) {
            assertThat(search.snapshot().key()).isEqualTo("synthetic-search-b");
            assertThat(search.snapshot().revision()).isEqualTo(1L);
        }
    }
    @Test void searchProbeAndSaveRequireOrganizationCredentialManagement() {
        var search=new com.specagent.assistant.config.SearchSettings(jdbc,crypto,"",configurationScope);
        try(var ignored=ActorContextHolder.bind(ActorContext.oidc(member,a))) {
            assertThatThrownBy(search::requireManagement).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->search.save(true,"synthetic-key",false,0L)).isInstanceOf(AccessDeniedException.class);
        }
    }
    private void save(String key) {
        settings.upsert(new OpenCodeSettings(key,"test","test-model",Instant.now(),Instant.now()));
    }
    @Test void switchingOrganizationsNeverFallsBackToAnotherOrganizationsKeyOrActiveTarget() {
        UUID provider=UUID.randomUUID();
        try(var ignored=ActorContextHolder.bind(ActorContext.oidc(user,a))) {
            save("synthetic-a-key");
            providers.insert(new ModelProviderRecord(provider,com.specagent.model.contract.ModelProvider.CUSTOM,
                    "Only A","CHAT_COMPLETIONS","https://example.test/v1","synthetic-provider-a",
                    "test","model-a","MANUAL",1L,null,0,Instant.now(),Instant.now(),null));
            active.setActive("CUSTOM",provider);
        }
        try(var ignored=ActorContextHolder.bind(ActorContext.oidc(user,b))) {
            assertThat(settings.find()).isEmpty();
            assertThat(providers.findAll()).isEmpty();
            assertThat(providers.findById(provider)).isEmpty();
            assertThat(active.find()).isEmpty();
            assertThatThrownBy(()->active.setActive("CUSTOM",provider)).isInstanceOf(AccessDeniedException.class);
            providers.delete(provider);
            save("synthetic-b-key");
            assertThat(settings.find().orElseThrow().apiKey()).isEqualTo("synthetic-b-key");
        }
        try(var ignored=ActorContextHolder.bind(ActorContext.oidc(user,a))) {
            assertThat(settings.find().orElseThrow().apiKey()).isEqualTo("synthetic-a-key");
            assertThat(providers.findById(provider)).isPresent();
            assertThat(active.find().orElseThrow().activeProviderId()).isEqualTo(provider);
        }
    }
    @Test void memberCannotWriteAndDisabledMembershipCannotRead() {
        try(var ignored=ActorContextHolder.bind(ActorContext.oidc(member,a))) {
            assertThatThrownBy(()->save("synthetic-forbidden")).isInstanceOf(AccessDeniedException.class);
            jdbc.update("UPDATE organization_members SET status='DISABLED' WHERE org_id=? AND user_id=?",a,member);
            assertThatThrownBy(settings::find).isInstanceOf(AccessDeniedException.class);
        }
    }
    @Test void ciphertextCannotBeCopiedToAnotherOrganizationRowOrPurpose() {
        try(var ignored=ActorContextHolder.bind(ActorContext.oidc(user,a))) {save("synthetic-bound-key");}
        String ciphertext=jdbc.queryForObject("SELECT api_key FROM opencode_settings WHERE org_id=?",String.class,a);
        assertThat(ciphertext).startsWith("enc:v2:");
        assertThat(crypto.decryptScoped(ciphertext,a,"opencode_settings",1)).isEqualTo("synthetic-bound-key");
        assertThatThrownBy(()->crypto.decryptScoped(ciphertext,b,"opencode_settings",1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->crypto.decryptScoped(ciphertext,a,"openrouter_settings",1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->crypto.decryptScoped(ciphertext,a,"opencode_settings",2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->crypto.decrypt(ciphertext)).isInstanceOf(IllegalStateException.class);
    }
    @Test void startupMigrationUpdatesEachOrganizationsSingletonIndependentlyAndIsIdempotent() {
        jdbc.update("INSERT INTO opencode_settings(org_id,singleton_id,api_key,masked_suffix,selected_model) VALUES (?,1,?,'test','test-model')",a,"synthetic-plain-a");
        jdbc.update("INSERT INTO opencode_settings(org_id,singleton_id,api_key,masked_suffix,selected_model) VALUES (?,1,?,'test','test-model')",b,crypto.encrypt("synthetic-v1-b"));
        migrator.run(null);
        try(var ignored=ActorContextHolder.bind(ActorContext.oidc(user,a))) {
            assertThat(settings.find().orElseThrow().apiKey()).isEqualTo("synthetic-plain-a");
        }
        try(var ignored=ActorContextHolder.bind(ActorContext.oidc(user,b))) {
            assertThat(settings.find().orElseThrow().apiKey()).isEqualTo("synthetic-v1-b");
        }
        String before=jdbc.queryForObject("SELECT api_key FROM opencode_settings WHERE org_id=?",String.class,a);
        migrator.run(null);
        assertThat(jdbc.queryForObject("SELECT api_key FROM opencode_settings WHERE org_id=?",String.class,a)).isEqualTo(before);
    }
}
