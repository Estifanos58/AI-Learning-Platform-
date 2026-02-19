#!/bin/bash
# Generate RSA key pair for JWT signing
mkdir -p src/main/resources/keys

# Generate private key
openssl genpkey -algorithm RSA -out src/main/resources/keys/private.pem -pkeyopt rsa_keygen_bits:2048

# Extract public key
openssl rsa -pubout -in src/main/resources/keys/private.pem -out src/main/resources/keys/public.pem

echo "RSA keys generated successfully in src/main/resources/keys/"
