import com.specagent.modelsettings.ModelCredentialCrypto;
import com.specagent.connection.credentials.ConnectionCredentialCrypto;
import java.sql.DriverManager;
import java.util.UUID;

/** Isolated acceptance only: use the actual product crypto readers in a separate JVM.
 * Prints counts, never plaintext, ciphertext, passwords or master keys. */
class P2CredentialRotationProbe {
    public static void main(String[] args) throws Exception {
        String url=System.getenv("P2_PROBE_JDBC_URL");
        if(url==null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:5546/spec_agent_p2_rotation_[a-z0-9_]+"))
            throw new IllegalArgumentException("Isolated rotation database required");
        String key=System.getenv("P2_PROBE_MASTER_KEY");
        var models=new ModelCredentialCrypto(key,"","unused-in-oidc","OIDC");
        var mcp=new ConnectionCredentialCrypto(key,"");
        int modelRows=0,mcpRows=0,rejectedCopies=0;
        String[][] tables={{"model_providers","id"},{"opencode_settings","singleton_id"},
                {"openrouter_settings","singleton_id"},{"custom_provider_settings","singleton_id"},
                {"search_settings","singleton_id"},{"embedding_service_settings","singleton_id"},
                {"embedding_service_revisions","revision"}};
        try(var db=DriverManager.getConnection(url,"spec_agent",System.getenv("P2_PROBE_DB_PASSWORD"))) {
            for(var table:tables) try(var stmt=db.createStatement();var rows=stmt.executeQuery(
                    "SELECT org_id,"+table[1]+",api_key FROM "+table[0]+" WHERE api_key IS NOT NULL AND api_key<>''")) {
                while(rows.next()) {
                    UUID org=rows.getObject(1,UUID.class);Object id=rows.getObject(2);String stored=rows.getString(3);
                    String plain=models.decryptScoped(stored,org,table[0],id);
                    if(plain==null || plain.isBlank())throw new IllegalStateException("Empty credential");
                    modelRows++;
                    boolean rejected=false;
                    try {models.decryptScoped(stored,UUID.randomUUID(),table[0],id);}
                    catch(IllegalStateException expected){rejected=true;}
                    if(!rejected)throw new IllegalStateException("Cross-organization copy accepted");
                    rejectedCopies++;
                }
            }
            try(var stmt=db.createStatement();var rows=stmt.executeQuery(
                    "SELECT org_id,connection_id,ref,encrypted_secret FROM connection_credentials")) {
                while(rows.next()) {
                    UUID org=rows.getObject(1,UUID.class),connection=rows.getObject(2,UUID.class);
                    String ref=rows.getString(3),stored=rows.getString(4);
                    if(mcp.decrypt(stored,org,connection,ref).isBlank())throw new IllegalStateException("Empty MCP credential");
                    mcpRows++;
                    boolean rejected=false;
                    try {mcp.decrypt(stored,UUID.randomUUID(),connection,ref);}
                    catch(IllegalStateException expected){rejected=true;}
                    if(!rejected)throw new IllegalStateException("Cross-organization MCP copy accepted");
                    rejectedCopies++;
                }
            }
        }
        if(modelRows<1 || mcpRows<1)throw new IllegalStateException("Model and MCP fixtures both required");
        System.out.println("{\"modelRowsDecrypted\":"+modelRows+",\"mcpRowsDecrypted\":"+mcpRows
                +",\"crossOrganizationCopiesRejected\":"+rejectedCopies+"}");
    }
}
