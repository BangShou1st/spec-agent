package com.specagent.retrieval.protocol;

import com.specagent.common.Hashes;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.specagent.retrieval.protocol.RetrievalWire.*;

/** Fixed shipped product help only; no caller-controlled filesystem path, URL or SQL corpus. */
@Service
public class CuratedHelpSources {
    public static final UUID CORPUS=UUID.nameUUIDFromBytes("spec-agent:curated-help:v1".getBytes(StandardCharsets.UTF_8));
    public record Document(String id,String text,String hash) {}
    public List<Document> documents() { return List.of(document("projects"),document("resources"),document("assistant")); }
    public Document document(String id) {
        if(!Set.of("projects","resources","assistant").contains(id)) throw new IllegalStateException("SOURCE_VERSION_MISMATCH");
        try(var input=CuratedHelpSources.class.getResourceAsStream("/help/"+id+".md")) {
            if(input==null) throw new IOException("Help source missing");
            byte[] bytes=input.readNBytes(12001); if(bytes.length>12000) throw new IOException("Help source budget exceeded");
            String text=new String(bytes,StandardCharsets.UTF_8); return new Document(id,text,Hashes.sha256Hex(text));
        } catch(IOException ex) { throw new IllegalStateException("RETRIEVAL_UNAVAILABLE"); }
    }
    public boolean current(Map<String,Object> row) {
        try {
            if(!CORPUS.equals(row.get("corpus_id")) || row.get("project_id")!=null || !"HELP".equals(row.get("scope")) || !"EXTERNAL_EVIDENCE".equals(row.get("authority")) || row.get("route_id")!=null) return false;
            var metadata=read(row.get("metadata").toString(),Map.class); var document=document(metadata.get("helpDocId").toString());
            int start=((Number)metadata.get("startOffset")).intValue(),end=((Number)metadata.get("endOffset")).intValue();
            int index=((Number)metadata.get("chunkIndex")).intValue();
            String ref="help:"+document.id()+":"+index;
            return index>=0 && ref.equals(row.get("source_ref"))
                && UUID.nameUUIDFromBytes(("help:"+document.id()).getBytes(StandardCharsets.UTF_8)).equals(row.get("source_id"))
                && document.hash().equals(metadata.get("rawContentHash")) && document.hash().equals(metadata.get("rawSourceVersion"))
                && start>=0 && end>start && end<=document.text().length() && document.text().substring(start,end).equals(row.get("content"));
        } catch(RuntimeException invalid) { return false; }
    }
}
