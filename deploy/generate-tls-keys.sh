#!/bin/bash
# Regenerates the project's single TLS key pair (requirement 3 / plan.md §3: the node verifies
# the manager's identity). Normally never run: the output is committed and packaged in the JARs.
#
#   manager/src/main/resources/tls/manager.p12    manager private key + self-signed certificate,
#                                                  served via spring.ssl bundle "manager"
#   node/src/main/resources/tls/truststore.p12    that certificate only, so a node trusts exactly
#                                                  this manager and nothing else
#
# Rerun only to add a host name/IP nodes use to reach the manager (the certificate's SAN must
# match it), then rebuild and roll out a node release.
#
# Usage: deploy/generate-tls-keys.sh [extra-host-or-ip ...]   e.g.  deploy/generate-tls-keys.sh 192.168.1.20
set -euo pipefail
cd "$(dirname "$0")/.."
KEYSTORE=manager/src/main/resources/tls/manager.p12
TRUSTSTORE=node/src/main/resources/tls/truststore.p12
PASSWORD=changeit   # protects nothing secret: the key is committed anyway (demo only, see plan.md)

# Default names: local runs, the docker-compose service name, and Docker Desktop's host alias.
SAN="dns:localhost,dns:manager,dns:host.docker.internal,ip:127.0.0.1"
for h in "$@"; do
  if [[ "$h" =~ ^[0-9.]+$ ]]; then SAN="$SAN,ip:$h"; else SAN="$SAN,dns:$h"; fi
done

mkdir -p "$(dirname "$KEYSTORE")" "$(dirname "$TRUSTSTORE")"
rm -f "$KEYSTORE" "$TRUSTSTORE"
# 1. New key pair + self-signed certificate, valid 10 years.
keytool -genkeypair -alias manager -keyalg RSA -keysize 2048 -validity 3650 -storetype PKCS12 \
  -keystore "$KEYSTORE" -storepass "$PASSWORD" -dname "CN=scanning-manager" -ext "SAN=$SAN"
# 2. Copy only the certificate (no private key) into the node truststore.
keytool -exportcert -alias manager -keystore "$KEYSTORE" -storepass "$PASSWORD" -rfc \
  | keytool -importcert -noprompt -alias manager -storetype PKCS12 -keystore "$TRUSTSTORE" -storepass "$PASSWORD"
echo "created $KEYSTORE and $TRUSTSTORE with SAN=$SAN"
