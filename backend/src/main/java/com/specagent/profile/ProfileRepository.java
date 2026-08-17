package com.specagent.profile;

import com.specagent.common.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProfileRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Json json;
    private final RowMapper<Profile> rowMapper;

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    public ProfileRepository(NamedParameterJdbcTemplate jdbcTemplate, Json json) {
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> new Profile(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("description"),
                json.readList(rs.getString("aspects"), STRING_LIST),
                json.readList(rs.getString("spec_section_definitions"), STRING_LIST),
                json.readList(rs.getString("question_policy_hints"), STRING_LIST),
                rs.getString("tone"),
                rs.getTimestamp("created_at").toInstant());
    }

    public Optional<Profile> findById(UUID id) {
        String sql = "SELECT * FROM profiles WHERE id = :id";
        return jdbcTemplate.query(sql, Map.of("id", id), rowMapper).stream().findFirst();
    }

    public Optional<Profile> findByName(String name) {
        String sql = "SELECT * FROM profiles WHERE name = :name";
        return jdbcTemplate.query(sql, Map.of("name", name), rowMapper).stream().findFirst();
    }

    public List<Profile> findAll() {
        String sql = "SELECT * FROM profiles ORDER BY name";
        return jdbcTemplate.query(sql, Map.of(), rowMapper);
    }
}
