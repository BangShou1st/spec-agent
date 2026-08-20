-- The normal local product database is spec_agent. Test-profile Spring Boot
-- processes use the separate spec_agent_test database so cleanup SQL cannot
-- modify normal developer data.
CREATE DATABASE spec_agent_test OWNER spec_agent;
