package com.specagent.assistant.tool;

import com.specagent.assistant.config.GaBrainSettings;
import com.specagent.retrieval.protocol.*;
import org.springframework.stereotype.Service;

/** Reuses the shared implementation, with a GA origin. Leaves the project's existing host bean untouched. */
@Service
public class GaRetrievalAccess {
    private final SharedRetrievalHost host;
    public GaRetrievalAccess(GaBrainSettings settings, RetrievalStore store, RetrievalIndexJobs jobs,
            RetrievalSourceJobs sources, HelpCorpusJobs help) {
        host=new SharedRetrievalHost(store,new PythonRetrievalClient(settings.connection()),jobs,sources,help);
    }
    public SharedRetrievalHost host() { return host; }
}
