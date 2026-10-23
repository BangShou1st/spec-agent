package com.specagent.modelsettings;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.ModelProvider;
import com.specagent.model.contract.ActiveModelTargetPort;
import com.specagent.model.contract.RuntimeCustomSettings;
import com.specagent.model.provider.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** L1 connection invariants; only synthetic values and an in-memory repository. */
class ModelConnectionsOfflineTest {
    final ModelProviderRepository repository = mock(ModelProviderRepository.class);
    final ModelProviderSettingsService active = mock(ModelProviderSettingsService.class);
    final ProviderModelCatalogService catalog = mock(ProviderModelCatalogService.class);
    final CompatibilityProbeService probe = mock(CompatibilityProbeService.class);
    final ModelConnectionCapabilities capabilities = mock(ModelConnectionCapabilities.class);
    final ModelProvidersService service = new ModelProvidersService(repository,catalog,active,capabilities,
            mock(com.specagent.access.OrganizationConfigurationScope.class));
    final Map<UUID,ModelProviderRecord> rows = new HashMap<>();

    ModelConnectionsOfflineTest() {
        when(repository.findAll()).thenAnswer(i -> List.copyOf(rows.values()));
        when(repository.findById(any())).thenAnswer(i -> Optional.ofNullable(rows.get(i.getArgument(0))));
        when(repository.findByIdForUpdate(any())).thenAnswer(i -> Optional.ofNullable(rows.get(i.getArgument(0))));
        doAnswer(i -> { var row=(ModelProviderRecord)i.getArgument(0); rows.put(row.id(),row); return null; })
                .when(repository).insert(any());
        doAnswer(i -> { var row=(ModelProviderRecord)i.getArgument(0); rows.put(row.id(),row); return null; })
                .when(repository).update(any());
    }
    ModelProvidersService.Patch patch(String name, String model, String key) {
        return new ModelProvidersService.Patch(null,name,"CHAT_COMPLETIONS","http://127.0.0.1:9/v1",key,model,"MANUAL");
    }
    ModelProviderRecord validated() {
        var row=service.create(patch("A","model-a","fixture-a"));
        var saved=new ModelProviderRecord(row.id(),ModelProvider.CUSTOM,row.displayName(),row.apiFormat(),
                row.baseUrl(),row.apiKey(),row.maskedSuffix(),row.selectedModel(),row.modelSource(),1,1L,0,
                Instant.now(),Instant.now(),Instant.now());
        rows.put(saved.id(),saved); return saved;
    }
    @Test void twoCustomConnectionsNeverShareConfiguration() {
        var a=service.create(patch("A","model-a","fixture-a"));
        var b=service.create(patch("B","model-b","fixture-b"));
        service.update(a.id(),patch("A2","model-a2",null));
        assertThat(service.require(b.id()).apiKey()).isEqualTo("fixture-b");
        assertThat(service.require(b.id()).selectedModel()).isEqualTo("model-b");
        assertThat(a.id()).isNotEqualTo(b.id());
    }
    @Test void modelChangeInvalidatesQualification() {
        var a=validated(); var changed=service.update(a.id(),patch("A","model-b",null));
        assertThat(changed.configRevision()).isEqualTo(2);
        assertThat(changed.validated()).isFalse();
        assertThatThrownBy(()->service.requireActivatable(changed)).isInstanceOf(ModelProviderException.class);
    }
    @Test void renamePreservesQualification() {
        var a=validated();var changed=service.update(a.id(),patch("renamed","model-a",null));
        assertThat(changed.validated()).isTrue();assertThat(changed.configRevision()).isEqualTo(1);
    }
    @Test void activeConnectionCannotBeDeleted() {
        var a=validated();when(active.isActiveId(a.id())).thenReturn(true);
        assertThatThrownBy(()->service.delete(a.id())).isInstanceOf(IllegalArgumentException.class);
        verify(repository,never()).delete(any());
    }
    @Test void originChangeCannotReuseCredential() {
        var a=validated();
        assertThatThrownBy(()->service.update(a.id(),new ModelProvidersService.Patch(null,null,null,
                "http://127.0.0.1:10/v1",null,null,null))).isInstanceOf(ModelProviderException.class);
    }
    @Test void catalogAbsenceDoesNotDisallowManualModel() {
        var a=validated();service.validate(a.id());
        verifyNoInteractions(catalog);
        verify(capabilities).verify(a);
    }
    @Test void sameUrlModelAndRevisionRemainDifferentConnections() {
        var a=new RuntimeCustomSettings("CHAT_COMPLETIONS","http://localhost:9/v1","fixture","same",1,UUID.randomUUID());
        var b=new RuntimeCustomSettings(a.apiFormat(),a.baseUrl(),a.apiKey(),a.selectedModel(),1,UUID.randomUUID());
        assertThat(new ActiveModelTargetPort.Target("CUSTOM","same",a).bindingFingerprint())
                .isNotEqualTo(new ActiveModelTargetPort.Target("CUSTOM","same",b).bindingFingerprint());
        assertThat(a.toString()).doesNotContain("fixture");
    }
    @Test void frozenConnectionResolvesAfterSwitchAndFailsAfterDeletion() {
        var a=validated();var b=service.create(patch("B","model-b","fixture-b"));
        var custom=new CustomProviderSettingsService(mock(CustomProviderSettingsRepository.class),
                new ObjectMapper(),mock(ProtocolAdapterRegistry.class),probe);
        custom.setConnections(repository,active);
        when(active.activeProviderId()).thenReturn(b.id());
        String reference="custom-provider:"+a.id()+":1";
        assertThat(custom.requireRuntimeSettings(reference).apiKey()).isEqualTo("fixture-a");
        rows.remove(a.id());
        assertThatThrownBy(()->custom.requireRuntimeSettings(reference)).hasMessage("GA_MODEL_BINDING_MISMATCH");
    }
    @Test void deprecatedProviderCodeIsRejected() {
        assertThatThrownBy(()->ModelProvider.fromCode("OPENROUTER")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void atomicSelectionNeverMixesProviderCodeWithAnotherConnectionsUuid() {
        var a=validated();var b=service.create(patch("B","model-b","fixture-b"));
        when(active.selection()).thenReturn(new ModelProviderSettingsService.Selection(ModelProvider.CUSTOM,a.id()));
        when(active.activeProviderId()).thenReturn(b.id());
        var custom=new CustomProviderSettingsService(mock(CustomProviderSettingsRepository.class),
                new ObjectMapper(),mock(ProtocolAdapterRegistry.class),probe);
        custom.setConnections(repository,active);
        var targets=new ActiveModelTargetService(active,mock(com.specagent.model.contract.OpenCodeRuntimeSettingsPort.class),
                mock(com.specagent.model.contract.KiloRuntimeSettingsPort.class),custom);
        var target=targets.activeTarget().orElseThrow();
        assertThat(((RuntimeCustomSettings)target.runtimeSettings()).providerId()).isEqualTo(a.id());
        verify(active,never()).activeProviderId();
    }
}
