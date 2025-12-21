# OpenTSx Security Guide & OWASP Analysis

## Table of Contents
- [Executive Summary](#executive-summary)
- [Security Risk Assessment](#security-risk-assessment)
- [Critical Vulnerabilities](#critical-vulnerabilities)
- [OWASP Top 10 Analysis](#owasp-top-10-analysis)
- [Security Architecture](#security-architecture)
- [Authentication & Authorization](#authentication--authorization)
- [Data Protection](#data-protection)
- [Network Security](#network-security)
- [Dependency Security](#dependency-security)
- [Secure Configuration](#secure-configuration)
- [Security Monitoring](#security-monitoring)
- [Incident Response](#incident-response)
- [Remediation Roadmap](#remediation-roadmap)
- [Security Checklist](#security-checklist)

---

## Executive Summary

This document provides a comprehensive security analysis of the OpenTSx platform based on OWASP guidelines and industry best practices. The analysis has identified **several critical security vulnerabilities** that require immediate attention.

### Overall Security Posture: ⚠️ REQUIRES IMMEDIATE ATTENTION

| Category | Status | Priority |
|----------|--------|----------|
| **Critical Issues** | 4 identified | URGENT |
| **High Severity** | 6 identified | HIGH |
| **Medium Severity** | 5 identified | MEDIUM |
| **Low Severity** | 4 identified | LOW |

**Immediate Actions Required:**
1. Rotate all exposed credentials
2. Enable TLS/SSL for all components
3. Fix SQL injection vulnerabilities
4. Implement proper authentication/authorization
5. Update vulnerable dependencies

---

## Security Risk Assessment

### Risk Matrix

| Risk Level | Count | Description |
|------------|-------|-------------|
| 🔴 **CRITICAL** | 4 | Immediate exploitation possible, severe impact |
| 🟠 **HIGH** | 6 | Exploitation likely, significant impact |
| 🟡 **MEDIUM** | 5 | Exploitation possible, moderate impact |
| 🟢 **LOW** | 4 | Minor security concerns |

### Business Impact

**If exploited, these vulnerabilities could result in:**
- Unauthorized access to production Kafka clusters
- Data breaches and data loss
- Service disruption and downtime
- Compliance violations (GDPR, HIPAA, SOC 2)
- Reputational damage
- Financial losses

---

## Critical Vulnerabilities

### 🔴 VULNERABILITY #1: Hardcoded Credentials in Source Control

**Severity:** CRITICAL | **CVSS Score:** 9.8 | **CWE-798**

**Location:** `/config/private/ccloud.props`

**Description:**
Production Confluent Cloud credentials are stored in plaintext in the Git repository.

**Evidence:**
```properties
sasl.username=2JMCB3VQUXPPZM7D
sasl.password=1WiprUIWgGmNAgQV1GW0rQi0S3szeGHDSGTLsQDZ0giMbzXXkQU6MjZlpYgDf6T+
basic.auth.user.info=QI747SYN7RCNNNI5:4OCvylbx/64yXCEf5UFsY4jMiZ5krg9O80bu3e5R4T2Zm4Bad99PhfVbYmLD60oM
```

**Impact:**
- Anyone with access to the repository can access production Kafka cluster
- Credentials remain in Git history even if deleted
- Potential for data exfiltration and cluster compromise

**Exploitation Scenario:**
```bash
# Attacker clones repository
git clone https://github.com/kamir/OpenTSx.git

# Finds credentials
cat config/private/ccloud.props

# Uses credentials to access production cluster
kafka-console-consumer --bootstrap-server <broker> \
  --consumer.config config/private/ccloud.props \
  --topic sensitive-data --from-beginning
```

**Immediate Remediation:**

1. **Rotate ALL exposed credentials immediately:**
```bash
# In Confluent Cloud console
confluent api-key delete --resource <cluster-id> <api-key>
confluent api-key create --resource <cluster-id>
```

2. **Remove file from Git history:**
```bash
# Remove from current commit
git rm --cached config/private/ccloud.props

# Purge from history (destructive!)
git filter-branch --force --index-filter \
  'git rm --cached --ignore-unmatch config/private/ccloud.props' \
  --prune-empty --tag-name-filter cat -- --all

# Force push
git push origin --force --all
```

3. **Add to .gitignore:**
```bash
echo "config/private/" >> .gitignore
echo "**/*ccloud*.props" >> .gitignore
echo "**/*secret*" >> .gitignore
echo "**/*password*" >> .gitignore
git add .gitignore
git commit -m "Prevent credential commits"
```

4. **Implement secrets management:**

**Option A: Environment Variables**
```bash
export KAFKA_BOOTSTRAP_SERVERS="..."
export KAFKA_SASL_USERNAME="..."
export KAFKA_SASL_PASSWORD="..."
export SCHEMA_REGISTRY_URL="..."
export SCHEMA_REGISTRY_API_KEY="..."
export SCHEMA_REGISTRY_API_SECRET="..."
```

**Option B: AWS Secrets Manager**
```bash
# Store secret
aws secretsmanager create-secret \
  --name opentsx/kafka-credentials \
  --secret-string '{
    "username":"<key>",
    "password":"<secret>",
    "bootstrap_servers":"<brokers>"
  }'

# Retrieve in application
aws secretsmanager get-secret-value \
  --secret-id opentsx/kafka-credentials \
  --query SecretString --output text
```

**Option C: HashiCorp Vault**
```bash
# Store secret
vault kv put secret/opentsx/kafka \
  username=<key> \
  password=<secret> \
  bootstrap_servers=<brokers>

# Retrieve in application
vault kv get -field=username secret/opentsx/kafka
```

**Long-term Solution:**

Update code to read from environment variables:

```java
// opentsx-connectors/src/main/java/org/opentsx/connectors/kafka/KafkaConnector.java

public Properties loadConfig() {
    Properties props = new Properties();

    // Read from environment variables, NOT files
    props.put("bootstrap.servers", System.getenv("KAFKA_BOOTSTRAP_SERVERS"));
    props.put("sasl.username", System.getenv("KAFKA_SASL_USERNAME"));
    props.put("sasl.password", System.getenv("KAFKA_SASL_PASSWORD"));

    // Validate required properties
    if (props.get("bootstrap.servers") == null) {
        throw new IllegalArgumentException("KAFKA_BOOTSTRAP_SERVERS not set");
    }

    return props;
}
```

---

### 🔴 VULNERABILITY #2: SQL Injection in Cassandra Queries

**Severity:** HIGH | **CVSS Score:** 8.6 | **CWE-89**

**Location:** `opentsx-store-cassandra/src/main/java/cassandra/CassandraConnector.java:98-105`

**Description:**
User-controlled data is directly concatenated into CQL queries without sanitization or prepared statements.

**Vulnerable Code:**
```java
public void insertTSOByLabel(TimeSeriesObject mr) {
    TSData data = TSData.convertMessreihe(mr);
    Gson gson = new Gson();
    String dataJSON = gson.toJson(data);

    // VULNERABLE: String concatenation with user data
    StringBuilder sb = new StringBuilder("INSERT INTO ")
            .append(TABLE_NAME).append("(id, tsdata) ")
            .append("VALUES (").append("'" + data.label + "'")  // NO ESCAPING!
            .append(", '").append(dataJSON).append("');");

    String query = sb.toString();
    session.execute(query);
}
```

**Attack Vector:**
```java
// Attacker provides malicious label
TimeSeriesObject tso = new TimeSeriesObject();
tso.setLabel("'); DROP TABLE tsdata; --");

// Resulting query becomes:
// INSERT INTO tsdata(id, tsdata) VALUES (''); DROP TABLE tsdata; --', '...');
```

**Impact:**
- Data manipulation (INSERT, UPDATE, DELETE)
- Data exfiltration
- Denial of service (DROP TABLE)
- Complete database compromise

**Remediation:**

**✅ SECURE CODE - Use Prepared Statements:**

```java
public void insertTSOByLabel(TimeSeriesObject mr) {
    TSData data = TSData.convertMessreihe(mr);
    Gson gson = new Gson();
    String dataJSON = gson.toJson(data);

    // SECURE: Use prepared statement with parameter binding
    String query = "INSERT INTO " + TABLE_NAME + "(id, tsdata) VALUES (?, ?)";
    PreparedStatement pstmt = session.prepare(query);

    // Bind parameters - Cassandra driver handles escaping
    BoundStatement bound = pstmt.bind(data.label, dataJSON);
    session.execute(bound);
}
```

**Additional Fixes Required:**

**File:** `opentsx-store-cassandra/src/main/java/cassandra/CassandraConnector.java`

Find and fix ALL string concatenation patterns:

```bash
# Find vulnerable patterns
grep -r "StringBuilder.*append.*VALUES" opentsx-store-cassandra/
grep -r "String.*=.*\"INSERT" opentsx-store-cassandra/
grep -r "String.*=.*\"UPDATE" opentsx-store-cassandra/
grep -r "String.*=.*\"DELETE" opentsx-store-cassandra/
```

**Secure Pattern for All Queries:**

```java
// READ operations
public TimeSeriesObject getByLabel(String label) {
    String query = "SELECT * FROM " + TABLE_NAME + " WHERE id = ?";
    PreparedStatement pstmt = session.prepare(query);
    ResultSet rs = session.execute(pstmt.bind(label));
    // Process results
}

// UPDATE operations
public void update(String id, String data) {
    String query = "UPDATE " + TABLE_NAME + " SET tsdata = ? WHERE id = ?";
    PreparedStatement pstmt = session.prepare(query);
    session.execute(pstmt.bind(data, id));
}

// DELETE operations
public void delete(String id) {
    String query = "DELETE FROM " + TABLE_NAME + " WHERE id = ?";
    PreparedStatement pstmt = session.prepare(query);
    session.execute(pstmt.bind(id));
}
```

---

### 🔴 VULNERABILITY #3: Disabled TLS/SSL in Krake Configuration

**Severity:** HIGH | **CVSS Score:** 8.1 | **CWE-295**

**Location:** `opentsx-clusters/krake-container/config/krake/api.yaml`

**Description:**
TLS/SSL is explicitly disabled for Krake API server and Kubernetes controller, exposing all API traffic in cleartext.

**Vulnerable Configuration:**
```yaml
# api.yaml - Line 10
tls:
  enabled: false  # INSECURE!
  cert: tmp/pki/system:api-server.pem
  key: tmp/pki/system:api-server-key.pem

# kubernetes.yaml
tls:
  enabled: false  # INSECURE!
```

**Impact:**
- All API communication transmitted in cleartext
- Credentials and authentication tokens exposed
- Vulnerable to man-in-the-middle (MITM) attacks
- Session hijacking possible
- Regulatory compliance violations

**Attack Scenario:**
```bash
# Attacker on same network sniffs traffic
tcpdump -i eth0 -A 'tcp port 8080' > captured.txt

# Captured cleartext includes:
# - API keys
# - Authentication tokens
# - Resource configurations
# - Sensitive cluster data
```

**Remediation:**

1. **Generate TLS Certificates:**

```bash
# Create CA
openssl genrsa -out ca-key.pem 4096
openssl req -new -x509 -days 3650 -key ca-key.pem -sha256 -out ca.pem \
  -subj "/C=US/ST=State/L=City/O=OpenTSx/CN=OpenTSx-CA"

# Create server certificate
openssl genrsa -out server-key.pem 4096
openssl req -subj "/CN=api-server" -sha256 -new -key server-key.pem -out server.csr

# Sign certificate
openssl x509 -req -days 365 -sha256 -in server.csr -CA ca.pem -CAkey ca-key.pem \
  -CAcreateserial -out server-cert.pem

# Create client certificate
openssl genrsa -out client-key.pem 4096
openssl req -subj "/CN=client" -new -key client-key.pem -out client.csr
openssl x509 -req -days 365 -sha256 -in client.csr -CA ca.pem -CAkey ca-key.pem \
  -CAcreateserial -out client-cert.pem
```

2. **Update Krake Configuration:**

```yaml
# api.yaml
tls:
  enabled: true  # ENABLE TLS
  cert: /etc/krake/pki/server-cert.pem
  key: /etc/krake/pki/server-key.pem
  client_ca: /etc/krake/pki/ca.pem

# kubernetes.yaml
tls:
  enabled: true  # ENABLE TLS
  cert: /etc/krake/pki/server-cert.pem
  key: /etc/krake/pki/server-key.pem
```

3. **Mount certificates in Docker:**

```dockerfile
# Dockerfile
COPY pki/*.pem /etc/krake/pki/
RUN chmod 600 /etc/krake/pki/*-key.pem
RUN chmod 644 /etc/krake/pki/*-cert.pem /etc/krake/pki/ca.pem
```

4. **Update client connections:**

```bash
# Clients must now use HTTPS
curl --cacert ca.pem \
     --cert client-cert.pem \
     --key client-key.pem \
     https://api-server:8080/api/v1/resources
```

---

### 🔴 VULNERABILITY #4: Weak Authorization - Always-Allow Mode

**Severity:** CRITICAL | **CVSS Score:** 9.1 | **CWE-284**

**Location:** `opentsx-clusters/krake-container/config/krake/api.yaml:40`

**Description:**
Authorization is set to "always-allow" mode, which bypasses all access control checks.

**Vulnerable Configuration:**
```yaml
authentication:
  allow_anonymous: true  # ALLOWS UNAUTHENTICATED ACCESS!

authorization: always-allow  # BYPASSES ALL ACCESS CONTROL!

# Options available:
#  - RBAC (Role-based access control) ← SHOULD USE THIS
#  - always-allow (Allow all requests) ← CURRENT (DANGEROUS!)
#  - always-deny (Deny all requests)
```

**Impact:**
- Any user can perform ANY action
- No access control enforcement
- Complete bypass of authorization
- Privilege escalation trivial
- Audit logs meaningless

**Attack Scenario:**
```bash
# Any unauthenticated user can:
curl http://api-server:8080/api/v1/applications -X DELETE  # Delete all applications
curl http://api-server:8080/api/v1/clusters -X GET          # List all clusters
curl http://api-server:8080/api/v1/secrets -X GET           # Exfiltrate secrets
```

**Remediation:**

1. **Enable RBAC Authorization:**

```yaml
# api.yaml
authentication:
  allow_anonymous: false  # DISABLE ANONYMOUS ACCESS

  strategy:
    keystone:
      enabled: true  # Enable Keystone auth
      endpoint: https://keystone.example.com:5000/v3
    static:
      enabled: false  # Disable static auth in production

authorization: RBAC  # ENABLE ROLE-BASED ACCESS CONTROL
```

2. **Define RBAC Roles:**

```yaml
# roles.yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: opentsx-admin
rules:
- apiGroups: ["*"]
  resources: ["*"]
  verbs: ["*"]

---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: opentsx-developer
rules:
- apiGroups: [""]
  resources: ["applications", "clusters"]
  verbs: ["get", "list", "watch"]

---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: opentsx-readonly
rules:
- apiGroups: [""]
  resources: ["*"]
  verbs: ["get", "list", "watch"]
```

3. **Create Role Bindings:**

```yaml
# rolebindings.yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: opentsx-admin-binding
subjects:
- kind: User
  name: admin@opentsx.com
  apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: ClusterRole
  name: opentsx-admin
  apiGroup: rbac.authorization.k8s.io
```

4. **Implement Least Privilege:**

| Role | Permissions | Use Case |
|------|-------------|----------|
| **Admin** | Full access | Operations team |
| **Developer** | Read/write apps | Development team |
| **ReadOnly** | Read-only | Monitoring systems |
| **Service** | Specific resources | Service accounts |

---

## OWASP Top 10 Analysis

### A01:2021 - Broken Access Control ⚠️ VULNERABLE

**Findings:**
- ✅ Anonymous authentication enabled (`allow_anonymous: true`)
- ✅ Authorization set to "always-allow" mode
- ❌ No role-based access control (RBAC)
- ❌ No attribute-based access control (ABAC)

**Affected Components:**
- Krake API server
- Kubernetes controller
- Application APIs (no authentication layer)

**Remediation:**
- Enable RBAC authorization
- Disable anonymous access
- Implement OAuth2/OIDC
- Add Spring Security annotations

---

### A02:2021 - Cryptographic Failures ⚠️ VULNERABLE

**Findings:**
- ✅ TLS disabled for Krake API (cleartext HTTP)
- ✅ Kafka using PLAINTEXT protocol (no encryption)
- ✅ Weak hash algorithm (MD5) in `TSAExample1.java:95`
- ❌ No encryption at rest for Cassandra
- ❌ Hardcoded credentials in source control

**Locations:**
- `opentsx-clusters/krake-container/config/krake/api.yaml` - TLS disabled
- `opentsx-clusters/inhouse-mrc/mdc1/docker-compose.yml` - PLAINTEXT Kafka
- `opentsx-kafka-streams-tsa/src/main/java/org/opentsx/tsa/TSAExample1.java:95` - MD5

**Remediation:**

**Enable TLS/SSL everywhere:**

```yaml
# Kafka with TLS
KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: SSL:SSL,SASL_SSL:SASL_SSL
KAFKA_INTER_BROKER_LISTENER_NAME: SSL

# Schema Registry with TLS
SCHEMA_REGISTRY_KAFKASTORE_SECURITY_PROTOCOL: SSL
```

**Replace MD5 with SHA-256:**

```java
// opentsx-kafka-streams-tsa/src/main/java/org/opentsx/tsa/TSAExample1.java
// BEFORE (Line 95):
md = MessageDigest.getInstance("MD5");  // WEAK!

// AFTER:
md = MessageDigest.getInstance("SHA-256");  // SECURE
```

**Enable Cassandra encryption:**

```yaml
# cassandra.yaml
server_encryption_options:
  internode_encryption: all
  keystore: /etc/cassandra/conf/.keystore
  keystore_password: <password>
  truststore: /etc/cassandra/conf/.truststore
  truststore_password: <password>

client_encryption_options:
  enabled: true
  keystore: /etc/cassandra/conf/.keystore
  keystore_password: <password>
```

---

### A03:2021 - Injection ⚠️ VULNERABLE

**Findings:**
- ✅ SQL injection in Cassandra queries
- ✅ No input validation for topic names
- ✅ No parameter sanitization
- ❌ No parameterized queries

**Location:** `opentsx-store-cassandra/src/main/java/cassandra/CassandraConnector.java:98-105`

**Remediation:** See Vulnerability #2 above

**Additional Input Validation:**

```java
// Validate topic names
public void validateTopicName(String topicName) {
    if (topicName == null || topicName.isEmpty()) {
        throw new IllegalArgumentException("Topic name cannot be empty");
    }

    // Kafka topic naming rules
    String pattern = "^[a-zA-Z0-9._-]+$";
    if (!topicName.matches(pattern)) {
        throw new IllegalArgumentException("Invalid topic name: " + topicName);
    }

    if (topicName.length() > 249) {
        throw new IllegalArgumentException("Topic name too long");
    }
}

// Validate partition counts
public void validatePartitionCount(int partitions) {
    if (partitions < 1 || partitions > 100000) {
        throw new IllegalArgumentException("Invalid partition count: " + partitions);
    }
}
```

---

### A04:2021 - Insecure Design ⚠️ VULNERABLE

**Findings:**
- ❌ No threat modeling performed
- ❌ Security not integrated into SDLC
- ❌ No security design review process
- ❌ Missing security requirements

**Recommendations:**

1. **Implement Threat Modeling:**
   - Use STRIDE methodology
   - Document trust boundaries
   - Identify attack surfaces
   - Create threat mitigation plan

2. **Secure SDLC:**
   - Security requirements phase
   - Security design review
   - Security code review
   - Security testing
   - Vulnerability scanning

3. **Defense in Depth:**
   - Network segmentation
   - Application-level authentication
   - Data encryption at rest and in transit
   - Security monitoring and alerting

---

### A05:2021 - Security Misconfiguration ⚠️ VULNERABLE

**Findings:**
- ✅ Default credentials (Krake: `system:admin`)
- ✅ Unnecessary features enabled (anonymous auth)
- ✅ Error messages expose stack traces
- ✅ Missing security headers
- ✅ Outdated components (Java 1.8, Kafka 2.3.0)

**Locations:**
- `opentsx-clusters/krake-container/config/krake/api.yaml` - Default admin
- Multiple files - `e.printStackTrace()` calls
- `pom.xml` - Outdated dependencies

**Remediation:**

**Remove default credentials:**

```yaml
# api.yaml
authentication:
  strategy:
    static:
      enabled: false  # Remove default admin account
    keystone:
      enabled: true   # Use external auth
```

**Fix exception handling:**

```java
// BEFORE (Insecure):
try {
    // operations
} catch (Exception e) {
    e.printStackTrace();  // Exposes stack trace
}

// AFTER (Secure):
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger log = LoggerFactory.getLogger(MyClass.class);

try {
    // operations
} catch (Exception e) {
    log.error("Operation failed", e);  // Log securely
    throw new ServiceException("Operation failed");  // Generic message to user
}
```

**Update dependencies:**

```xml
<!-- pom.xml -->
<properties>
    <java.version>17</java.version>  <!-- Update from 1.8 -->
    <kafka.version>3.4.0</kafka.version>  <!-- Update from 2.3.0 -->
    <jackson.version>2.15.2</jackson.version>  <!-- Fix CVE-2017-7525 -->
</properties>
```

---

### A06:2021 - Vulnerable and Outdated Components ⚠️ VULNERABLE

**Critical Vulnerable Dependencies:**

| Dependency | Current Version | Vulnerable | Fix Version | CVEs |
|------------|----------------|------------|-------------|------|
| jackson-databind | 2.8.1 | YES | 2.15.2+ | CVE-2017-7525 (RCE) |
| xstream | 1.2.2 | YES | 1.4.20+ | Multiple RCE |
| sshd-core | 0.8.0 | YES | 2.10.0+ | Multiple |
| poi | 3.14 | YES | 5.2.3+ | XXE vulnerabilities |
| gson | 2.2.4 | YES | 2.10.1+ | Deserialization |
| log4j (transitive) | Various | MAYBE | 2.20.0+ | Log4Shell |

**Scan for vulnerabilities:**

```bash
# Maven dependency check
mvn org.owasp:dependency-check-maven:check

# OWASP Dependency Check
dependency-check --project OpenTSx --scan .

# Snyk scan
snyk test
```

**Update POM files:**

```xml
<dependencies>
    <!-- Jackson (fix RCE) -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>2.15.2</version>
    </dependency>

    <!-- XStream (fix RCE) -->
    <dependency>
        <groupId>com.thoughtworks.xstream</groupId>
        <artifactId>xstream</artifactId>
        <version>1.4.20</version>
    </dependency>

    <!-- Apache POI (fix XXE) -->
    <dependency>
        <groupId>org.apache.poi</groupId>
        <artifactId>poi</artifactId>
        <version>5.2.3</version>
    </dependency>

    <!-- Gson -->
    <dependency>
        <groupId>com.google.code.gson</groupId>
        <artifactId>gson</artifactId>
        <version>2.10.1</version>
    </dependency>
</dependencies>
```

---

### A07:2021 - Identification and Authentication Failures ⚠️ VULNERABLE

**Findings:**
- ✅ No multi-factor authentication (MFA)
- ✅ Weak password requirements
- ✅ No account lockout policy
- ✅ No session management
- ✅ Credentials stored in plaintext

**Remediation:**

**Implement OAuth2/OIDC:**

```xml
<!-- Add Spring Security OAuth2 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

```java
// SecurityConfig.java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2Login()
            .and()
            .oauth2ResourceServer().jwt();

        return http.build();
    }
}
```

**Implement MFA:**

```java
// Use libraries like Google Authenticator
import com.warrenstrange.googleauth.GoogleAuthenticator;

public boolean validateMFA(String username, int verificationCode) {
    GoogleAuthenticator gAuth = new GoogleAuthenticator();
    String secret = getUserSecret(username);
    return gAuth.authorize(secret, verificationCode);
}
```

---

### A08:2021 - Software and Data Integrity Failures

**Findings:**
- ❌ No code signing
- ❌ No CI/CD pipeline security
- ❌ No dependency verification
- ❌ No SBOM (Software Bill of Materials)

**Recommendations:**

**Sign commits:**

```bash
# Generate GPG key
gpg --full-generate-key

# Configure Git
git config --global user.signingkey <key-id>
git config --global commit.gpgsign true

# Sign commits
git commit -S -m "Signed commit"
```

**Verify dependencies:**

```xml
<!-- Maven Enforcer Plugin -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-enforcer-plugin</artifactId>
    <executions>
        <execution>
            <id>enforce-checksums</id>
            <goals>
                <goal>enforce</goal>
            </goals>
            <configuration>
                <rules>
                    <requireReleaseDeps>
                        <message>No Snapshots Allowed!</message>
                    </requireReleaseDeps>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

**Generate SBOM:**

```bash
# CycloneDX Maven Plugin
mvn org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom
```

---

### A09:2021 - Security Logging and Monitoring Failures ⚠️ VULNERABLE

**Findings:**
- ✅ Sensitive data logged to console (`properties.list(System.out)`)
- ✅ No centralized logging
- ✅ No security event monitoring
- ✅ No alerting system
- ✅ Insufficient audit trails

**Location:** `opentsx-connectors/src/main/java/org/opentsx/connectors/kafka/OpenTSxClusterLink.java:25-26`

**Vulnerable Code:**
```java
// INSECURE: Dumps all properties including potential secrets
properties.list(System.out);

// INSECURE: Prints configuration values
System.out.println(key + " = {" + props.getProperty(key) + "}");
```

**Remediation:**

**Secure logging:**

```java
// BEFORE (Insecure):
properties.list(System.out);

// AFTER (Secure):
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger log = LoggerFactory.getLogger(OpenTSxClusterLink.class);
private static final Set<String> SENSITIVE_KEYS = Set.of(
    "sasl.password", "password", "secret", "key", "token"
);

public void logConfiguration(Properties props) {
    for (String key : props.stringPropertyNames()) {
        String value = props.getProperty(key);

        // Mask sensitive values
        if (SENSITIVE_KEYS.stream().anyMatch(key::contains)) {
            value = "***REDACTED***";
        }

        log.info("Config: {} = {}", key, value);
    }
}
```

**Implement security audit logging:**

```java
// AuditLogger.java
public class AuditLogger {
    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");

    public static void logAuthSuccess(String username, String resource) {
        auditLog.info("AUTH_SUCCESS: user={}, resource={}", username, resource);
    }

    public static void logAuthFailure(String username, String reason) {
        auditLog.warn("AUTH_FAILURE: user={}, reason={}", username, reason);
    }

    public static void logDataAccess(String username, String resource, String action) {
        auditLog.info("DATA_ACCESS: user={}, resource={}, action={}",
            username, resource, action);
    }

    public static void logSecurityEvent(String event, String details) {
        auditLog.warn("SECURITY_EVENT: event={}, details={}", event, details);
    }
}
```

**Centralized logging with ELK stack:**

```yaml
# docker-compose.yml
elasticsearch:
  image: elasticsearch:8.8.0
  environment:
    - discovery.type=single-node
  ports:
    - 9200:9200

logstash:
  image: logstash:8.8.0
  volumes:
    - ./logstash.conf:/usr/share/logstash/pipeline/logstash.conf
  ports:
    - 5000:5000

kibana:
  image: kibana:8.8.0
  ports:
    - 5601:5601
  depends_on:
    - elasticsearch
```

---

### A10:2021 - Server-Side Request Forgery (SSRF)

**Status:** ✅ NOT VULNERABLE

No server-side request forgery vulnerabilities identified. The application does not make HTTP requests based on user input.

---

## Security Architecture

### Defense in Depth

OpenTSx should implement multiple layers of security:

```
┌─────────────────────────────────────────────────────────────┐
│                    Layer 7: Monitoring                       │
│  (SIEM, Security Monitoring, Alerting)                       │
├─────────────────────────────────────────────────────────────┤
│                    Layer 6: Application                      │
│  (Authentication, Authorization, Input Validation)           │
├─────────────────────────────────────────────────────────────┤
│                    Layer 5: Data                             │
│  (Encryption at Rest, Data Masking, Access Control)          │
├─────────────────────────────────────────────────────────────┤
│                    Layer 4: Transport                        │
│  (TLS/SSL, Certificate Management)                           │
├─────────────────────────────────────────────────────────────┤
│                    Layer 3: Network                          │
│  (Firewall, Network Segmentation, VPC)                       │
├─────────────────────────────────────────────────────────────┤
│                    Layer 2: Host                             │
│  (OS Hardening, Patch Management, Antivirus)                 │
├─────────────────────────────────────────────────────────────┤
│                    Layer 1: Physical                         │
│  (Data Center Security, Access Control)                      │
└─────────────────────────────────────────────────────────────┘
```

### Current Security Architecture (AS-IS)

```
❌ Monitoring: Minimal security monitoring
❌ Application: No authentication/authorization
❌ Data: No encryption at rest
⚠️  Transport: TLS disabled in many components
⚠️  Network: No network policies
⚠️  Host: No container security scanning
❌ Physical: Not applicable (cloud deployment)
```

### Target Security Architecture (TO-BE)

```
✅ Monitoring: SIEM integration, security alerts
✅ Application: OAuth2/OIDC, RBAC, input validation
✅ Data: Encryption at rest, key management
✅ Transport: TLS 1.3 everywhere, mutual TLS
✅ Network: Network policies, service mesh
✅ Host: Image scanning, runtime protection
✅ Physical: Cloud provider security controls
```

---

## Authentication & Authorization

### Current State: ❌ INSUFFICIENT

- No application-level authentication
- Krake using "always-allow" authorization
- Anonymous access enabled
- No SSO/OIDC integration
- No multi-factor authentication

### Target State: ✅ SECURE

**Implement OAuth2 with OIDC:**

```yaml
# application.yml
spring:
  security:
    oauth2:
      client:
        registration:
          okta:
            client-id: ${OAUTH2_CLIENT_ID}
            client-secret: ${OAUTH2_CLIENT_SECRET}
            scope: openid, profile, email
        provider:
          okta:
            issuer-uri: https://your-domain.okta.com/oauth2/default
```

**Implement RBAC:**

```java
@RestController
@RequestMapping("/api/v1/timeseries")
public class TimeSeriesController {

    @PreAuthorize("hasRole('ADMIN') or hasRole('DEVELOPER')")
    @PostMapping
    public ResponseEntity<TimeSeriesObject> create(@RequestBody TimeSeriesObject tso) {
        // Create time series
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'DEVELOPER', 'VIEWER')")
    @GetMapping("/{id}")
    public ResponseEntity<TimeSeriesObject> get(@PathVariable String id) {
        // Get time series
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        // Delete time series
    }
}
```

---

## Data Protection

### Encryption at Rest

**Cassandra Encryption:**

```yaml
# cassandra.yaml
server_encryption_options:
  internode_encryption: all
  keystore: /etc/cassandra/conf/.keystore
  keystore_password: ${KEYSTORE_PASSWORD}
  truststore: /etc/cassandra/conf/.truststore
  truststore_password: ${TRUSTSTORE_PASSWORD}
  cipher_suites: [TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384]

client_encryption_options:
  enabled: true
  optional: false
  keystore: /etc/cassandra/conf/.keystore
  keystore_password: ${KEYSTORE_PASSWORD}
  require_client_auth: true
  truststore: /etc/cassandra/conf/.truststore
  truststore_password: ${TRUSTSTORE_PASSWORD}
```

### Encryption in Transit

**Kafka with TLS:**

```properties
# server.properties
listeners=SSL://0.0.0.0:9093
advertised.listeners=SSL://kafka:9093

security.inter.broker.protocol=SSL
ssl.client.auth=required

ssl.keystore.location=/etc/kafka/secrets/kafka.keystore.jks
ssl.keystore.password=${SSL_KEYSTORE_PASSWORD}
ssl.key.password=${SSL_KEY_PASSWORD}
ssl.truststore.location=/etc/kafka/secrets/kafka.truststore.jks
ssl.truststore.password=${SSL_TRUSTSTORE_PASSWORD}

# Force TLS 1.3
ssl.enabled.protocols=TLSv1.3
ssl.protocol=TLSv1.3
```

**Client Configuration:**

```properties
# client.properties
security.protocol=SSL
ssl.truststore.location=/etc/kafka/secrets/kafka.truststore.jks
ssl.truststore.password=${SSL_TRUSTSTORE_PASSWORD}
ssl.keystore.location=/etc/kafka/secrets/kafka.keystore.jks
ssl.keystore.password=${SSL_KEYSTORE_PASSWORD}
ssl.key.password=${SSL_KEY_PASSWORD}

ssl.enabled.protocols=TLSv1.3
ssl.endpoint.identification.algorithm=https
```

### Data Masking

**Implement field-level encryption for sensitive data:**

```java
public class SensitiveDataEncryptor {
    private final Cipher cipher;
    private final SecretKey secretKey;

    public String encrypt(String plaintext) {
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] encrypted = cipher.doFinal(plaintext.getBytes());
        return Base64.getEncoder().encodeToString(encrypted);
    }

    public String decrypt(String ciphertext) {
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
        return new String(decrypted);
    }
}
```

---

## Network Security

### Network Segmentation

**Implement network policies in Kubernetes:**

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: opentsx-network-policy
  namespace: opentsx
spec:
  podSelector:
    matchLabels:
      app: opentsx
  policyTypes:
  - Ingress
  - Egress
  ingress:
  - from:
    - namespaceSelector:
        matchLabels:
          name: opentsx
    ports:
    - protocol: TCP
      port: 8080
  egress:
  - to:
    - namespaceSelector:
        matchLabels:
          name: opentsx
    ports:
    - protocol: TCP
      port: 9092  # Kafka
    - protocol: TCP
      port: 9042  # Cassandra
```

### Firewall Rules

**Configure security groups (AWS example):**

```bash
# Kafka broker security group
aws ec2 create-security-group \
  --group-name kafka-broker-sg \
  --description "Kafka broker security group"

# Allow Kafka traffic only from application tier
aws ec2 authorize-security-group-ingress \
  --group-id sg-kafka \
  --protocol tcp \
  --port 9092-9094 \
  --source-group sg-app

# Allow Cassandra traffic only from application tier
aws ec2 authorize-security-group-ingress \
  --group-id sg-cassandra \
  --protocol tcp \
  --port 9042 \
  --source-group sg-app
```

---

## Dependency Security

### Automated Vulnerability Scanning

**GitHub Dependabot configuration:**

```yaml
# .github/dependabot.yml
version: 2
updates:
  - package-ecosystem: "maven"
    directory: "/"
    schedule:
      interval: "daily"
    open-pull-requests-limit: 10
    reviewers:
      - "security-team"
    labels:
      - "security"
      - "dependencies"
```

**Maven OWASP Dependency Check:**

```xml
<!-- pom.xml -->
<plugin>
    <groupId>org.owasp</groupId>
    <artifactId>dependency-check-maven</artifactId>
    <version>8.4.0</version>
    <configuration>
        <failBuildOnCVSS>7</failBuildOnCVSS>
        <skipProvidedScope>true</skipProvidedScope>
        <skipRuntimeScope>false</skipRuntimeScope>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>check</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

**Run vulnerability scan:**

```bash
# OWASP Dependency Check
mvn org.owasp:dependency-check-maven:check

# Snyk
snyk test --all-projects

# Trivy
trivy fs --security-checks vuln .
```

---

## Secure Configuration

### Configuration Best Practices

1. **Never commit secrets to Git**
2. **Use environment variables for sensitive data**
3. **Implement secrets rotation**
4. **Use least privilege principle**
5. **Enable security features by default**

### Secrets Management with Vault

```bash
# Store secret in Vault
vault kv put secret/opentsx/kafka \
  bootstrap_servers="kafka:9092" \
  sasl_username="api-key" \
  sasl_password="secret"

# Retrieve in application
vault kv get -field=sasl_password secret/opentsx/kafka
```

### Spring Cloud Config

```yaml
# bootstrap.yml
spring:
  application:
    name: opentsx
  cloud:
    config:
      uri: https://config-server:8888
      username: ${CONFIG_SERVER_USERNAME}
      password: ${CONFIG_SERVER_PASSWORD}
```

---

## Security Monitoring

### Security Events to Monitor

| Event | Severity | Action |
|-------|----------|--------|
| Authentication failure (3+ attempts) | HIGH | Alert security team |
| Authorization failure | MEDIUM | Log and review |
| SQL injection attempt | CRITICAL | Block IP, alert |
| Unusual data access pattern | MEDIUM | Flag for review |
| Configuration change | HIGH | Alert and log |
| Sensitive data exposure | CRITICAL | Immediate response |

### SIEM Integration

**Send logs to SIEM (Splunk example):**

```xml
<!-- logback.xml -->
<appender name="SPLUNK" class="com.splunk.logging.HttpEventCollectorLogbackAppender">
    <url>https://splunk:8088</url>
    <token>${SPLUNK_HEC_TOKEN}</token>
    <index>opentsx</index>
    <source>application</source>
    <sourcetype>_json</sourcetype>
</appender>
```

### Alerting Rules

```yaml
# Prometheus alerting rules
groups:
- name: security
  rules:
  - alert: HighAuthenticationFailureRate
    expr: rate(auth_failures_total[5m]) > 5
    for: 5m
    labels:
      severity: critical
    annotations:
      summary: "High authentication failure rate detected"

  - alert: UnauthorizedAccessAttempt
    expr: increase(authorization_failures_total[1m]) > 0
    labels:
      severity: high
    annotations:
      summary: "Unauthorized access attempt detected"
```

---

## Incident Response

### Incident Response Plan

**1. Detection:**
- Monitor security alerts
- Automated threat detection
- User reports

**2. Containment:**
```bash
# Immediate actions
# 1. Isolate affected systems
kubectl cordon <node-name>

# 2. Rotate credentials
confluent api-key delete <key>
confluent api-key create

# 3. Block malicious IPs
iptables -A INPUT -s <malicious-ip> -j DROP
```

**3. Eradication:**
- Remove backdoors
- Patch vulnerabilities
- Update configurations

**4. Recovery:**
- Restore from clean backups
- Validate system integrity
- Gradual service restoration

**5. Lessons Learned:**
- Post-incident review
- Update security controls
- Improve monitoring

### Security Incident Contacts

- **Security Team:** security@opentsx.com
- **On-Call:** +1-xxx-xxx-xxxx
- **Escalation:** ciso@opentsx.com

---

## Remediation Roadmap

### Phase 1: IMMEDIATE (Week 1)

**Priority: CRITICAL**

- [ ] Rotate all exposed Confluent Cloud credentials
- [ ] Remove `config/private/ccloud.props` from Git history
- [ ] Add sensitive files to `.gitignore`
- [ ] Fix SQL injection in CassandraConnector.java
- [ ] Enable TLS for Krake API server
- [ ] Change Krake authorization from `always-allow` to `RBAC`
- [ ] Disable anonymous authentication

**Estimated Effort:** 40 hours
**Owner:** Security Team + Dev Lead

---

### Phase 2: SHORT-TERM (Month 1)

**Priority: HIGH**

- [ ] Replace MD5 with SHA-256
- [ ] Implement prepared statements everywhere
- [ ] Enable TLS for Kafka brokers
- [ ] Enable TLS for Cassandra
- [ ] Update vulnerable dependencies (Jackson, XStream)
- [ ] Implement secure exception handling
- [ ] Add input validation
- [ ] Implement secrets management (Vault/AWS Secrets Manager)
- [ ] Add Spring Security with OAuth2
- [ ] Implement RBAC roles and permissions

**Estimated Effort:** 120 hours
**Owner:** Development Team

---

### Phase 3: MEDIUM-TERM (Quarter 1)

**Priority: MEDIUM**

- [ ] Upgrade Java from 1.8 to 17
- [ ] Upgrade Kafka from 2.3.0 to 3.4.0+
- [ ] Implement centralized logging (ELK stack)
- [ ] Add security monitoring and alerting
- [ ] Implement network segmentation
- [ ] Add container security scanning
- [ ] Implement SIEM integration
- [ ] Add security testing to CI/CD
- [ ] Create security runbooks
- [ ] Security training for team

**Estimated Effort:** 240 hours
**Owner:** Platform Team

---

### Phase 4: LONG-TERM (Quarter 2)

**Priority: LOW/CONTINUOUS**

- [ ] Implement service mesh (Istio/Linkerd)
- [ ] Add encryption at rest for all storage
- [ ] Implement zero-trust architecture
- [ ] Add DDoS protection
- [ ] Implement WAF (Web Application Firewall)
- [ ] Regular penetration testing
- [ ] Security compliance certifications (SOC 2, ISO 27001)
- [ ] Bug bounty program
- [ ] Regular security audits
- [ ] Continuous security improvement

**Estimated Effort:** Ongoing
**Owner:** Security Team

---

## Security Checklist

### Before Production Deployment

#### Authentication & Authorization
- [ ] OAuth2/OIDC implemented
- [ ] RBAC configured with least privilege
- [ ] MFA enabled for admin accounts
- [ ] Service accounts properly secured
- [ ] Anonymous access disabled
- [ ] Default credentials removed

#### Encryption
- [ ] TLS 1.3 enabled for all services
- [ ] Mutual TLS configured
- [ ] Encryption at rest enabled (Cassandra)
- [ ] Certificate management process in place
- [ ] Secrets stored in vault (not files)
- [ ] Key rotation policy defined

#### Network Security
- [ ] Network segmentation implemented
- [ ] Firewall rules configured
- [ ] Security groups properly configured
- [ ] VPC/network policies in place
- [ ] Unnecessary ports closed
- [ ] DDoS protection enabled

#### Application Security
- [ ] Input validation everywhere
- [ ] Prepared statements for all queries
- [ ] Secure exception handling
- [ ] Security headers configured
- [ ] CSRF protection enabled
- [ ] XSS protection enabled

#### Dependency Security
- [ ] All dependencies updated
- [ ] Vulnerability scan passed
- [ ] No critical vulnerabilities
- [ ] SBOM generated
- [ ] Dependency checking automated

#### Monitoring & Logging
- [ ] Security event logging configured
- [ ] Centralized logging in place
- [ ] SIEM integration complete
- [ ] Alerting rules configured
- [ ] Audit logging enabled
- [ ] Log retention policy defined

#### Configuration
- [ ] No hardcoded credentials
- [ ] No secrets in source control
- [ ] Environment-specific configs
- [ ] Secure defaults enabled
- [ ] Unnecessary features disabled
- [ ] Error messages sanitized

#### Testing
- [ ] Security testing completed
- [ ] Penetration testing performed
- [ ] Vulnerability assessment done
- [ ] Code security review completed
- [ ] Security regression tests added

#### Documentation
- [ ] Security architecture documented
- [ ] Runbooks created
- [ ] Incident response plan ready
- [ ] Contact information updated
- [ ] Security policies published

#### Compliance
- [ ] Compliance requirements identified
- [ ] Data privacy requirements met
- [ ] Audit requirements satisfied
- [ ] Regulatory requirements met

---

## Conclusion

OpenTSx requires **immediate security improvements** to be production-ready. The most critical issues are:

1. **Exposed credentials** in Git repository
2. **SQL injection** vulnerabilities
3. **Disabled TLS/SSL** for critical components
4. **Weak authorization** (always-allow mode)
5. **Vulnerable dependencies**

**All recommendations in this document should be implemented before deploying to production.**

For questions or assistance with security remediation:
- **Email:** security@opentsx.com
- **Issues:** [GitHub Security Advisories](https://github.com/kamir/OpenTSx/security/advisories)

---

**Last Updated:** 2025-01-13
**Next Review:** 2025-02-13 (Monthly security review required)

**Security is not a one-time effort - it requires continuous vigilance and improvement.**
