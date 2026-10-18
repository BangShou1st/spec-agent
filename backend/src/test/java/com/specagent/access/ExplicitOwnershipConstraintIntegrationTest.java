package com.specagent.access;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

/** Database constraints must reject omitted/unknown attribution, independently of application guards. */
@SpringBootTest
@ActiveProfiles("test")
class ExplicitOwnershipConstraintIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    private static final String ORG="00000000-0000-4000-8000-000000000001";
    private static final String USER="00000000-0000-4000-8000-000000000002";

    private void rejected(String sql,String state,String column) {
        var failure=catchThrowableOfType(()->jdbc.update(sql),DataIntegrityViolationException.class);
        assertThat(failure).as("invalid attribution must not commit: %s",sql).isNotNull();
        assertThat(failure.getMostSpecificCause()).isInstanceOf(PSQLException.class);
        var cause=(PSQLException)failure.getMostSpecificCause();
        assertThat(cause.getSQLState()).isEqualTo(state);
        if(column!=null)assertThat(cause.getServerErrorMessage().getColumn()).isEqualTo(column);
    }

    @Test void projectThreadAndMembershipCannotOmitTheirOwners() {
        rejected("INSERT INTO projects(id,title,created_by,created_at,updated_at) VALUES('"+UUID.randomUUID()+"','missing-org','"+USER+"',now(),now())","23502","org_id");
        rejected("INSERT INTO projects(id,title,org_id,created_at,updated_at) VALUES('"+UUID.randomUUID()+"','missing-creator','"+ORG+"',now(),now())","23502","created_by");
        rejected("INSERT INTO global_assistant_threads(id,owner_user_id) VALUES('"+UUID.randomUUID()+"','"+USER+"')","23502","org_id");
        rejected("INSERT INTO global_assistant_threads(id,org_id) VALUES('"+UUID.randomUUID()+"','"+ORG+"')","23502","owner_user_id");
        rejected("INSERT INTO project_members(project_id,user_id,role) VALUES('"+UUID.randomUUID()+"','"+USER+"','VIEWER')","23502","org_id");
    }

    @Test void profileCannotBeCreatedWithoutOrganization() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM profiles",Integer.class)).isPositive();
        rejected("INSERT INTO profiles(id,name,description,aspects,spec_section_definitions,question_policy_hints,tone) "
                +"SELECT '"+UUID.randomUUID()+"','missing-org',description,aspects,spec_section_definitions,question_policy_hints,tone FROM profiles LIMIT 1","23502","org_id");
    }

    @Test void credentialConfigurationNeverDefaultsToLegacyOrganization() {
        for(String table:List.of("model_providers","model_provider_settings","opencode_settings","openrouter_settings","kilo_settings","custom_provider_settings","search_settings")) {
            assertThat(jdbc.queryForObject("SELECT column_default IS NULL FROM information_schema.columns WHERE table_schema='public' AND table_name=? AND column_name='org_id'",Boolean.class,table))
                    .as("%s must require explicit organization",table).isTrue();
        }
        rejected("INSERT INTO opencode_settings(singleton_id,api_key,masked_suffix,selected_model) VALUES(1,'synthetic-explicit-owner','test','m1')","23502","org_id");
    }

    @Test void unknownOrganizationsAndUsersCannotBeStoredAsOwners() {
        rejected("INSERT INTO projects(id,title,org_id,created_by,created_at,updated_at) VALUES('"+UUID.randomUUID()+"','unknown-org','"+UUID.randomUUID()+"','"+USER+"',now(),now())","23503",null);
        rejected("INSERT INTO projects(id,title,org_id,created_by,created_at,updated_at) VALUES('"+UUID.randomUUID()+"','unknown-creator','"+ORG+"','"+UUID.randomUUID()+"',now(),now())","23503",null);
        rejected("INSERT INTO global_assistant_threads(id,org_id,owner_user_id) VALUES('"+UUID.randomUUID()+"','"+UUID.randomUUID()+"','"+USER+"')","23503",null);
        rejected("INSERT INTO global_assistant_threads(id,org_id,owner_user_id) VALUES('"+UUID.randomUUID()+"','"+ORG+"','"+UUID.randomUUID()+"')","23503",null);
    }
}
