package com.specagent.retrieval.protocol;

import com.specagent.common.Hashes;
import com.specagent.retrieval.index.RetrievalSourcePolicy;
import com.specagent.retrieval.index.RetrievalSourceProjector;
import com.specagent.workspace.node.*;
import com.specagent.workspace.answer.AnswerRepository;
import com.specagent.workspace.patch.AnswerPatchRepository;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.specagent.retrieval.protocol.RetrievalWire.*;

/** Recheck actual canonical records. A stale/malformed derived row alone never proves a source exists. */
@Service
public class RetrievalSourceVerifier {
    private final NodeRepository nodes;
    private final AnswerRepository answers;
    private final AnswerPatchRepository patches;
    private final CuratedHelpSources help;
    private final RetrievalSourcePolicy policy=new RetrievalSourcePolicy();
    public RetrievalSourceVerifier(NodeRepository nodes,AnswerRepository answers,AnswerPatchRepository patches,CuratedHelpSources help) {
        this.nodes=nodes; this.answers=answers; this.patches=patches; this.help=help;
    }
    public boolean current(Map<String,Object> row) {
        try {
            UUID project=(UUID)row.get("project_id"),id=(UUID)row.get("source_id");
            String kind=row.get("source_kind").toString(),text=row.get("content").toString(),ref=row.get("source_ref").toString();
            if(!Hashes.sha256Hex(text).equals(row.get("content_hash")) || !policy.allowText(text)) return false;
            var metadata=read(row.get("metadata").toString(),Map.class);
            if(!policy.allowMetadata(metadata)) return false;
            if(kind.equals("HELP_CHUNK")) return help.current(row);
            if(kind.equals("NODE") || kind.equals("RESOURCE_CHUNK")) {
                var node=nodes.findById(id);
                if(node.isEmpty() || !node.get().projectId().equals(project) || node.get().isRetracted() || !policy.allow(node.get())) return false;
                var value=node.get();
                if(kind.equals("NODE")) return "PROJECT".equals(row.get("scope")) && RetrievalSourceProjector.nodeAuthority(value).name().equals(row.get("authority")) && value.kind()!=NodeKind.RESOURCE && ref.equals("node:"+id)
                    && text.equals(com.specagent.retrieval.index.RetrievalSourceProjector.joinText(value.question(),value.purpose(),value.contentText()));
                if(value.kind()!=NodeKind.RESOURCE || value.contentText()==null || !"RESOURCE".equals(row.get("scope")) || !"EXTERNAL_EVIDENCE".equals(row.get("authority"))
                    || !(metadata.get("chunkIndex") instanceof Number chunk) || !ref.equals("resource-chunk:"+id+":"+chunk.intValue())
                    || !id.toString().equals(metadata.get("resourceId"))) return false;
                Object rawHash=metadata.get("rawContentHash"),start=metadata.get("startOffset"),end=metadata.get("endOffset");
                // Only Python-projected slices can be used by the new service; backfill old chunks before cutover.
                if(!(start instanceof Number begin) || !(end instanceof Number finish) || rawHash==null
                        || !rawHash.equals(Hashes.sha256Hex(value.contentText()))) return false;
                int from=begin.intValue(),to=finish.intValue();
                return from>=0 && to>from && to<=value.contentText().length() && value.contentText().substring(from,to).equals(text);
            }
            if(kind.equals("ANSWER")) return answers.findById(id).filter(answer->"ROUTE".equals(row.get("scope")) && "USER_AUTHORED".equals(row.get("authority"))
                && answer.routeId().equals(row.get("route_id")) && answer.projectId().equals(project)
                && ref.equals("answer:"+id) && text.equals(com.specagent.retrieval.index.RetrievalSourceProjector.joinText(answer.freeText(),answer.selectedOptionId()))).isPresent();
            if(kind.equals("CLAIM")) {
                Object answerId=metadata.get("sourceAnswerId"); if(answerId==null || !ref.equals("claim:"+id)) return false;
                for(var patch:patches.findBySourceAnswerId(UUID.fromString(answerId.toString()))) {
                    if(!patch.projectId().equals(project) || !"ROUTE".equals(row.get("scope")) || !patch.routeId().equals(row.get("route_id"))
                        || !patch.sourceNodeId().toString().equals(metadata.get("sourceNodeId"))) continue;
                    int ordinal=0;
                    for(var claim:patch.claims()) {
                        UUID claimId=claim.id()==null?UUID.nameUUIDFromBytes((patch.id()+":claim:"+ordinal).getBytes(StandardCharsets.UTF_8)):claim.id();
                        ordinal++;
                        if(id.equals(claimId) && RetrievalSourceProjector.claimAuthority(claim.status()).name().equals(row.get("authority")) && claim.text()!=null && claim.text().strip().equals(text)) return true;
                    }
                }
            }
            return false; // No unregistered observation/help source can manufacture an authority record.
        } catch(RuntimeException invalid) { return false; }
    }
}
