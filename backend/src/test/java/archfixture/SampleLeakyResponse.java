package archfixture;

import com.specagent.model.contract.ModelOutputContract;

/**
 * 文件名:SampleLeakyResponse.java
 *
 * 违规样例夹具,对应规则 {@code httpSurfaceMustNotReferenceModelInternals}:
 * 一个仅限 HTTP 层的 DTO(以 Response 为后缀且被控制器引用)却携带了模型内部类型。
 */
public class SampleLeakyResponse {

    private final ModelOutputContract contract;

    public SampleLeakyResponse(ModelOutputContract contract) {
        this.contract = contract;
    }
}
