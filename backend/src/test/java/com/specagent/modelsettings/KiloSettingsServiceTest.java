package com.specagent.modelsettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specagent.model.contract.ModelGatewayErrorCategory;
import com.specagent.model.contract.RuntimeKiloSettings;
import com.specagent.model.provider.ModelProviderException;
import com.sharedlibs.kilogateway.KiloGatewayClient;
import com.sharedlibs.kilogateway.KiloGatewayException;
import com.sharedlibs.kilogateway.autoconfigure.KiloGatewayProperties;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 文件名:KiloSettingsServiceTest.java
 *
 * 测试目标:验证 Kilo Gateway 设置服务:目录发现(可执行列表 + 免费子集)、
 * 免费模型无需密钥保存、付费模型缺密钥时 fail-closed、保存前的可达性探测,
 * 以及运行时设置投影(绝不泄露密钥)。探测客户端被替换为桩,故不联网。
 */
class KiloSettingsServiceTest {

    private final KiloSettingsRepository repository = mock(KiloSettingsRepository.class);
    private final KiloGatewayClient catalogClient = mock(KiloGatewayClient.class);
    private final KiloGatewayClient probeClient = mock(KiloGatewayClient.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private KiloSettingsService service() {
        KiloGatewayProperties properties = new KiloGatewayProperties();
        return new KiloSettingsService(repository, catalogClient, properties, mapper) {
            @Override
            protected KiloGatewayClient probeClient(KiloGatewayProperties probeProperties) {
                return KiloSettingsServiceTest.this.probeClient;
            }
        };
    }

    private void catalog(String... ids) {
        ObjectNode root = mapper.createObjectNode();
        var data = root.putArray("data");
        for (String id : ids) {
            data.addObject().put("id", id);
        }
        when(catalogClient.models()).thenReturn(root);
    }

    private static KiloSettings stored(String apiKey, String suffix, String model) {
        Instant now = Instant.now();
        return new KiloSettings(apiKey, suffix, model, now, now);
    }

    @Test
    void listModelsExposesExecutableListAndFreeSubset() {
        catalog("b-paid", "a:free");

        KiloSettingsService.CandidateModels models = service().listModels();

        assertThat(models.allModels()).containsExactly("a:free", "b-paid");
        assertThat(models.freeModels()).containsExactly("a:free");
        // 有付费模型时优先用它证明密钥链路可达。
        assertThat(models.recommendedProbeModel()).isEqualTo("b-paid");
    }

    @Test
    void freeModelSavesWithoutAKeyAndIsProbedAnonymously() {
        catalog("a:free");
        when(repository.find()).thenReturn(Optional.empty());
        when(probeClient.chat(any())).thenReturn(mapper.createObjectNode());

        service().save(null, "a:free");

        ArgumentCaptor<KiloSettings> captor = ArgumentCaptor.forClass(KiloSettings.class);
        verify(repository).upsert(captor.capture());
        assertThat(captor.getValue().apiKey()).isNull();
        assertThat(captor.getValue().maskedSuffix()).isNull();
        assertThat(captor.getValue().selectedModel()).isEqualTo("a:free");
        verify(probeClient).chat(any());
    }

    @Test
    void paidModelWithoutAKeyIsRejectedBeforeAnyWrite() {
        catalog("b-paid");

        assertThatThrownBy(() -> service().save(null, "b-paid"))
                .isInstanceOf(ModelProviderException.class)
                .extracting(error -> ((ModelProviderException) error).gatewayCategory())
                .isEqualTo(ModelGatewayErrorCategory.NOT_CONFIGURED);
        verify(repository, never()).upsert(any());
    }

    @Test
    void modelMissingFromTheLiveCatalogIsRejected() {
        catalog("a:free");

        assertThatThrownBy(() -> service().save(null, "unknown-model"))
                .isInstanceOf(ModelProviderException.class)
                .extracting(error -> ((ModelProviderException) error).gatewayCategory())
                .isEqualTo(ModelGatewayErrorCategory.INVALID_MODEL);
        verify(repository, never()).upsert(any());
    }

    @Test
    void probeMapsAnAuthenticationFailureToTheAuthenticationCategory() {
        catalog("a:free");
        when(probeClient.chat(any())).thenThrow(new KiloGatewayException(403, "kilo", "forbidden"));

        assertThatThrownBy(() -> service().probe("candidate-key"))
                .isInstanceOf(ModelProviderException.class)
                .extracting(error -> ((ModelProviderException) error).gatewayCategory())
                .isEqualTo(ModelGatewayErrorCategory.AUTHENTICATION);
    }

    @Test
    void changeModelReusesTheStoredCredential() {
        when(repository.find()).thenReturn(Optional.of(stored("stored-key", "1234", "a:free")));
        catalog("a:free", "b-paid");
        when(probeClient.chat(any())).thenReturn(mapper.createObjectNode());

        service().changeModel("b-paid");

        ArgumentCaptor<KiloSettings> captor = ArgumentCaptor.forClass(KiloSettings.class);
        verify(repository).upsert(captor.capture());
        assertThat(captor.getValue().apiKey()).isEqualTo("stored-key");
        assertThat(captor.getValue().selectedModel()).isEqualTo("b-paid");
    }

    @Test
    void statusMasksTheStoredSuffix() {
        when(repository.find()).thenReturn(Optional.of(stored("k", "1234", "a:free")));

        KiloSettingsStatus status = service().status();

        assertThat(status.configured()).isTrue();
        assertThat(status.maskedKey()).isEqualTo("••••1234");
        assertThat(status.selectedModel()).isEqualTo("a:free");
    }

    @Test
    void requireRuntimeSettingsReturnsTheStoredPairWithoutLeakingTheKey() {
        when(repository.find()).thenReturn(Optional.of(stored("super-secret-key", "1234", "a:free")));

        RuntimeKiloSettings runtime = service().requireRuntimeSettings();

        assertThat(runtime.apiKey()).isEqualTo("super-secret-key");
        assertThat(runtime.selectedModel()).isEqualTo("a:free");
        assertThat(runtime.toString()).doesNotContain("super-secret-key");
    }

    @Test
    void requireRuntimeSettingsFailsClosedWhenUnconfigured() {
        when(repository.find()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().requireRuntimeSettings())
                .isInstanceOf(ModelProviderException.class)
                .extracting(error -> ((ModelProviderException) error).gatewayCategory())
                .isEqualTo(ModelGatewayErrorCategory.NOT_CONFIGURED);
    }
}
