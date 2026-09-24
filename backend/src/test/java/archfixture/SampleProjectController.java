package archfixture;

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
}
