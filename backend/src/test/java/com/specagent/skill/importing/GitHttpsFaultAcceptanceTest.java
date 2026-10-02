package com.specagent.skill.importing;

import com.specagent.common.network.OutboundNetworkPolicy;
import com.specagent.skill.config.SkillProperties;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Actual public HTTPS transport; no mock Git, local TLS relaxation or business writes. */
@EnabledIfEnvironmentVariable(named="SPEC_AGENT_GA_LIVE_GIT",matches="true")
class GitHttpsFaultAcceptanceTest {
    private final String url=System.getenv().getOrDefault("SPEC_AGENT_GA_LIVE_GIT_URL","https://github.com/obra/superpowers.git");
    private SkillProperties properties() { var properties=new SkillProperties(); properties.setGitProxy(System.getenv().getOrDefault("SPEC_AGENT_GIT_PROXY","AUTO")); return properties; }
    Set<Path> temporaryDirectories() throws Exception {
        try(var files=Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(p->p.getFileName().toString().startsWith("spec-agent-git-skill-")).collect(java.util.stream.Collectors.toSet());
        }
    }
    @Test void missingRefWireBudgetCancellationAndAbsoluteTimeoutCleanEveryNewTemporaryDirectory() throws Exception {
        Set<Path> before=temporaryDirectories(); var results=new LinkedHashMap<String,Object>();
        var defaults=properties(); var importer=new GitSkillImporter(defaults,new OutboundNetworkPolicy());
        assertThrows(SkillImportException.class,()->importer.fetchTree(url,"refs/heads/spec-agent-nonexistent-"+UUID.randomUUID()));
        assertEquals(before,temporaryDirectories()); results.put("missingRef","FAILED_CLEAN");
        var limited=properties(); limited.setGitCloneBytes(128);
        assertThrows(SkillImportException.class,()->new GitSkillImporter(limited,new OutboundNetworkPolicy()).fetchTree(url,null));
        assertEquals(before,temporaryDirectories()); results.put("wireBudget","FAILED_CLEAN");
        var active=new AtomicBoolean(true);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var task=executor.submit(()->importer.fetchTree(url,null,active::get));
            long wait=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(temporaryDirectories().equals(before) && System.nanoTime()<wait) Thread.sleep(10);
            assertFalse(temporaryDirectories().equals(before),"Cancellation must happen during actual HTTPS preparation");
            Thread.sleep(200); long cancelledAt=System.nanoTime(); active.set(false);
            assertThrows(ExecutionException.class,()->task.get(5,TimeUnit.SECONDS));
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-cancelledAt);
            assertTrue(elapsed<5000); results.put("cancelStopMs",elapsed);
        }
        assertEquals(before,temporaryDirectories()); results.put("cancel","FAILED_CLEAN");
        var timeout=properties(); timeout.setGitTimeoutSeconds(1); long start=System.nanoTime();
        assertThrows(SkillImportException.class,()->new GitSkillImporter(timeout,new OutboundNetworkPolicy()).fetchTree(url,null));
        long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start); assertTrue(elapsed<4000,"Absolute timeout escaped: "+elapsed);
        assertEquals(before,temporaryDirectories()); results.put("timeoutStopMs",elapsed); results.put("timeout","FAILED_CLEAN");
        results.put("recordedAt",Instant.now().toString()); results.put("boundary","actual JGit HTTPS/public remote; cancellation/timeout/faults; no model or staging business writes");
        results.put("previousDirectoriesExcluded",before.size()); results.put("newTemporaryDirectoriesRemaining",0);
        results.put("productionEngineChanged",false);
        Files.writeString(Path.of("../docs/v2/evidence/GLOBAL_ASSISTANT_REAL_GIT_FAULTS.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(results));
    }
}
