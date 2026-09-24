package archfixture;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Controller fixture that hands {@link SampleCreateProjectRequest} and
 * {@link SampleLeakyResponse}, making both members of the HTTP DTO role.
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
