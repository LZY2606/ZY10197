package com.example.rocklayer.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ScenarioRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ScenarioRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public JsonNode get() {
        var rows = jdbcTemplate.queryForList("SELECT payload FROM scenario_state WHERE id = 1");
        if (rows.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readTree((String) rows.get(0).get("payload"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored scenario is not valid JSON", e);
        }
    }

    @Transactional
    public void save(JsonNode scenario) {
        try {
            String payload = objectMapper.writeValueAsString(scenario);
            jdbcTemplate.update("DELETE FROM scenario_state WHERE id = 1");
            jdbcTemplate.update("INSERT INTO scenario_state(id, updated_at, payload) VALUES (1, datetime('now'), ?)", payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Scenario is not serializable", e);
        }
    }

    @Transactional
    public void clear() {
        jdbcTemplate.update("DELETE FROM scenario_state");
    }
}
