package com.specagent.capability;

import com.specagent.access.AccessDeniedException;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

/** Observe an already requested project rebuild. This never submits embedding work,
 * activates an index, or accepts an arbitrary generation ID from the planner. */
@Component
public class RetrievalAwaitGenerationCapability implements DeferredCapabilityAdapter,InternalCapabilityAdapter {
    public static final String ID="retrieval.await_generation";
    private final RebuildWaitRepository waits;
    public RetrievalAwaitGenerationCapability(RebuildWaitRepository waits) { this.waits=waits; }
    @Override public Duration completionTimeout() { return Duration.ofHours(1); }
    @Override public CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(ID,"1",
                "等待当前项目已请求的检索重建代际就绪；不启动重建、不激活索引。无参数，Host 绑定具体代际及重试代次。",
                Map.of("type","object","properties",Map.of(),"additionalProperties",false),
                Map.of("state",Map.of("type","string")),true,SideEffectClass.NONE,List.of(),List.of());
    }
    @Override public void requireAuthorizedInvocation(CapabilityInvocation invocation) {
        if(!invocation.arguments().isEmpty()) throw new IllegalArgumentException("Rebuild wait accepts no external IDs");
        waits.authorize(invocation.runId(),invocation.projectId());
    }
    @Override public void requireAuthorizedCompletion(CapabilityInvocationRecord invocation) {
        var actor=waits.authorize(invocation.runId(),invocation.projectId());
        var task=waits.bound(invocation.id());
        if(task.isEmpty() && invocation.status()==CapabilityResult.Status.FAILED) return; // no-task failure, no private data
        if(task.isEmpty() || !invocation.projectId().equals(task.get().project()) || !actor.orgId().equals(task.get().org()))
            throw AccessDeniedException.notVisible("Rebuild wait task not visible");
    }
    @Override public CapabilityResult invoke(CapabilityInvocation invocation) {
        requireAuthorizedInvocation(invocation);
        var actor=waits.authorize(invocation.runId(),invocation.projectId());
        var selected=waits.select(invocation.projectId(),actor.orgId());
        if(selected.isEmpty()) return CapabilityResult.failed(invocation.invocationId(),invocation.invocationKey(),ID,"REBUILD_NOT_FOUND");
        var task=selected.get();
        if(!invocation.projectId().equals(task.project()) || !actor.orgId().equals(task.org()))
            throw AccessDeniedException.notVisible("Rebuild wait task not visible");
        waits.bind(invocation.invocationId(),task);
        return result(invocation.invocationId(),invocation.invocationKey(),task,
                "READY".equals(task.state())?CapabilityResult.Status.SUCCEEDED:CapabilityResult.Status.PENDING);
    }
    @Override public void validateCompletion(CapabilityInvocationRecord invocation,CapabilityResult completion) {
        var task=waits.bound(invocation.id()).orElseThrow(()->new IllegalArgumentException("Missing readiness binding"));
        var status=terminalStatus(task.state());
        if(!completion.equals(result(invocation.id(),invocation.invocationKey(),task,status)))
            throw new IllegalArgumentException("Readiness completion does not match committed task outcome");
    }
    static CapabilityResult.Status terminalStatus(String state) {
        if(state==null) throw new IllegalArgumentException("Readiness task has no committed terminal outcome");
        return switch(state) {
            case "READY","ACTIVE" -> CapabilityResult.Status.SUCCEEDED;
            case "FAILED","DISCARDED" -> CapabilityResult.Status.FAILED;
            default -> throw new IllegalArgumentException("Unknown readiness outcome");
        };
    }
    static CapabilityResult result(UUID id,String key,RebuildWaitRepository.Task task,CapabilityResult.Status status) {
        String state=task.state()==null?"PENDING":task.state();
        // Never expose source text, profile credentials, or arbitrary worker exception messages.
        return new CapabilityResult(id,key,ID,status,Map.of("state",state),List.of(),
                Map.of("kind","RETRIEVAL_GENERATION_READINESS","generationId",task.generation().toString(),
                        "readinessRevision",task.revision()),List.of());
    }
}
