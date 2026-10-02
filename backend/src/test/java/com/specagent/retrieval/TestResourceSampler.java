package com.specagent.retrieval;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Explicit live-evaluation sampler; never part of production or retrieval control flow. */
final class TestResourceSampler implements AutoCloseable {
    private final Process python;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{ var thread=new Thread(r,"rag-resource-sampler"); thread.setDaemon(true); return thread; });
    volatile long pythonPeak=-1,gpuPeak=-1,gpuBaseline=-1,samples;
    TestResourceSampler(Process python) { this.python=python; sample(); timer.scheduleWithFixedDelay(this::sample,1,1,TimeUnit.SECONDS); }
    private long number(List<String> command) {
        Process process=null;
        try {
            process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(3,TimeUnit.SECONDS)) { process.destroyForcibly(); return -1; }
            if(process.exitValue()!=0) return -1;
            return Long.parseLong(new String(process.getInputStream().readNBytes(4096),StandardCharsets.UTF_8).strip().split("\\R")[0]);
        } catch(Exception unavailable) { return -1; }
        finally { if(process!=null && process.isAlive()) process.destroyForcibly(); }
    }
    private void sample() {
        long gpu=number(List.of("nvidia-smi","--query-gpu=memory.used","--format=csv,noheader,nounits"));
        if(gpu>=0) { if(gpuBaseline<0) gpuBaseline=gpu; gpuPeak=Math.max(gpuPeak,gpu); }
        var ids=new ArrayList<Long>(); ids.add(python.pid()); python.descendants().map(ProcessHandle::pid).forEach(ids::add);
        String arguments=ids.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(","));
        long workingSet=number(List.of("powershell.exe","-NoProfile","-NonInteractive","-Command","(Get-Process -Id "+arguments+" -ErrorAction SilentlyContinue | Measure-Object WorkingSet64 -Sum).Sum"));
        if(workingSet>=0) pythonPeak=Math.max(pythonPeak,workingSet);
        samples++;
    }
    Map<String,Object> report() { return Map.of("pythonTreeWorkingSetObservedPeakBytes",pythonPeak,"gpuDeviceMemoryObservedPeakMiB",gpuPeak,"gpuDeviceBaselineMiB",gpuBaseline,
        "samples",samples,"scope","1-second fixed delay plus probe duration; Python launcher+descendants; whole GPU device shared with other apps; sampled observations, not instantaneous exclusive RAG peak"); }
    public void close() throws InterruptedException { timer.shutdown(); if(!timer.awaitTermination(8,TimeUnit.SECONDS)) timer.shutdownNow(); }
}
