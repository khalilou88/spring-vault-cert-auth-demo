package com.example.vault.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.vault.core.VaultKeyValueOperationsSupport;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultHealth;
import org.springframework.vault.support.VaultResponse;
import org.springframework.vault.support.Versioned;

import java.util.Map;

@Service
public class VaultService {

    private final VaultTemplate vaultTemplate;

    @Value("${spring.cloud.vault.kv.backend:secret}")
    private String backend;

    public VaultService(VaultTemplate vaultTemplate) {
        this.vaultTemplate = vaultTemplate;
    }

    /**
     * Read a secret from Vault KV v2
     */
    public Map<String, Object> readSecret(String path) {
        try {
            Versioned<Map<String, Object>> response = vaultTemplate
                    .opsForVersionedKeyValue(backend)
                    .get(path);

            return response != null ? response.getData() : null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read secret from path: " + path, e);
        }
    }

    /**
     * Write a secret to Vault KV v2
     */
    public void writeSecret(String path, Map<String, Object> data) {
        try {
            vaultTemplate
                    .opsForVersionedKeyValue(backend)
                    .put(path, data);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write secret to path: " + path, e);
        }
    }

    /**
     * Read a specific key from a secret
     */
    public String readSecretValue(String path, String key) {
        Map<String, Object> secret = readSecret(path);
        return secret != null ? (String) secret.get(key) : null;
    }

    /**
     * Check if Vault is accessible
     */
    public boolean isVaultHealthy() {
        try {
            VaultHealth response = vaultTemplate.opsForSys().health();
            return response.isInitialized() && !response.isSealed() && !response.isStandby();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Read secret using KV v1 (if needed)
     */
    public Map<String, Object> readSecretV1(String path) {
        try {
            VaultResponse response = vaultTemplate
                    .opsForKeyValue(backend, VaultKeyValueOperationsSupport.KeyValueBackend.KV_1)
                    .get(path);

            return response != null ? response.getData() : null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read secret from path: " + path, e);
        }
    }
}