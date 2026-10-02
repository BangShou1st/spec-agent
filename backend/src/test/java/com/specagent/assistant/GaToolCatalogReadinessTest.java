package com.specagent.assistant;

import com.specagent.assistant.tool.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class GaToolCatalogReadinessTest {
    @Autowired GaCatalogProjection catalog;
    @SpyBean TavilyWebService web;
    @SpyBean GaRetrievalReadiness retrieval;
    @Test void completeCatalogProjectsAllTwelveToolsWithoutTruncation() {
        doReturn(true).when(web).configured();
        doReturn(true).when(retrieval).ready();
        var snapshot=catalog.current();
        assertEquals(12,snapshot.descriptors().size());
        assertEquals(12,snapshot.tools().size());
        assertEquals(java.util.Set.of("project.create","project.search","project.list_recent","project.get_summary",
                "skill.import.discover","skill.import","help.search","project.content.discover","web.search","web.fetch",
                "ui.navigate","user-input.request"),snapshot.descriptors().stream().map(GaCatalogProjection.Descriptor::capabilityId).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void missingCredentialsAndUnavailableRetrievalAreExplicitlyHidden() {
        doReturn(false).when(web).configured();
        doReturn(false).when(retrieval).ready();
        var ids=catalog.current().descriptors().stream().map(GaCatalogProjection.Descriptor::capabilityId).toList();
        assertEquals(8,ids.size());
        assertFalse(ids.contains("web.search")); assertFalse(ids.contains("web.fetch"));
        assertFalse(ids.contains("help.search")); assertFalse(ids.contains("project.content.discover"));
    }
}
