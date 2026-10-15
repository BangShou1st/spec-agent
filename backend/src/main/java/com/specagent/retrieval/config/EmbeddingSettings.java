package com.specagent.retrieval.config;

import com.specagent.common.Hashes;
import com.specagent.modelsettings.ModelCredentialCrypto;
import com.specagent.retrieval.protocol.RetrievalWire;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.specagent.retrieval.protocol.RetrievalWire.*;

/** Host-approved immutable semantics. Secrets exist only in encrypted Java-owned revisions. */
@Service
public class EmbeddingSettings {
    public record Config(String provider,String baseUrl,String model,int timeoutSeconds,int batchSize,String queryStrategy) {}
    public record Draft(Config config,String apiKey,Long revision,UUID testId) {
        public Draft(Config config,String apiKey,Long revision) { this(config,apiKey,revision,null); }
        @Override public String toString() { return "EmbeddingDraft[redacted]"; }
    }
    public record Profile(String profileId,Map<String,Object> semantic,Config config,long serviceRevision,UUID orgId) {
        public int dimensions() { return ((Number)semantic.get("dimensions")).intValue(); }
    }
    private final JdbcTemplate jdbc;
    private final ModelCredentialCrypto crypto;
    private final Set<String> trustedOrigins;
    private final com.specagent.access.OrganizationConfigurationScope scope;
    public EmbeddingSettings(JdbcTemplate jdbc,ModelCredentialCrypto crypto,
            @Value("${SPEC_AGENT_EMBEDDING_TRUSTED_ORIGINS:}") String origins,
            com.specagent.access.OrganizationConfigurationScope scope) {
        this.jdbc=jdbc; this.crypto=crypto;
        this.trustedOrigins=new HashSet<>(Arrays.asList(origins.split(",")));
        this.scope=scope;
    }
    public Config validate(Config c) {
        if(c==null || !Set.of("OLLAMA","OPENAI_COMPATIBLE").contains(c.provider()) || c.model()==null || c.model().isBlank()
                || c.model().length()>240 || c.model().chars().anyMatch(Character::isISOControl)
                || c.timeoutSeconds()<3 || c.timeoutSeconds()>60 || c.batchSize()<1 || c.batchSize()>16
                || !Set.of("raw-text.v1","qwen-instruct.v1").contains(c.queryStrategy())) throw new IllegalArgumentException("INVALID_EMBEDDING_CONFIG");
        try {
            URI uri=URI.create(c.baseUrl().strip());
            if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                    || uri.getQuery()!=null || uri.getFragment()!=null || c.baseUrl().length()>1000) throw new IllegalArgumentException();
            String origin=new URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),null,null,null).toString();
            boolean local=Set.of("localhost","127.0.0.1","[::1]","::1","host.docker.internal").contains(uri.getHost());
            if(c.provider().equals("OLLAMA") && ((!local && !trustedOrigins.contains(origin)) || !Set.of("","/").contains(uri.getPath()))) throw new IllegalArgumentException();
            if(c.provider().equals("OPENAI_COMPATIBLE") && !"https".equals(uri.getScheme()) && !local && !trustedOrigins.contains(origin)) throw new IllegalArgumentException();
            String url=uri.toString().replaceAll("/+$","");
            return new Config(c.provider(),url,c.model().strip(),c.timeoutSeconds(),c.batchSize(),c.queryStrategy());
        } catch(Exception invalid) { throw new IllegalArgumentException("INVALID_EMBEDDING_ADDRESS"); }
    }
    public long revision() {
        var rows=jdbc.queryForList("SELECT revision FROM embedding_service_settings WHERE singleton_id=1 AND org_id=?",scope.readOrg());
        return rows.isEmpty()?0:((Number)rows.getFirst().get("revision")).longValue();
    }
    public Config current() {
        var rows=jdbc.queryForList("SELECT config FROM embedding_service_settings WHERE singleton_id=1 AND org_id=?",scope.readOrg());
        return rows.isEmpty()?null:read(rows.getFirst().get("config").toString(),Config.class);
    }
    public String draftKey(Draft draft) {
        UUID org=scope.writeOrg();
        if(draft.apiKey()!=null) return credential(draft.apiKey());
        var old=current();
        if(old!=null && old.provider().equals(draft.config().provider()) && old.baseUrl().equals(draft.config().baseUrl())) {
            return crypto.decryptScoped(jdbc.queryForObject("SELECT api_key FROM embedding_service_settings WHERE singleton_id=1 AND org_id=?",String.class,org),org,"embedding_service_settings",1);
        }
        return null; // Never carry credentials to a different origin.
    }
    private static String credential(String value) {
        String key=value.strip(); if(key.isEmpty() || key.length()>4096 || key.contains("••") || key.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("INVALID_CREDENTIAL");
        return key;
    }
    public String key(Profile profile) {
        if(profile.orgId()==null || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM organizations WHERE id=? AND status='ACTIVE')",Boolean.class,profile.orgId())))
            throw new IllegalStateException("EMBEDDING_NOT_CONFIGURED");
        if(profile.orgId()==null || jdbc.queryForObject("SELECT count(*) FROM embedding_profile_registrations WHERE org_id=? AND profile_id=?",Integer.class,profile.orgId(),profile.profileId())!=1)
            throw new IllegalStateException("EMBEDDING_NOT_CONFIGURED");
        var rows=jdbc.queryForList("""
            SELECT api_key,revoked,revision FROM embedding_service_revisions WHERE org_id=? AND config->>'provider'=? AND config->>'baseUrl'=? ORDER BY revision DESC LIMIT 1
            """,profile.orgId(),profile.config().provider(),profile.config().baseUrl());
        if(rows.isEmpty() || Boolean.TRUE.equals(rows.getFirst().get("revoked"))) throw new IllegalStateException("EMBEDDING_NOT_CONFIGURED");
        return crypto.decryptScoped((String)rows.getFirst().get("api_key"),profile.orgId(),"embedding_service_revisions",rows.getFirst().get("revision"));
    }
    @Transactional public Map<String,Object> save(Draft draft) {
        UUID org=scope.writeOrg();lock(org);
        if(draft.revision()!=null && draft.revision()!=revision()) throw new IllegalStateException("SETTINGS_CHANGED");
        Config config=validate(draft.config()); String key=draftKey(new Draft(config,draft.apiKey(),draft.revision()));
        if(config.provider().equals("OLLAMA") && key!=null) throw new IllegalArgumentException("OLLAMA_GATEWAY_AUTH_UNSUPPORTED");
        if(config.provider().equals("OPENAI_COMPATIBLE") && (key==null || key.isBlank()) && !localApi(config)) throw new IllegalArgumentException("EMBEDDING_NOT_CONFIGURED");
        Map<String,Object> verifiedProbe=null;
        if(draft.testId()!=null) {
            var probes=jdbc.queryForList("SELECT * FROM embedding_probes WHERE id=? AND org_id=? AND created_at>now()-interval '15 minutes'",draft.testId(),org);
            if(probes.size()!=1 || !read(probes.getFirst().get("config").toString(),Config.class).equals(config)
                    || !Hashes.sha256Hex(key==null?"":key).equals(probes.getFirst().get("credential_hash"))) throw new IllegalStateException("EMBEDDING_TEST_EXPIRED");
            verifiedProbe=probes.getFirst();
        }
        long next=revision()+1; String encrypted=crypto.encryptScoped(key,org,"embedding_service_settings",1);
        // Even connection-only changes need an explicit test, but semantic identity stays unchanged.
        jdbc.update("""
            INSERT INTO embedding_service_settings(org_id,singleton_id,revision,config,api_key) VALUES(?,1,?,CAST(? AS jsonb),?)
            ON CONFLICT(org_id,singleton_id) DO UPDATE SET revision=EXCLUDED.revision,config=EXCLUDED.config,api_key=EXCLUDED.api_key,
                candidate_profile=NULL,tested_revision=NULL,test_code=NULL,tested_at=NULL
            """,org,next,write(config),encrypted);
        jdbc.update("INSERT INTO embedding_service_revisions(org_id,revision,config,api_key) VALUES(?,?,CAST(? AS jsonb),?)",org,next,write(config),crypto.encryptScoped(key,org,"embedding_service_revisions",next));
        if(verifiedProbe!=null) register(config,((Number)verifiedProbe.get("dimensions")).intValue(),(String)verifiedProbe.get("model_digest"),next);
        return view();
    }
    public static boolean localApi(Config c) {
        return Set.of("localhost","127.0.0.1","[::1]","::1").contains(URI.create(c.baseUrl()).getHost());
    }
    @Transactional public Map<String,Object> clearCredential(long expected) {
        UUID org=scope.writeOrg();lock(org);
        if(expected!=revision()) throw new IllegalStateException("SETTINGS_CHANGED");
        jdbc.update("UPDATE embedding_service_settings SET api_key=NULL,revision=revision+1,candidate_profile=NULL,test_code=NULL,tested_at=NULL,tested_revision=NULL WHERE singleton_id=1 AND org_id=?",org);
        jdbc.update("UPDATE embedding_service_revisions SET api_key=NULL,revoked=TRUE WHERE org_id=?",org);
        return view();
    }
    public Map<String,Object> view() {
        UUID org=scope.readOrg();var rows=jdbc.queryForList("SELECT * FROM embedding_service_settings WHERE singleton_id=1 AND org_id=?",org);
        var m=new LinkedHashMap<String,Object>(); m.put("revision",revision()); m.put("config",current()); m.put("source",rows.isEmpty()?"NOT_CONFIGURED":"DATABASE");
        String key=rows.isEmpty()?null:crypto.decryptScoped((String)rows.getFirst().get("api_key"),org,"embedding_service_settings",1);
        m.put("configured",key!=null&&!key.isBlank()); m.put("maskedKey",key==null||key.isBlank()?null:"••••"+key.substring(Math.max(0,key.length()-4)));
        m.put("candidateProfile",rows.isEmpty()?null:rows.getFirst().get("candidate_profile"));
        m.put("testCode",rows.isEmpty()?null:rows.getFirst().get("test_code")); m.put("testedAt",rows.isEmpty()?null:rows.getFirst().get("tested_at"));
        String id=rows.isEmpty()?null:(String)rows.getFirst().get("candidate_profile");
        m.put("dimensions",id==null?null:profile(id).dimensions()); return m;
    }
    @Transactional public Profile register(Config config,int dimensions,String digest,long revision) {
        UUID org=scope.writeOrg();lock(org);
        if(revision!=revision() || !config.equals(current())) throw new IllegalStateException("SETTINGS_CHANGED");
        if(dimensions<1 || dimensions>4096) throw new IllegalArgumentException("UNSUPPORTED_DIMENSIONS");
        var semantic=new TreeMap<String,Object>();
        semantic.put("provider",config.provider()); semantic.put("modelTag",config.model()); semantic.put("modelDigest",digest==null?"unversioned":digest);
        semantic.put("serviceIdentity",config.baseUrl()); semantic.put("dimensions",dimensions); semantic.put("queryStrategy",config.queryStrategy());
        semantic.put("queryInstruction",config.queryStrategy().equals("qwen-instruct.v1")?QUERY_INSTRUCTION:"");
        semantic.put("documentStrategy","raw-text.v1"); semantic.put("normalization","l2"); semantic.put("truncate",false);
        semantic.put("splitterVersion","host-source-granularity.v1"); semantic.put("indexSchemaVersion","retrieval-index.v3");
        String id=Hashes.sha256Hex(write(semantic));
        jdbc.update("INSERT INTO embedding_profiles(profile_id,semantic,config,service_revision) VALUES(?,CAST(? AS jsonb),CAST(? AS jsonb),?) ON CONFLICT DO NOTHING",id,write(semantic),write(config),revision);
        jdbc.update("INSERT INTO embedding_profile_registrations(org_id,profile_id,config,service_revision) VALUES(?,?,CAST(? AS jsonb),?) ON CONFLICT(org_id,profile_id) DO NOTHING",org,id,write(config),revision);
        jdbc.update("UPDATE embedding_service_settings SET candidate_profile=?,tested_revision=?,test_code='OK',tested_at=now() WHERE singleton_id=1 AND revision=? AND org_id=?",id,revision,revision,org);
        return profile(id);
    }
    public void testFailure(long revision,String code) {
        UUID org=scope.writeOrg();jdbc.update("UPDATE embedding_service_settings SET candidate_profile=NULL,tested_revision=?,test_code=?,tested_at=now() WHERE singleton_id=1 AND revision=? AND org_id=?",revision,code,revision,org);
    }
    public UUID probe(Config config,String key,int dimensions,String digest) {
        UUID org=scope.writeOrg();
        UUID id=UUID.randomUUID();
        jdbc.update("DELETE FROM embedding_probes WHERE org_id=? AND created_at<now()-interval '15 minutes'",org);
        jdbc.update("INSERT INTO embedding_probes(id,org_id,config,credential_hash,dimensions,model_digest) VALUES(?,?,CAST(? AS jsonb),?,?,?)",id,org,write(config),Hashes.sha256Hex(key==null?"":key),dimensions,digest);
        return id;
    }
    public Profile profile(String id) {
        return profileForOrg(scope.readOrg(),id);
    }
    public Profile profileForOrg(UUID org,String id) {
        var rows=jdbc.queryForList("SELECT p.semantic,r.config,r.service_revision FROM embedding_profiles p JOIN embedding_profile_registrations r ON r.profile_id=p.profile_id WHERE r.org_id=? AND p.profile_id=?",org,id);
        if(rows.size()!=1) throw new IllegalStateException("UNSUPPORTED_PROFILE");
        var r=rows.getFirst(); Config config=read(r.get("config").toString(),Config.class);
        long revision=((Number)r.get("service_revision")).longValue();
        // Connection limits are revisioned separately from immutable vector semantics.
        // New approved workloads use the latest limits for this exact model/strategy;
        // an already approved Python request keeps its captured configuration.
        var runtime=jdbc.queryForList("""
            SELECT revision,config FROM embedding_service_revisions WHERE org_id=? AND NOT revoked
              AND config->>'provider'=? AND config->>'baseUrl'=? AND config->>'model'=? AND config->>'queryStrategy'=?
            ORDER BY revision DESC LIMIT 1
            """,org,config.provider(),config.baseUrl(),config.model(),config.queryStrategy());
        if(!runtime.isEmpty()) {
            config=read(runtime.getFirst().get("config").toString(),Config.class);
            revision=((Number)runtime.getFirst().get("revision")).longValue();
        }
        return new Profile(id,read(r.get("semantic").toString(),Map.class),config,revision,org);
    }
    private void lock(UUID org) {jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))","embedding-settings:"+org);}
    public int dimensions(String id) { return PROFILE.equals(id)?1024:profile(id).dimensions(); }
    public void requireManagement() {scope.writeOrg();}
    public static final String QUERY_INSTRUCTION="Retrieve relevant Spec Agent product documentation and requirement workspace passages for the query.";
}
