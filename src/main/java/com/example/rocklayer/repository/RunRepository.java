package com.example.rocklayer.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Repository
public class RunRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RunRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void save(String id, String status, JsonNode request, JsonNode response) {
        save(id, null, status, request, response);
    }

    @Transactional
    public void save(String id, String createdAt, String status, JsonNode request, JsonNode response) {
        try {
            String sql = "INSERT INTO runs(id, created_at, status, request_json, response_json) VALUES (?, "
                    + (createdAt == null || createdAt.isBlank() ? "datetime('now')" : "?") + ", ?, ?, ?)";
            Object[] args = createdAt == null || createdAt.isBlank()
                    ? new Object[]{id, status, objectMapper.writeValueAsString(request), objectMapper.writeValueAsString(response)}
                    : new Object[]{id, createdAt, status, objectMapper.writeValueAsString(request), objectMapper.writeValueAsString(response)};
            jdbcTemplate.update(sql, args);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Run is not serializable", e);
        }
    }

    public List<Map<String, Object>> list() {
        return jdbcTemplate.queryForList("SELECT id, created_at, status FROM runs ORDER BY created_at DESC, id DESC");
    }

    public JsonNode find(String id) {
        var rows = jdbcTemplate.queryForList("SELECT response_json FROM runs WHERE id = ?", id);
        if (rows.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readTree((String) rows.get(0).get("response_json"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored run is not valid JSON", e);
        }
    }

    @Transactional
    public void clear() {
        jdbcTemplate.update("DELETE FROM runs");
    }

    public List<Map<String, Object>> allRows() {
        return jdbcTemplate.queryForList("SELECT id, created_at, status, request_json, response_json FROM runs ORDER BY created_at, id");
    }
}
