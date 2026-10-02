package com.specagent.retrieval.protocol;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** retrieval.v1 DTOs. A grant is an opaque host reference, never self-reported permission. */
public final class RetrievalWire {
    public static final String PROFILE="26810df4b6b4e7d0d2977bcc57175fcf7e944ebe99f447b5c4e690cb08b8c047";
    public static final Set<String> LANES=Set.of("route-lexical","route-trigram","project-lexical","project-trigram",
            "resource-lexical","resource-trigram","graph","vector");
    public static final ObjectMapper JSON=new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    private RetrievalWire() {}
    public record Workload(String kind, UUID id, long executionEpoch) {
        public Workload {
            if(kind==null || !Set.of("PROJECT_RUN","GA_RUN","CONTEXT_PROJECTION","INDEX_JOB").contains(kind)
                    || id==null || executionEpoch<1) throw new IllegalArgumentException("RETRIEVAL_PROTOCOL_ERROR");
        }
    }
    public record Grant(UUID grantId, long version, Instant expiresAt) {
        public Grant { if(grantId==null || version<1 || expiresAt==null) throw new IllegalArgumentException("RETRIEVAL_PROTOCOL_ERROR"); }
    }
    public interface Envelope {
        String protocolVersion(); UUID requestId(); Workload workload(); Grant scopeGrant();
        String profileId(); UUID indexGeneration(); Instant deadline();
    }
    public record Limits(int maxItems,int maxChars,int laneLimit) {}
    public record Vector(int dimensions,double[] values,String checksum) {
        public Vector { values=values==null ? null : values.clone(); }
        @Override public double[] values() { return values==null ? null : values.clone(); }
        public void validate() {
            if(dimensions!=1024 || !RetrievalVectors.checksum(values).equals(checksum)) throw new IllegalArgumentException("INVALID_VECTOR");
        }
    }
    public record Location(Integer chunkIndex,Integer startOffset,Integer endOffset) {
        public Location {
            if(chunkIndex!=null && chunkIndex<0 || (startOffset==null)!=(endOffset==null)
                    || startOffset!=null && (startOffset<0 || endOffset<=startOffset)) throw new IllegalArgumentException("RETRIEVAL_PROTOCOL_ERROR");
        }
    }
    public record Source(UUID entryId,UUID corpusId,UUID projectId,UUID originRouteId,String sourceRef,
                         String sourceVersion,String contentHash,String sourceKind,String scope,String authority,
                         int selectionTier,String content,Location location) {
        public Source {
            if(entryId==null || corpusId==null || sourceRef==null || sourceRef.isBlank() || sourceRef.length()>240
                    || sourceVersion==null || sourceVersion.isBlank() || sourceVersion.length()>128
                    || content==null || content.isBlank() || content.length()>12000 || !com.specagent.common.Hashes.sha256Hex(content).equals(contentHash)
                    || location==null || selectionTier<0 || selectionTier>4 || sourceKind==null
                    || !Set.of("NODE","ANSWER","CLAIM","RESOURCE_CHUNK","CAPABILITY_OBSERVATION","ROUTE_SUMMARY","HELP_CHUNK").contains(sourceKind)
                    || scope==null || !Set.of("ROUTE","PROJECT","RESOURCE","HELP").contains(scope)
                    || authority==null || !Set.of("CONFIRMED","USER_AUTHORED","EXTERNAL_EVIDENCE","DERIVED","ASSUMED","UNRESOLVED","REJECTED").contains(authority)
                    || scope.equals("HELP")!=(projectId==null) || scope.equals("ROUTE") && originRouteId==null
                    || scope.equals("HELP") && (!sourceKind.equals("HELP_CHUNK") || originRouteId!=null))
                throw new IllegalArgumentException("RETRIEVAL_PROTOCOL_ERROR");
        }
    }
    public record Lane(String lane,List<Source> entries) {}
    public record Ranked(Source source,List<String> lanes,double rankScore) {}
    public record Search(String protocolVersion,UUID requestId,Workload workload,Grant scopeGrant,String profileId,
                         UUID indexGeneration,Instant deadline,String retrievalEngineVersion,String query,String mode,Limits limits) implements Envelope {}
    public record SearchResult(String protocolVersion,UUID requestId,Workload workload,Grant scopeGrant,String profileId,
                         UUID indexGeneration,Instant deadline,String retrievalEngineVersion,String status,boolean vectorUnavailable,
                         List<Ranked> items,List<String> warnings) implements Envelope {}
    public record Candidates(String protocolVersion,UUID requestId,Workload workload,Grant scopeGrant,String profileId,
                         UUID indexGeneration,Instant deadline,String query,List<String> lanes,int laneLimit,Vector queryVector,Double maxVectorDistance) implements Envelope {
        public Candidates(String protocolVersion,UUID requestId,Workload workload,Grant scopeGrant,String profileId,
                          UUID indexGeneration,Instant deadline,String query,List<String> lanes,int laneLimit,Vector queryVector) {
            this(protocolVersion,requestId,workload,scopeGrant,profileId,indexGeneration,deadline,query,lanes,laneLimit,queryVector,0.65);
        }
        public Candidates { if(maxVectorDistance==null) maxVectorDistance=0.65; }
    }
    public record CandidatesResult(String protocolVersion,UUID requestId,Workload workload,Grant scopeGrant,String profileId,
                         UUID indexGeneration,Instant deadline,List<Lane> lanes) implements Envelope {}
    public record Validation(String protocolVersion,UUID requestId,Workload workload,Grant scopeGrant,String profileId,
                         UUID indexGeneration,Instant deadline,List<Source> sources) implements Envelope {}
    public record ValidationResult(String protocolVersion,UUID requestId,Workload workload,Grant scopeGrant,String profileId,
                         UUID indexGeneration,Instant deadline,List<UUID> allowedEntryIds,List<UUID> rejectedEntryIds) implements Envelope {}
    public record IndexSource(UUID entryId,String sourceRef,String sourceVersion,String contentHash,String text,Location location) {}
    public record IndexVector(UUID entryId,String sourceRef,String sourceVersion,String contentHash,Location location,Vector vector) {}
    public record IndexBatch(String protocolVersion,UUID requestId,Workload workload,Grant scopeGrant,String profileId,
                         UUID indexGeneration,Instant deadline,UUID jobId,UUID leaseId,long expectedVersion,List<IndexSource> sources) implements Envelope {}
    public record IndexResult(String protocolVersion,UUID requestId,Workload workload,Grant scopeGrant,String profileId,
                         UUID indexGeneration,Instant deadline,UUID jobId,UUID leaseId,long expectedVersion,List<IndexVector> vectors) implements Envelope {}

    public record SplitRequest(String protocolVersion,UUID requestId,Workload workload,Grant scopeGrant,String profileId,
            UUID indexGeneration,Instant deadline,UUID jobId,UUID leaseId,long expectedVersion,
            String sourceRef,String sourceVersion,String sourceKind,String contentHash,String text) implements Envelope {}
    public record SourceChunk(int index,String text,int startOffset,int endOffset,String contentHash,List<String> headings) {}
    public record SplitResult(String protocolVersion,UUID requestId,Workload workload,Grant scopeGrant,String profileId,
            UUID indexGeneration,Instant deadline,UUID jobId,UUID leaseId,long expectedVersion,
            String sourceRef,String sourceVersion,String contentHash,List<SourceChunk> chunks) implements Envelope {}

    public static <T> T read(String body,Class<T> type) {
        try {
            if(body==null || body.getBytes(StandardCharsets.UTF_8).length>2*1024*1024) throw new IllegalArgumentException();
            return JSON.readValue(body,type);
        } catch(Exception ex) { throw new IllegalArgumentException("RETRIEVAL_PROTOCOL_ERROR"); }
    }
    public static String write(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch(Exception ex) { throw new IllegalArgumentException("RETRIEVAL_PROTOCOL_ERROR"); }
    }
    public static Map<String,Object> binding(Envelope envelope) {
        validate(envelope);
        return Map.of("protocolVersion",envelope.protocolVersion(),"requestId",envelope.requestId(),"workload",envelope.workload(),
                "scopeGrant",envelope.scopeGrant(),"profileId",envelope.profileId(),"indexGeneration",envelope.indexGeneration(),"deadline",envelope.deadline());
    }
    public static void validate(Envelope envelope) {
        if(!"retrieval.v1".equals(envelope.protocolVersion()) || envelope.requestId()==null || envelope.workload()==null
                || envelope.workload().id()==null || envelope.workload().executionEpoch()<1
                || !Set.of("PROJECT_RUN","GA_RUN","CONTEXT_PROJECTION","INDEX_JOB").contains(envelope.workload().kind())
                || envelope.scopeGrant()==null || envelope.scopeGrant().grantId()==null || envelope.scopeGrant().version()<1
                || envelope.scopeGrant().expiresAt()==null || envelope.deadline()==null
                || envelope.deadline().isAfter(envelope.scopeGrant().expiresAt()) || envelope.indexGeneration()==null
                || envelope.profileId()==null || !envelope.profileId().matches("[a-f0-9]{64}")) throw new IllegalArgumentException("RETRIEVAL_PROTOCOL_ERROR");
    }
}
