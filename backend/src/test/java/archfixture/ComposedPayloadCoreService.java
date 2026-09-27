package archfixture;

/**
 * 文件名:ComposedPayloadCoreService.java
 *
 * 测试夹具:核心服务依赖了嵌套在其他 HTTP 请求里的请求类型,
 * 用于验证架构门禁禁止这种"深层 HTTP 载荷泄漏"。
 */
public class ComposedPayloadCoreService {
    private final ComposedPayloadController.ChildRequest request;

    public ComposedPayloadCoreService(ComposedPayloadController.ChildRequest request) {
        this.request = request;
    }
}
