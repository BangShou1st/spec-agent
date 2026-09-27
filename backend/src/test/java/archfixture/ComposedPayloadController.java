package archfixture;

import com.specagent.model.contract.ModelOutputContract;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * 文件名:ComposedPayloadController.java
 *
 * 测试夹具:载荷组合控制器,端点返回/接收由多个子类型组合而成的载荷,
 * 验证架构门禁能递归识别嵌套类型。所有子类型都不会被端点直接引用。
 */
public class ComposedPayloadController {

    // 控制器的实现依赖不属于 HTTP 载荷。
    private final InternalRequest internal = new InternalRequest(null);

    @GetMapping("/fixture/composed")
    public ResponseEntity<OuterResponse> get() {
        return ResponseEntity.ok(new OuterResponse(List.of(), Map.of(), null));
    }

    @PostMapping("/fixture/composed")
    public void create(@RequestBody OuterRequest request) {
    }

    @GetMapping("/fixture/view")
    public ReadView view() {
        return new ReadView("read-model");
    }

    @GetMapping("/fixture/generic")
    public Envelope<GenericDetails> generic() {
        return new Envelope<>(null);
    }

    @GetMapping("/fixture/getter")
    public GetterResponse getter() {
        return new GetterResponse();
    }

    private InternalRequest helper() {
        return internal;
    }

    public record OuterResponse(List<InnerResponse> children,
                                Map<String, ? extends Envelope<Details[]>> details,
                                OuterResponse next) { }

    public record InnerResponse(ModelOutputContract contract) { }

    // 没有 DTO 后缀:载荷覆盖不能依赖子类型的命名。
    public record Details(ModelOutputContract contract) { }

    public record GenericDetails(ModelOutputContract contract) { }

    public record Envelope<T>(T value) { }

    public record OuterRequest(ChildRequest child) { }

    public record ChildRequest(String value) { }

    public record ReadView(String value) { }

    public record InternalRequest(ModelOutputContract contract) { }

    public static class GetterResponse {
        public GetterDetails getDetails() {
            return null;
        }
    }

    public record GetterDetails(ModelOutputContract contract) { }
}
