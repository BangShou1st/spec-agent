package com.specagent.testing;

import com.specagent.agent.broker.AgentBrainProperties;
import com.specagent.retrieval.protocol.*;
import com.specagent.workspace.project.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.*;

/** Real Python + host RPC fixture. Callers must not wrap RPC-visible data in a test transaction. */
public abstract class SharedPythonRetrievalFixture {
    @Autowired AgentBrainProperties brain;
    @Autowired ProjectService fixtureProjects;
    @Autowired ProjectDeletionService deletion;
    @Autowired RetrievalStore store;
    @Autowired SharedRetrievalHost retrieval;
    @LocalServerPort int port;
    private Process process;
    private String oldBase;
    private final List<UUID> ownedProjects=new ArrayList<>();

    @BeforeEach void startSharedPython() throws Exception {
        oldBase=brain.getBaseUrl();
        int pythonPort;
        try(var socket=new java.net.ServerSocket(0)) { pythonPort=socket.getLocalPort(); }
        Path root=Path.of("..").toAbsolutePath().normalize();
        Path logs=root.resolve("scratch/rag-d4-r1"); Files.createDirectories(logs);
        var builder=new ProcessBuilder(PythonExecutable.locate(root),"-m","uvicorn",
            "spec_agent_brain.app:create_app","--factory","--host","127.0.0.1","--port",Integer.toString(pythonPort));
        builder.directory(root.resolve("agent-brain").toFile());
        builder.environment().put("SPEC_AGENT_BRAIN_MODEL_MODE","broker");
        builder.environment().put("SPEC_AGENT_INTERNAL_BROKER_URL","http://127.0.0.1:"+port+"/internal/v1/model-inference");
        builder.environment().put("SPEC_AGENT_BRAIN_INTERNAL_SECRET",brain.getInternalSecret());
        // Deterministic lexical-degradation fixture: no live embedding or paid model calls.
        // Python fusion, authorized store RPC and source validation remain real.
        builder.environment().put("SPEC_AGENT_OLLAMA_URL","http://127.0.0.1:1");
        builder.redirectErrorStream(true).redirectOutput(Files.createTempFile(logs,"retrieval-",".log").toFile());
        process=builder.start();
        brain.setBaseUrl("http://127.0.0.1:"+pythonPort);
        try(var http=HttpClient.newHttpClient()) {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(System.nanoTime()<until && process.isAlive()) {
                try {
                    if(http.send(HttpRequest.newBuilder(URI.create(brain.getBaseUrl()+"/health"))
                        .timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200) return;
                } catch(Exception unavailable) { }
                Thread.sleep(50);
            }
        }
        fail("Matching Python fixture did not become ready");
    }

    protected Project createProject(String title) {
        Project project=fixtureProjects.createProject(title); ownedProjects.add(project.id()); return project;
    }

    protected void projectResources(UUID project) {
        UUID generation=store.ensureProjectGeneration(project);
        int attempts=0;
        while(retrieval.splitOneSource(project,generation))
            assertThat(++attempts).as("bounded resource task drain").isLessThan(256);
    }

    @AfterEach void stopAndCleanOwnedFixture() throws Exception {
        try {
            for(UUID project:ownedProjects) deletion.deleteProject(project);
        } finally {
            ownedProjects.clear();
            if(oldBase!=null) brain.setBaseUrl(oldBase);
            if(process!=null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroy();
                if(!process.waitFor(5,TimeUnit.SECONDS)) { process.destroyForcibly(); assertThat(process.waitFor(5,TimeUnit.SECONDS)).isTrue(); }
            }
        }
    }
}
