package archfixture;

/**
 * 文件名:SampleOrchestrationCommandService.java
 *
 * 非违规样例夹具:应用层编排服务(命名角色为 *CommandService)允许引用
 * HTTP DTO——以此证明门禁中的角色区分(编排服务豁免,核心服务不豁免)。
 */
public class SampleOrchestrationCommandService {

    private final SampleCreateProjectRequest request;

    public SampleOrchestrationCommandService(SampleCreateProjectRequest request) {
        this.request = request;
    }
}
