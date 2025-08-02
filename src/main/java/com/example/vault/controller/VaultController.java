package com.example.vault.controller;

import com.example.vault.service.VaultService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/vault")
public class VaultController {

    private final VaultService vaultService;

    // Example of injecting secrets directly via @Value
    @Value("${database.password:default}")
    private String dbPassword;

    @Value("${api.key:default}")
    private String apiKey;

    public VaultController(VaultService vaultService) {
        this.vaultService = vaultService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        boolean healthy = vaultService.isVaultHealthy();

        response.put("vault_healthy", healthy);
        response.put("status", healthy ? "UP" : "DOWN");

        return ResponseEntity.ok(response);
    }

    @GetMapping("/secret/{path}")
    public ResponseEntity<Map<String, Object>> getSecret(@PathVariable String path) {
        try {
            Map<String, Object> secret = vaultService.readSecret(path);

            if (secret == null) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("path", path);
            response.put("data", secret);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to retrieve secret");
            error.put("message", e.getMessage());

            return ResponseEntity.internalServerError().body(error);
        }
    }

    @PostMapping("/secret/{path}")
    public ResponseEntity<Map<String, String>> writeSecret(
            @PathVariable String path,
            @RequestBody Map<String, Object> data) {
        try {
            vaultService.writeSecret(path, data);

            Map<String, String> response = new HashMap<>();
            response.put("message", "Secret written successfully");
            response.put("path", path);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to write secret");
            error.put("message", e.getMessage());

            return ResponseEntity.internalServerError().body(error);
        }
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, String>> getConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("database_password", dbPassword);
        config.put("api_key", apiKey);
        config.put("note", "These values are injected from Vault");

        return ResponseEntity.ok(config);
    }

    @GetMapping("/secret/{path}/key/{key}")
    public ResponseEntity<Map<String, String>> getSecretValue(
            @PathVariable String path,
            @PathVariable String key) {
        try {
            String value = vaultService.readSecretValue(path, key);

            if (value == null) {
                return ResponseEntity.notFound().build();
            }

            Map<String, String> response = new HashMap<>();
            response.put("path", path);
            response.put("key", key);
            response.put("value", value);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to retrieve secret value");
            error.put("message", e.getMessage());

            return ResponseEntity.internalServerError().body(error);
        }
    }
}