# HashiCorp Vault Server with Docker Setup

This guide walks you through setting up a HashiCorp Vault server using Docker with TLS and certificate authentication for development and testing purposes.

## Prerequisites

- Docker and Docker Compose installed
- OpenSSL for certificate generation
- Basic understanding of PKI concepts

## Step 1: Create Directory Structure

```bash
mkdir vault-docker-setup
cd vault-docker-setup

# Create necessary directories
mkdir -p {config,data,certs,policies,scripts}
```

## Step 2: Generate Certificates

### Create Certificate Authority (CA)

```bash
cd certs

# Generate CA private key
openssl genrsa -out ca.key 4096

# Generate CA certificate
openssl req -new -x509 -key ca.key -sha256 -subj "/C=US/ST=CA/O=MyOrg/CN=VaultCA" -days 3650 -out ca.crt
```

### Create Vault Server Certificate

```bash
# Generate server private key
openssl genrsa -out vault-server.key 4096

# Create certificate signing request
openssl req -new -key vault-server.key -out vault-server.csr -config <(
cat <<EOF
[req]
default_bits = 4096
prompt = no
distinguished_name = req_distinguished_name
req_extensions = req_ext

[req_distinguished_name]
C=US
ST=CA
O=MyOrg
CN=vault.local

[req_ext]
subjectAltName = @alt_names

[alt_names]
DNS.1 = vault.local
DNS.2 = localhost
DNS.3 = vault
IP.1 = 127.0.0.1
IP.2 = 172.20.0.10
EOF
)

# Sign the server certificate
openssl x509 -req -in vault-server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out vault-server.crt -days 365 -extensions req_ext -extfile <(
cat <<EOF
[req_ext]
subjectAltName = @alt_names

[alt_names]
DNS.1 = vault.local
DNS.2 = localhost
DNS.3 = vault
IP.1 = 127.0.0.1
IP.2 = 172.20.0.10
EOF
)

# Clean up CSR
rm vault-server.csr
```

### Create Client Certificate

```bash
# Generate client private key
openssl genrsa -out client.key 4096

# Create client certificate signing request
openssl req -new -key client.key -out client.csr -subj "/C=US/ST=CA/O=MyOrg/CN=vault-client"

# Sign the client certificate
openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out client.crt -days 365

# Create PKCS12 keystore for Spring Boot
openssl pkcs12 -export -in client.crt -inkey client.key -out client-keystore.p12 -name client -CAfile ca.crt -caname root -password pass:changeit

# Create Java truststore
keytool -import -file ca.crt -alias ca -keystore truststore.jks -storepass changeit -noprompt

# Clean up CSR
rm client.csr

cd ..
```

## Step 3: Create Vault Configuration

### Create Vault Config File

```bash
cat > config/vault.hcl <<EOF
# Vault configuration file

# Storage backend
storage "file" {
  path = "/vault/data"
}

# Network listener
listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_cert_file = "/vault/certs/vault-server.crt"
  tls_key_file  = "/vault/certs/vault-server.key"
  tls_client_ca_file = "/vault/certs/ca.crt"
  tls_require_and_verify_client_cert = false
  tls_disable_client_certs = false
}

# API address
api_addr = "https://vault.local:8200"

# Cluster address
cluster_addr = "https://vault.local:8201"

# UI
ui = true

# Disable mlock for development (don't use in production)
disable_mlock = true

# Log level
log_level = "Info"
EOF
```

## Step 4: Create Docker Compose Configuration

### Create docker-compose.yml

```bash
cat > docker-compose.yml <<'EOF'
services:
  vault:
    image: hashicorp/vault:1.15.2
    container_name: vault-server
    hostname: vault.local
    cap_add:
      - IPC_LOCK
    environment:
      VAULT_DEV_ROOT_TOKEN_ID: myroot
      VAULT_DEV_LISTEN_ADDRESS: 0.0.0.0:8200
    ports:
      - "8200:8200"
    volumes:
      - ./data:/vault/data:rw
      - ./certs:/vault/certs:ro
      - ./config:/vault/config:ro
    networks:
      vault-network:
        ipv4_address: 172.20.0.10
    command: ["vault", "server", "-config=/vault/config/vault.hcl"]
    healthcheck:
      test: ["CMD", "vault", "status"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 30s

networks:
  vault-network:
    driver: bridge
    ipam:
      config:
        - subnet: 172.20.0.0/16
EOF
```

## Step 5: Create Initialization Scripts

### Create Vault Initialization Script

```bash
cat > scripts/init-vault.sh <<'EOF'
#!/bin/bash

# Wait for Vault to be ready
echo "Waiting for Vault to be ready..."
until curl -k -s https://vault.local:8200/v1/sys/health > /dev/null 2>&1; do
  sleep 2
done

echo "Vault is ready. Initializing..."

# Initialize Vault (only if not already initialized)
if ! vault status | grep -q "Initialized.*true"; then
  echo "Initializing Vault..."
  mkdir -p vault-credentials
  vault operator init -key-shares=1 -key-threshold=1 -format=json > ./vault-credentials/vault-init.json
  
  # Extract keys
  UNSEAL_KEY=$(cat ./vault-credentials/vault-init.json | jq -r '.unseal_keys_b64[0]')
  ROOT_TOKEN=$(cat ./vault-credentials/vault-init.json | jq -r '.root_token')
  
  # Save keys to files
  echo $UNSEAL_KEY > ./vault-credentials/unseal_key
  echo $ROOT_TOKEN > ./vault-credentials/root_token
  
  echo "Vault initialized. Keys saved to ./vault-credentials/"
else
  echo "Vault already initialized"
fi

# Unseal Vault
if vault status | grep -q "Sealed.*true"; then
  echo "Unsealing Vault..."
  if [ -f ./vault-credentials/unseal_key ]; then
    vault operator unseal $(cat ./vault-credentials/unseal_key)
  else
    echo "Unseal key not found. Please unseal manually."
  fi
else
  echo "Vault already unsealed"
fi
EOF

chmod +x scripts/init-vault.sh
```

### Create Configuration Script

```bash
cat > scripts/configure-vault.sh <<'EOF'
#!/bin/bash

# Set Vault address
export VAULT_ADDR=https://vault.local:8200
export VAULT_CACERT=./certs/ca.crt
export VAULT_TOKEN=$(cat vault-credentials/root_token)

# Login with root token
if [ -f ./vault-credentials/root_token ]; then
  vault login $(cat ./vault-credentials/root_token)
else
  echo "Please provide root token:"
  vault login
fi

echo "Configuring Vault..."

# Enable certificate authentication
echo "Enabling certificate authentication..."
vault auth enable cert

# Configure certificate authentication
echo "Configuring certificate auth method..."
vault write auth/cert/certs/web-cert \
    display_name=web \
    policies=web-policy \
    certificate=@certs/ca.crt \
    ttl=3600

# Create policy for Spring Boot application
echo "Creating web policy..."
cat > policies/web-policy.hcl <<POLICY
# Allow reading application secrets
path "secret/data/application/*" {
  capabilities = ["read"]
}

# Allow full access to vault-demo secrets
path "secret/data/vault-demo/*" {
  capabilities = ["create", "read", "update", "delete", "list"]
}

# Allow listing secrets
path "secret/metadata/*" {
  capabilities = ["list"]
}
POLICY

vault policy write web-policy policies/web-policy.hcl

# Enable KV v2 secrets engine (if not already enabled)
echo "Enabling KV v2 secrets engine..."
vault secrets enable -path=secret kv-v2 2>/dev/null || echo "KV v2 already enabled"

# Add sample secrets
echo "Adding sample secrets..."
vault kv put secret/application \
    database.password=mydbpassword \
    database.username=myuser \
    api.key=myapikey123 \
    jwt.secret=myjwtsecret

vault kv put secret/vault-demo/config \
    app.name="Vault Demo Application" \
    environment=development \
    debug=true \
    database.url="jdbc:postgresql://localhost:5432/mydb"

vault kv put secret/vault-demo/database \
    host=localhost \
    port=5432 \
    database=myapp \
    username=appuser \
    password=apppass

echo "Vault configuration complete!"
echo ""
echo "Test certificate authentication:"
echo "vault write auth/cert/login name=web-cert"
EOF

chmod +x scripts/configure-vault.sh
```

## Step 6: Start and Configure Vault

### Add Vault.local to /etc/hosts

```bash
echo "127.0.0.1 vault.local" | sudo tee -a /etc/hosts
```

### Start Vault Server

```bash
# Start Vault in detached mode
docker compose up -d

# Check logs
docker compose logs -f vault
```

### Initialize and Configure Vault

```bash
# Set environment variables
export VAULT_ADDR=https://vault.local:8200
export VAULT_CACERT=./certs/ca.crt

# Initialize Vault 
./scripts/init-vault.sh


# Configure Vault
./scripts/configure-vault.sh
```

## Step 7: Test Certificate Authentication

### Test with Vault CLI

```bash
# Test certificate authentication
export VAULT_CLIENT_CERT=certs/client.crt
export VAULT_CLIENT_KEY=certs/client.key

vault write auth/cert/login name=web-cert

# List available secrets
vault kv list secret/

# Read a secret
vault kv get secret/application
```

### Test with curl

```bash
# Test certificate authentication with curl
curl --cert certs/client.crt \
     --key certs/client.key \
     --cacert certs/ca.crt \
     -X POST \
     -d '{"name": "web-cert"}' \
     https://vault.local:8200/v1/auth/cert/login
```

## Step 8: Integration with Spring Boot

### Copy Certificates to Spring Boot Project

```bash
# Copy certificates to your Spring Boot project
cp certs/client-keystore.p12 src/main/resources/
cp certs/truststore.jks src/main/resources/
```

### Update Spring Boot application.yml

Make sure your Spring Boot application.yml points to the correct Vault address:

```yaml
spring:
  cloud:
    vault:
      uri: https://vault.local:8200
      # ... rest of configuration
```

## Management Commands

### Useful Docker Commands

```bash
# View logs
docker compose logs vault

# Stop Vault
docker compose down

# Restart Vault
docker compose restart vault

# Execute commands in Vault container
docker compose exec vault vault status

# Backup Vault data
tar -czf vault-backup-$(date +%Y%m%d).tar.gz data/
```

### Useful Vault Commands

```bash
# Check Vault status
vault status

# List authentication methods
vault auth list

# List enabled secrets engines
vault secrets list

# View policies
vault policy list
vault policy read web-policy

# Test authentication
vault write auth/cert/login name=web-cert
```

## Production Considerations

⚠️ **Important**: This setup is for development/testing only. For production:

1. **Security**:
   - Use proper CA infrastructure
   - Enable audit logging
   - Use strong passwords and key lengths
   - Enable TLS client certificate verification
   - Use HashiCorp Vault Enterprise features

2. **High Availability**:
   - Use Consul or other HA storage backends
   - Deploy multiple Vault instances
   - Implement proper load balancing

3. **Monitoring**:
   - Set up comprehensive logging
   - Monitor Vault metrics
   - Implement alerting

4. **Backup**:
   - Implement regular backup procedures
   - Test backup restoration
   - Use encrypted backups

## Troubleshooting

### Common Issues

1. **Certificate Issues**:
   - Verify certificate chain
   - Check certificate expiration
   - Ensure proper SAN configuration

2. **Network Issues**:
   - Check Docker network configuration
   - Verify port mappings
   - Test connectivity with curl

3. **Permission Issues**:
   - Check Vault policies
   - Verify authentication method configuration
   - Review audit logs

### Debug Commands

```bash
# Check certificate details
openssl x509 -in certs/vault-server.crt -text -noout
openssl x509 -in certs/client.crt -text -noout

# Test TLS connection
openssl s_client -connect vault.local:8200 -CAfile certs/ca.crt

# Check Vault configuration
docker compose exec vault cat /vault/config/vault.hcl
```


Directory Structure Created:
```bash
vault-docker-setup/
├── config/           # Vault configuration
├── data/            # Vault data (persistent)
├── certs/           # All certificates and keystores
├── policies/        # Vault policies
├── scripts/         # Automation scripts
└── docker-compose.yml
```