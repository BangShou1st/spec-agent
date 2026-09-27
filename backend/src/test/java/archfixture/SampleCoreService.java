package archfixture;

/**
 * 文件名:SampleCoreService.java
 *
 * 违规样例夹具,对应规则 {@code coreMustNotDependOnHttpOnlyDtos}:
 * 核心服务持有仅限 HTTP 层的 DTO 引用,应被架构门禁拦截。
 */
public class SampleCoreService {

    private final SampleCreateProjectRequest leakedRequest;

    public SampleCoreService(SampleCreateProjectRequest leakedRequest) {
        this.leakedRequest = leakedRequest;
    }
}
