package archfixture;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 文件名:SampleProjectController.java
 *
 * 控制器夹具:使用 {@link SampleCreateProjectRequest} 和
 * {@link SampleLeakyResponse},使两者都被归入 HTTP DTO 角色。
 */
public class SampleProjectController {

    private final SampleCreateProjectRequest request;
    private final SampleLeakyResponse leakyResponse;

    public SampleProjectController(SampleCreateProjectRequest request, SampleLeakyResponse leakyResponse) {
        this.request = request;
        this.leakyResponse = leakyResponse;
    }

    @PostMapping("/fixture/project")
    public void create(@RequestBody SampleCreateProjectRequest request) {
    }

    @GetMapping("/fixture/project")
    public SampleLeakyResponse get() {
        return leakyResponse;
    }
}
