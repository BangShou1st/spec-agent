package com.specagent.architecture;

import archfixture.SampleCreateProjectRequest;
import archfixture.SampleLeakyResponse;
import archfixture.ComposedPayloadController;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 文件名:HttpBoundaryGateFixtureTest.java
 *
 * 测试目标:证明 HTTP 边界门禁真实有效。合成夹具(位于 {@code archfixture}
 * 包,刻意放在 com.specagent 之外,使生产规则平时看不到它们)验证两条规则
 * 确实会拒绝它们应该捕获的违规,且编排角色在预期范围内豁免。
 *
 * 夹具引用了真实的生产模型类型,使"模型内部类型"规则在真实的包谓词下得到检验。
 */
class HttpBoundaryGateFixtureTest {

    private static final JavaClasses FIXTURES =
            new ClassFileImporter().importPackages("archfixture");

    @Test
    void coreDependenceOnHttpOnlyDtosIsRejected() {
        AssertionError violation = catchThrowableOfType(
                () -> ArchitectureTests.coreMustNotDependOnHttpOnlyDtos(FIXTURES).check(FIXTURES),
                AssertionError.class);

        assertThat(violation).as("core -> HTTP DTO must be rejected").isNotNull();
        assertThat(violation.getMessage())
                .contains("SampleCoreService")
                .contains("SampleCreateProjectRequest");
        // 编排角色不应被标记:它允许引用 HTTP DTO
        assertThat(violation.getMessage())
                .as("orchestration role is exempt from the core rule")
                .doesNotContain("SampleOrchestrationCommandService");
    }

    @Test
    void httpDtoCarryingModelInternalsIsRejected() {
        AssertionError violation = catchThrowableOfType(
                () -> ArchitectureTests.httpSurfaceMustNotReferenceModelInternals(FIXTURES).check(FIXTURES),
                AssertionError.class);

        assertThat(violation).as("HTTP DTO -> model internals must be rejected").isNotNull();
        assertThat(violation.getMessage())
                .contains("SampleLeakyResponse")
                .contains("ModelOutputContract");
        // 普通控制器不应被标记:它只引用自己的 DTO
        assertThat(violation.getMessage())
                .as("the controller itself stays clean")
                .doesNotContain("SampleProjectController.java:");
    }

    @Test
    void fixtureSetupCoversBothRules() {
        // 守护夹具本身:DTO 角色必须已通过控制器引用识别出 Request 与 Response
        // 两个夹具,否则上面的拒绝测试会空洞通过。
        JavaClasses classes = FIXTURES;
        assertThat(ArchitectureTests.httpOnlyDtoRole(classes).test(
                classes.get(SampleCreateProjectRequest.class))).isTrue();
        assertThat(ArchitectureTests.httpOnlyDtoRole(classes).test(
                classes.get(SampleLeakyResponse.class))).isTrue();
    }

    @Test
    void composedAndNestedPayloadsCarryingModelInternalsAreRejected() {
        AssertionError violation = catchThrowableOfType(
                () -> ArchitectureTests.httpSurfaceMustNotReferenceModelInternals(FIXTURES).check(FIXTURES),
                AssertionError.class);

        assertThat(violation).isNotNull();
        assertThat(violation.getMessage()).contains(
                "ComposedPayloadController$InnerResponse",
                "ComposedPayloadController$Details",
                "ComposedPayloadController$GenericDetails",
                "ComposedPayloadController$GetterDetails",
                "ModelOutputContract");
    }

    @Test
    void coreDependenceOnComposedRequestIsRejected() {
        AssertionError violation = catchThrowableOfType(
                () -> ArchitectureTests.coreMustNotDependOnHttpOnlyDtos(FIXTURES).check(FIXTURES),
                AssertionError.class);

        assertThat(violation).isNotNull();
        assertThat(violation.getMessage())
                .contains("ComposedPayloadCoreService", "ComposedPayloadController$ChildRequest");
    }

    @Test
    void internalHelpersStayOutsideThePayloadRoleAndViewsStayOutsideHttpOnlyDtos() {
        var dtoRole = ArchitectureTests.httpOnlyDtoRole(FIXTURES);
        assertThat(dtoRole.test(FIXTURES.get(ComposedPayloadController.ChildRequest.class))).isTrue();
        assertThat(dtoRole.test(FIXTURES.get(ComposedPayloadController.InternalRequest.class))).isFalse();
        assertThat(dtoRole.test(FIXTURES.get(ComposedPayloadController.ReadView.class))).isFalse();

        var result = ArchitectureTests.httpSurfaceMustNotReferenceModelInternals(FIXTURES).evaluate(FIXTURES);
        assertThat(result.getFailureReport().toString())
                .doesNotContain("ComposedPayloadController$InternalRequest");
    }
}
