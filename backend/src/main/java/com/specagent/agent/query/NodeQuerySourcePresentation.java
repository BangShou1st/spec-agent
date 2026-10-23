package com.specagent.agent.query;

import com.specagent.agent.protocol.*;
import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.snapshot.*;
import com.specagent.retrieval.protocol.*;
import com.specagent.workspace.context.ContextSnapshotRepository;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.answer.AnswerRepository;
import com.specagent.workspace.patch.AnswerPatchRepository;
import com.specagent.common.Hashes;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Existing SOURCE card shape, generated from host-owned frozen input and current authority only. */
@Service
public class NodeQuerySourcePresentation {
    private final ContextSnapshotRepository contexts;
    private final AgentInputSnapshotBuilder snapshots;
    private final RetrievalStore retrieval;
    private final RetrievalGrantAuthorization authorization;
    private final NodeRepository nodes;
    private final AnswerRepository answers;
    private final AnswerPatchRepository patches;
    private final MutableSourceFingerprinter fingerprints;
    private final com.specagent.retrieval.index.RetrievalSourcePolicy sourcePolicy=new com.specagent.retrieval.index.RetrievalSourcePolicy();
    public NodeQuerySourcePresentation(ContextSnapshotRepository contexts,AgentInputSnapshotBuilder snapshots,
            RetrievalStore retrieval,RetrievalGrantAuthorization authorization,NodeRepository nodes,
            AnswerRepository answers,AnswerPatchRepository patches,MutableSourceFingerprinter fingerprints) {
        this.contexts=contexts;this.snapshots=snapshots;this.retrieval=retrieval;this.authorization=authorization;
        this.nodes=nodes;this.answers=answers;this.patches=patches;this.fingerprints=fingerprints;
    }
    public record View(String sourceStatus,List<Map<String,Object>> resourceRefs) {
        public boolean unavailable() {return sourceStatus.equals("UNAVAILABLE") || sourceStatus.equals("VALIDATION_FAILED");}
    }
    @Transactional
    public View project(AgentRun run,Map<String,Object> response) {
        if(!response.containsKey("sourceRefs")) return new View("LEGACY",List.of());
        try {
            authorization.issue(run.projectId());
            if(!(response.get("sourceRefs") instanceof List<?> raw) || raw.size()>128
                    || raw.stream().anyMatch(r->!(r instanceof String))) return new View("VALIDATION_FAILED",List.of());
            if(raw.isEmpty()) return new View("NONE",List.of());
            if(run.contextSnapshotId()==null) return new View("VALIDATION_FAILED",List.of());
            var context=contexts.findById(run.contextSnapshotId()).orElseThrow();
            if(!context.projectId().equals(run.projectId())) throw new IllegalStateException("Foreign context");
            var frozen=snapshots.build(context);
            var cards=new ArrayList<Map<String,Object>>();
            for(String ref:new LinkedHashSet<>(raw.stream().map(String.class::cast).toList())) {
                if(!frozen.allowedSourceRefs().contains(ref)) return new View("VALIDATION_FAILED",List.of());
                // Context/route identities are provenance, not evidence excerpts or navigation targets.
                if(ref.startsWith("context:") || ref.startsWith("route:")) continue;
                var item=frozen.retrievedContext().stream().filter(i->i.sourceRef().equals(ref)).findFirst();
                if(item.isPresent()) {
                    var row=retrieval.currentCitation(run.projectId(),item.get());
                    cards.add(card((UUID)row.get("id"),ref,row.get("source_version").toString(),
                        row.get("content_hash").toString(),row.get("content").toString(),run.projectId(),
                        targetNode(run.projectId(),row),item.get().location()));
                } else cards.add(lineageCard(run,frozen,ref));
            }
            authorization.issue(run.projectId());
            return new View(cards.isEmpty()?"NONE":"AVAILABLE",List.copyOf(cards));
        } catch(com.specagent.access.AccessDeniedException denied) {return new View("UNAVAILABLE",List.of());}
        catch(IllegalStateException unavailable) {return new View("UNAVAILABLE",List.of());}
        catch(RuntimeException failure) {return new View("VALIDATION_FAILED",List.of());}
    }
    private UUID targetNode(UUID project,Map<String,Object> row) {
        String kind=row.get("source_kind").toString();
        UUID id;
        if(Set.of("NODE","RESOURCE_CHUNK").contains(kind)) id=(UUID)row.get("source_id");
        else if(kind.equals("ANSWER")) id=answers.findById((UUID)row.get("source_id")).orElseThrow().nodeId();
        else {
            var metadata=RetrievalWire.read(row.get("metadata").toString(),Map.class);
            id=metadata.get("sourceNodeId")==null?null:UUID.fromString(metadata.get("sourceNodeId").toString());
        }
        if(id!=null) {
            var node=nodes.findById(id).orElseThrow();
            if(!node.projectId().equals(project) || node.isRetracted() || !sourcePolicy.allow(node))
                throw new IllegalStateException("SOURCE_UNAVAILABLE");
        }
        return id;
    }
    private Map<String,Object> lineageCard(AgentRun run,AgentInputSnapshot frozen,String ref) {
        for(var entry:frozen.lineage()) {
            if(!ref.equals("node:"+entry.node().id())
                    && (entry.answer()==null || !ref.equals("answer:"+entry.answer().id()))
                    && entry.patches().stream().noneMatch(p->ref.equals("patch:"+p.id()))) continue;
            var node=nodes.findById(entry.node().id()).orElseThrow();
            if(!node.projectId().equals(run.projectId()) || node.isRetracted() || !sourcePolicy.allow(node)
                    || !fingerprints.modelVisibleNodeHash(node).equals(fingerprints.modelVisibleNodeHash(entry.node())))
                throw new IllegalStateException("SOURCE_VERSION_MISMATCH");
            String text=null;
            if(ref.equals("node:"+node.id())) text=entry.node().body().text();
            if(entry.answer()!=null && ref.equals("answer:"+entry.answer().id())) {
                var answer=answers.findById(entry.answer().id()).orElseThrow();
                if(!answer.projectId().equals(run.projectId()) || !answer.nodeId().equals(node.id())
                        || !Objects.equals(answer.freeText(),entry.answer().freeText())) throw new IllegalStateException("SOURCE_VERSION_MISMATCH");
                text=entry.answer().freeText();
                if(text==null || text.isBlank()) text="已确认选项："+entry.answer().selectedOptionId();
            }
            for(var patch:entry.patches()) if(ref.equals("patch:"+patch.id())) {
                var current=patches.findById(patch.id()).orElseThrow();
                if(!current.projectId().equals(run.projectId()) || !current.sourceNodeId().equals(node.id())) throw new IllegalStateException("SOURCE_UNAVAILABLE");
                text=String.join("\n",patch.claims().stream().map(ClaimView::text).toList());
            }
            if(text!=null) {
                String hash=Hashes.sha256Hex(text);
                return card(UUID.fromString(ref.substring(ref.indexOf(':')+1)),ref,hash,hash,text,run.projectId(),node.id(),Map.of());
            }
        }
        for(var related:frozen.relatedNodes()) if(ref.equals("node:"+related.nodeId())) {
            var current=nodes.findById(related.nodeId()).orElseThrow();
            if(!current.projectId().equals(run.projectId()) || current.isRetracted() || !sourcePolicy.allow(current) || related.node()==null
                    || !fingerprints.modelVisibleNodeHash(current).equals(fingerprints.modelVisibleNodeHash(related.node())))
                throw new IllegalStateException("SOURCE_VERSION_MISMATCH");
            String text=related.node().body().text(),hash=fingerprints.modelVisibleNodeHash(related.node());
            return card(current.id(),ref,hash,hash,text,run.projectId(),current.id(),Map.of());
        }
        throw new IllegalStateException("SOURCE_UNAVAILABLE");
    }
    private Map<String,Object> card(UUID id,String ref,String version,String hash,String text,UUID project,UUID node,Map<String,Object> location) {
        var metadata=new LinkedHashMap<String,Object>();
        metadata.put("sourceRef",ref);metadata.put("sourceVersion",version);metadata.put("contentHash",hash);
        metadata.put("excerpt",text.substring(0,Math.min(800,text.length())));metadata.put("projectId",project.toString());
        if(node!=null) metadata.put("nodeId",node.toString());
        for(String key:List.of("startOffset","endOffset")) if(location.get(key)!=null) metadata.put(key,location.get(key));
        return Map.of("kind","SOURCE","id",id.toString(),"label",text.substring(0,Math.min(64,text.length())),"metadata",metadata);
    }
}
