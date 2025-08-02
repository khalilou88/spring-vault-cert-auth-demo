# Spring Boot + Vault Certificate Authentication Setup

## Prerequisites

1. HashiCorp Vault server running with TLS enabled
2. Client certificate and private key
3. CA certificate that signed the client certificate

## Step 1: Prepare Certificates

### Create Client Keystore (PKCS12)
```bash
# Convert PEM certificates to PKCS12 keystore
openssl pkcs12 -export -in client.crt -inkey client.key -out client-keystore.p12 -name client -CAfile ca.crt -caname root

# Set password when prompted (use same password in application.yml)
```

### Create Truststore (JKS)
```bash
# Import CA certificate into truststore
keytool -import -file ca.crt -alias ca -keystore truststore.jks -storepass changeit
```

### Place Certificates
- Put `client-keystore.p12` and `truststore.jks` in `src/main/resources/`

## Step 2: Configure Vault Server

### Enable Certificate Authentication
```bash
# Enable cert auth method
vault auth enable cert

# Configure the CA certificate
vault write auth/cert/certs/web-cert \
    display_name=web \
    policies=web-policy \
    certificate=@ca.crt \
    ttl=3600
```

### Create Policy
```bash
# Create policy file (web-policy.hcl)
cat << EOF > web-policy.hcl
path "secret/data/application/*" {
  capabilities = ["read"]
}

path "secret/data/vault-demo/*" {
  capabilities = ["read", "create", "update"]
}
EOF

# Apply the policy
vault policy write web-policy web-policy.hcl
```

### Add Sample Secrets
```bash
# Add some test secrets
vault kv put secret/application database.password=mydbpass api.key=myapikey

vault kv put secret/vault-demo/config \
    app.name="Vault Demo" \
    environment="production" \
    debug=false
```

## Step 3: Application Configuration

### Environment Variables (Optional)
```bash
export VAULT_URI=https://vault.example.com:8200
export VAULT_KEYSTORE_PASSWORD=changeit
export VAULT_TRUSTSTORE_PASSWORD=changeit
```

### Alternative Configuration (bootstrap.yml)
If you prefer bootstrap configuration:

```yaml
spring:
  application:
    name: vault-demo
  cloud:
    vault:
      uri: ${VAULT_URI:https://vault.example.com:8200}
      authentication: CERT
      ssl:
        cert-auth-path: cert
        key-store: classpath:client-keystore.p12
        key-store-password: ${VAULT_KEYSTORE_PASSWORD:changeit}
        key-store-type: PKCS12
        trust-store: classpath:truststore.jks
        trust-store-password: ${VAULT_TRUSTSTORE_PASSWORD:changeit}
        trust-store-type: JKS
      kv:
        enabled: true
        backend: secret
        default-context: application
        application-name: ${spring.application.name}
```

## Step 4: Testing

### Test Endpoints
```bash
# Check Vault health
curl http://localhost:8080/api/vault/health

# Get injected configuration
curl http://localhost:8080/api/vault/config

# Read a secret
curl http://localhost:8080/api/vault/secret/vault-demo/config

# Write a secret
curl -X POST http://localhost:8080/api/vault/secret/vault-demo/test \
  -H "Content-Type: application/json" \
  -d '{"key1": "value1", "key2": "value2"}'

# Get specific key from secret
curl http://localhost:8080/api/vault/secret/vault-demo/config/key/app.name
```

## Security Best Practices

1. **Certificate Security**
   - Store certificates securely in production
   - Use environment variables or external configuration for passwords
   - Regularly rotate certificates

2. **Vault Policies**
   - Follow principle of least privilege
   - Create specific policies for each application
   - Regularly audit access patterns

3. **Network Security**
   - Use TLS for all Vault communications
   - Restrict network access to Vault server
   - Consider using Vault Agent for secret caching

## Troubleshooting

### Common Issues

1. **Certificate Validation Errors**
   - Verify certificate chain is complete
   - Check certificate expiration dates
   - Ensure CA certificate is properly imported

2. **Authentication Failures**
   - Verify cert auth method is enabled in Vault
   - Check policy assignments
   - Validate certificate permissions

3. **SSL Handshake Failures**
   - Verify keystore and truststore passwords
   - Check certificate formats (PKCS12 vs JKS)
   - Ensure proper certificate encoding

### Debug Configuration
Add to application.yml for debugging:
```yaml
logging:
  level:
    org.springframework.vault: DEBUG
    org.springframework.web.client.RestTemplate: DEBUG
```

## Production Considerations

1. **External Configuration**
   - Use Kubernetes secrets or similar for certificates
   - Store sensitive configuration in environment variables
   - Consider using HashiCorp Vault Agent

2. **Monitoring**
   - Monitor certificate expiration
   - Set up alerts for Vault connectivity issues
   - Track secret access patterns

3. **High Availability**
   - Configure multiple Vault endpoints
   - Implement proper retry logic
   - Consider circuit breaker patterns



./mvnw spring-boot:run


# Check the keystore contents
keytool -list -v -keystore src/main/resources/client-keystore.p12 -storepass changeit

# Check the truststore contents
keytool -list -v -keystore src/main/resources/truststore.jks -storepass changeit