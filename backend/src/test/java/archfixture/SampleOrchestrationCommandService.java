package archfixture;

/**
 * NON-violation fixture: an application orchestration service (name role
 * *CommandService) may reference an HTTP DTO — this proves the role
 * distinction in the gate (orchestration is exempt, core is not).
 */
public class SampleOrchestrationCommandService {

    private final SampleCreateProjectRequest request;

    public SampleOrchestrationCommandService(SampleCreateProjectRequest request) {
        this.request = request;
    }
}
