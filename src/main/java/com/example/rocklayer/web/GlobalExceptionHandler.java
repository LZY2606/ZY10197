package com.example.rocklayer.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private final ObjectMapper mapper;

    public GlobalExceptionHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ObjectNode> badRequest(IllegalArgumentException e) {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", "INVALID");
        node.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(node);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ObjectNode> serverError(Exception e) {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", "ERROR");
        node.put("message", e.getClass().getSimpleName() + ": " + e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(node);
    }
}
