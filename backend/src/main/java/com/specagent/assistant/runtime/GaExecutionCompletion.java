package com.specagent.assistant.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.specagent.assistant.conversation.*;
import com.specagent.common.Hashes;
import com.specagent.model.contract.GaModelContract;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Converts bounded terminal framework facts into the existing product event/message transaction. */
@Service
public class GaExecutionCompletion {
    private final GaExecutionStore executions;
    private final GlobalAssistantRunRepository runs;
    private final GlobalAssistantRunLifecycleService lifecycle;
    private final GlobalAssistantRunEventService events;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public GaExecutionCompletion(GaExecutionStore executions, GlobalAssistantRunRepository runs,
            GlobalAssistantRunLifecycleService lifecycle, GlobalAssistantRunEventService events, JdbcTemplate jdbc) {
        this.executions=executions; this.runs=runs; this.lifecycle=lifecycle; this.events=events; this.jdbc=jdbc;
    }
    public static final class StreamState {
        private final AnswerStreamPublisher publisher;
        private final StringBuilder text=new StringBuilder();
        private final Set<UUID> closed=new HashSet<>();
        private UUID call;
        private int sequence;
        private StreamState(AnswerStreamPublisher publisher) { this.publisher=publisher; }
    }
    public StreamState newStream(UUID run) {
        return new StreamState(new AnswerStreamPublisher(events,run,System.nanoTime()));
    }
    /** Each draft append and its receipt commit under the same run lock as terminalization. */
    @Transactional
    public void progress(GaExecutionPreparation.Prepared prepared,String line,StreamState state) {
        var run=runs.lockById(prepared.scope().runId());
        if(!run.status().isActive()) throw new IllegalStateException("GA_EXECUTION_FENCE");
        executions.authorize(prepared.scope());
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT executor_claimed FROM ga_executions WHERE run_id=?",Boolean.class,run.id())))
            throw new IllegalStateException("GA_EXECUTOR_NOT_CLAIMED");
        JsonNode event=parse(line);
        validateEvent(prepared,event,++state.sequence);
        String kind=event.path("type").textValue();
        JsonNode payload=event.path("payload");
        if("STATUS".equals(kind)) {
            fields(payload,Set.of("stage"));
            if(state.sequence!=1 || !"EXECUTING".equals(payload.path("stage").textValue())) throw new IllegalArgumentException("GA stage");
        } else if(Set.of("TEXT_DELTA","TEXT_RESET").contains(kind)) {
            fields(payload,"TEXT_DELTA".equals(kind)?Set.of("callId","text"):Set.of("callId"));
            UUID call=UUID.fromString(payload.path("callId").textValue());
            var admitted=jdbc.queryForList("SELECT status FROM ga_execution_calls WHERE run_id=? AND call_id=? AND kind='MODEL'",
                    String.class,run.id(),call);
            if(admitted.size()!=1 || !Set.of("RESERVED","SUCCEEDED").contains(admitted.getFirst()) || state.closed.contains(call))
                throw new IllegalArgumentException("GA draft call not admitted");
            if(state.call==null) { state.call=call; state.publisher.nextGeneration(); }
            if(!state.call.equals(call)) throw new IllegalArgumentException("GA draft call transition");
            if("TEXT_DELTA".equals(kind)) {
                String text=payload.path("text").textValue();
                if(text==null || text.isEmpty() || state.text.length()+text.length()>131072) throw new IllegalArgumentException("GA draft limit");
                state.text.append(text); state.publisher.accept(text); state.publisher.finish();
            } else {
                verifyReset(run.id(),call);
                state.publisher.discard(); state.closed.add(call); state.call=null; state.text.setLength(0);
            }
        } else throw new IllegalArgumentException("GA progress type");
        receipt(run.id(),event,line);
    }
    @Transactional
    public void accept(GaExecutionPreparation.Prepared prepared, List<String> lines) {
        accept(prepared,lines,null);
    }
    @Transactional
    public void accept(GaExecutionPreparation.Prepared prepared, List<String> lines, StreamState state) {
        var scope=prepared.scope();
        var run=runs.lockById(scope.runId());
        // A duplicate completed dispatch has no authority to emit another public event.
        if(!run.status().isActive()) return;
        if(run.cancelRequestedAt()!=null) { lifecycle.cancelAndTerminalize(run.id()); return; }
        var binding=executions.authorize(scope);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT executor_claimed FROM ga_executions WHERE run_id=?",Boolean.class,run.id())))
            throw new IllegalStateException("GA_EXECUTOR_NOT_CLAIMED");
        JsonNode terminal=null;
        Set<UUID> identities=new HashSet<>();
        int sequence=0;
        for(String line:lines) {
            JsonNode event;
            try { event=mapper.readTree(line); } catch(Exception ex) { throw new IllegalArgumentException("GA event JSON"); }
            fields(event,Set.of("protocolVersion","runId","executionEpoch","eventId","sequence","type","payload"));
            if(!"ga-execution-event.v1".equals(event.path("protocolVersion").textValue())
                    || !run.id().toString().equals(event.path("runId").textValue())
                    || !event.path("executionEpoch").isIntegralNumber() || !event.path("executionEpoch").canConvertToLong()
                    || event.path("executionEpoch").longValue()!=scope.epoch()
                    || !event.path("sequence").isIntegralNumber() || !event.path("sequence").canConvertToInt()
                    || event.path("sequence").intValue()!=++sequence
                    || sequence>8192 || terminal!=null) throw new IllegalArgumentException("GA event scope/order");
            UUID identity=UUID.fromString(event.path("eventId").textValue());
            if(!identities.add(identity)) throw new IllegalArgumentException("GA event identity");
            String kind=event.path("type").textValue();
            JsonNode payload=event.path("payload");
            if("STATUS".equals(kind)) {
                fields(payload,Set.of("stage"));
                if(sequence!=1 || !"EXECUTING".equals(payload.path("stage").textValue())) throw new IllegalArgumentException("GA event stage");
            } else if(Set.of("TEXT_DELTA","TEXT_RESET").contains(kind)) {
                if(state==null || sequence>state.sequence) throw new IllegalArgumentException("GA unconsumed draft");
                fields(payload,"TEXT_DELTA".equals(kind)?Set.of("callId","text"):Set.of("callId"));
            } else if(Set.of("COMPLETED","USER_INPUT_REQUIRED").contains(kind)) {
                fields(payload,Set.of("text"));
                String text=payload.path("text").textValue();
                if(text==null || text.isBlank() || text.length()>131072) throw new IllegalArgumentException("GA final text");
                verifyOutput(scope,kind,text); terminal=event;
            } else if("FAILED".equals(kind)) {
                fields(payload,Set.of("errorCode"));
                if(!Set.of("GA_EXECUTION_FAILED","GA_EXECUTION_PROTOCOL_ERROR","GA_REPEATED_TOOL_CALL")
                        .contains(payload.path("errorCode").textValue())) throw new IllegalArgumentException("GA error code");
                terminal=event;
            } else throw new IllegalArgumentException("GA event type");
            receipt(run.id(),event,line);
        }
        if(terminal==null || sequence<2 || (state!=null && sequence!=state.sequence+1)) throw new IllegalArgumentException("GA truncated execution");
        String kind=terminal.path("type").textValue();
        if("FAILED".equals(kind)) {
            if(state!=null) state.publisher.discard();
            lifecycle.failWithAssistant(run.threadId(),run.id(),"这一步未能完成，请检查后重试。",
                    terminal.path("payload").path("errorCode").textValue(),"Framework execution failed", "OpenCode Zen",binding.model());
            return;
        }
        var checkpoints=jdbc.queryForList("SELECT checkpoint_id FROM ga_checkpoints WHERE thread_id=? ORDER BY checkpoint_id DESC LIMIT 1",
                String.class,run.threadId());
        if(checkpoints.isEmpty()) throw new IllegalStateException("GA_CHECKPOINT_MISSING");
        String text=terminal.path("payload").path("text").textValue();
        verifyCheckpoint(prepared,run.threadId(),checkpoints.getFirst(),kind,text);
        if(state!=null && "COMPLETED".equals(kind) && !state.text.isEmpty()) {
            if(!text.equals(state.text.toString())) throw new IllegalArgumentException("GA draft/final mismatch");
            state.publisher.finish();
        } else {
            if(state!=null) state.publisher.discard();
            var stream=new AnswerStreamPublisher(events,run.id(),System.nanoTime());
            stream.nextGeneration(); stream.accept(text); stream.finish();
        }
        if("USER_INPUT_REQUIRED".equals(kind)) lifecycle.completeForClarification(run.threadId(),run.id(),text,"OpenCode Zen",binding.model());
        else lifecycle.completeWithAssistant(run.threadId(),run.id(),text,"OpenCode Zen",binding.model());
        UUID boundary=jdbc.queryForObject("SELECT id FROM global_assistant_messages WHERE thread_id=? ORDER BY sequence DESC LIMIT 1",UUID.class,run.threadId());
        jdbc.update("UPDATE ga_checkpoint_heads SET completed_checkpoint_id=?,public_history_boundary=? WHERE thread_id=?",
                checkpoints.getFirst(),boundary,run.threadId());
    }
    private JsonNode parse(String line) {
        try { return mapper.readTree(line); } catch(Exception ex) { throw new IllegalArgumentException("GA event JSON"); }
    }
    private void validateEvent(GaExecutionPreparation.Prepared prepared,JsonNode event,int sequence) {
        fields(event,Set.of("protocolVersion","runId","executionEpoch","eventId","sequence","type","payload"));
        if(!"ga-execution-event.v1".equals(event.path("protocolVersion").textValue())
                || !prepared.scope().runId().toString().equals(event.path("runId").textValue())
                || !event.path("executionEpoch").isIntegralNumber() || !event.path("executionEpoch").canConvertToLong()
                || event.path("executionEpoch").longValue()!=prepared.scope().epoch()
                || !event.path("sequence").isIntegralNumber() || !event.path("sequence").canConvertToInt()
                || event.path("sequence").intValue()!=sequence || sequence>8192 || sequence<1)
            throw new IllegalArgumentException("GA event scope/order");
        UUID.fromString(event.path("eventId").textValue());
    }
    private void receipt(UUID run,JsonNode event,String line) {
        UUID id=UUID.fromString(event.path("eventId").textValue()); int seq=event.path("sequence").intValue();
        String hash=Hashes.sha256Hex(line);
        var rows=jdbc.queryForList("SELECT payload_hash FROM ga_execution_events WHERE run_id=? AND event_id=? AND internal_sequence=?",
                String.class,run,id,seq);
        if(rows.isEmpty()) jdbc.update("INSERT INTO ga_execution_events(run_id,event_id,internal_sequence,payload_hash) VALUES(?,?,?,?)",run,id,seq,hash);
        else if(!hash.equals(rows.getFirst())) throw new IllegalArgumentException("GA receipt conflict");
    }
    private void verifyReset(UUID run,UUID call) {
        var results=jdbc.queryForList("SELECT result FROM ga_execution_calls WHERE run_id=? AND call_id=? AND status='SUCCEEDED'",
                String.class,run,call);
        if(results.size()!=1 || GaModelContract.readResponse(results.getFirst()).toolCalls().isEmpty())
            throw new IllegalArgumentException("GA reset without tool response");
    }
    private void verifyOutput(GaExecutionStore.Scope scope,String kind,String text) {
        String callKind="COMPLETED".equals(kind)?"MODEL":"TOOL";
        var rows=jdbc.queryForList("SELECT result FROM ga_execution_calls WHERE run_id=? AND kind=? AND status='SUCCEEDED' ORDER BY call_sequence DESC LIMIT 1",
                String.class,scope.runId(),callKind);
        if(rows.isEmpty()) throw new IllegalArgumentException("GA output not recorded");
        if("MODEL".equals(callKind)) {
            var response=GaModelContract.readResponse(rows.getFirst());
            if(!response.toolCalls().isEmpty() || !text.equals(response.content())) throw new IllegalArgumentException("GA output mismatch");
        } else try {
            var response=mapper.readTree(rows.getFirst());
            if(!"USER_INPUT_REQUIRED".equals(response.path("status").textValue())
                    || !text.equals(response.path("content").path("question").textValue())) throw new IllegalArgumentException("GA question mismatch");
        } catch(java.io.IOException ex) { throw new IllegalArgumentException("GA question JSON"); }
    }
    private void verifyCheckpoint(GaExecutionPreparation.Prepared prepared,UUID thread,String checkpoint,String kind,String text) {
        try {
            String stored=jdbc.queryForObject("SELECT checkpoint FROM ga_checkpoints WHERE thread_id=? AND checkpoint_id=?",String.class,thread,checkpoint);
            var payload=mapper.readTree(mapper.readTree(stored).path("payload").textValue());
            var messages=payload.path("value").path("channel_values").path("value").path("messages").path("value");
            String current=mapper.readTree(prepared.body()).path("messageId").textValue();
            boolean consumed=false;
            for(var message:messages) {
                var value=message.path("value").path("value");
                if("human".equals(value.path("type").textValue()) && current.equals(value.path("id").textValue())) consumed=true;
            }
            if(!messages.isArray() || messages.isEmpty() || !consumed) throw new IllegalStateException("GA_CHECKPOINT_OUTPUT_MISMATCH");
            var last=messages.get(messages.size()-1).path("value").path("value");
            if("COMPLETED".equals(kind)) {
                if(!"ai".equals(last.path("type").textValue()) || !text.equals(last.path("content").textValue())
                        || !last.path("tool_calls").path("value").isEmpty()) throw new IllegalStateException("GA_CHECKPOINT_OUTPUT_MISMATCH");
            } else {
                if(!"tool".equals(last.path("type").textValue())) throw new IllegalStateException("GA_CHECKPOINT_OUTPUT_MISMATCH");
                var observation=mapper.readTree(last.path("content").textValue());
                if(!"USER_INPUT_REQUIRED".equals(observation.path("status").textValue())
                        || !text.equals(observation.path("content").path("question").textValue())) throw new IllegalStateException("GA_CHECKPOINT_OUTPUT_MISMATCH");
            }
        } catch(java.io.IOException ex) { throw new IllegalStateException("GA_CHECKPOINT_OUTPUT_MISMATCH"); }
    }
    private static void fields(JsonNode node,Set<String> fields) {
        var actual=new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        if(!node.isObject() || !actual.equals(fields)) throw new IllegalArgumentException("GA event fields");
    }
    @Transactional
    public void failed(UUID runId,String model) {
        failed(runId,model,null);
    }
    @Transactional
    public void failed(UUID runId,String model,StreamState state) {
        var run=runs.lockById(runId);
        if(!run.status().isActive()) return;
        if(state!=null) state.publisher.discard();
        if(run.cancelRequestedAt()!=null) lifecycle.cancelAndTerminalize(runId);
        else lifecycle.failWithAssistant(run.threadId(),runId,"这一步未能完成，请稍后重试。","GA_EXECUTION_FAILED",
                "Execution unavailable or interrupted","OpenCode Zen",model);
    }
}
