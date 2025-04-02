# Keycloak 2FA REST API Extension

khode-2fa is a Keycloak extension that provides a REST API for managing Time-based One-Time Password (TOTP) authentication. This extension allows you to set up, verify, enable, disable, and validate TOTP for users in a Keycloak realm.

## Features

- Check if TOTP is configured for a user
- Setup TOTP for a user
- Verify and enable TOTP
- Get TOTP status for a user
- Validate TOTP code
- Disable TOTP for a user
- Flexible authentication support:
  - Client credentials (service account) authentication
  - User token authentication
  - Per-endpoint authorization controls

This extension is designed to integrate seamlessly with existing Keycloak deployments, offering developers and administrators greater flexibility in implementing and managing 2FA.

## Installation

You can either build the JAR file from source or download a pre-built version from the releases page.

### Option 1: Download from Releases

1. Go to the [Releases](https://github.com/chornthorn/khode-two-factor-auth/releases) page
2. Download the latest `khode-two-factor-auth-x.x.x.jar` file (e.g. `khode-two-factor-auth-1.4.0.jar`)
3. Copy the JAR file to your Keycloak deployments directory and set proper permissions:

   ```bash
   # Copy the file
   cp khode-two-factor-auth-1.4.0.jar /path/to/keycloak/providers/
   
   # Set proper ownership (assuming keycloak is the user/group)
   sudo chown keycloak:keycloak /path/to/keycloak/providers/khode-two-factor-auth-*.jar
   
   # Set proper permissions
   sudo chmod 644 /path/to/keycloak/providers/khode-two-factor-auth-*.jar
   ```

### Option 2: Build from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/chornthorn/khode-two-factor-auth.git
   ```

2. Change to the project directory:
   ```bash
   cd khode-two-factor-auth
   ```

3. Build the project using Maven:
   ```bash
   mvn clean package
   ```

4. Copy the resulting JAR file to the Keycloak deployments directory and set proper permissions:
   ```bash
   # Copy the file
   cp target/khode-two-factor-auth-*.jar /path/to/keycloak/providers/
   
   # Set proper ownership (assuming keycloak is the user/group)
   sudo chown keycloak:keycloak /path/to/keycloak/providers/khode-two-factor-auth-*.jar
   
   # Set proper permissions (readable by owner and group)
   sudo chmod 644 /path/to/keycloak/providers/khode-two-factor-auth-*.jar
   ```

### Option 3: Using Docker

1. Clone the repository:
   ```bash
   git clone https://github.com/chornthorn/khode-two-factor-auth.git
   ```

2. Build the Docker image:
    ```bash
    cd khode-two-factor-auth
    docker build -t khode-keycloak:latest .
    ```

    #### For MacOS with Apple Silicon (M1/M2) or other ARM-based systems, specify the platform:
    ```bash
    cd khode-two-factor-auth
    docker build --platform linux/amd64 -t khode-keycloak:latest .
    ```

3. Run the container:
   ```bash
   docker run -p 8080:8080 -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin khode-keycloak:latest start-dev
   ```

4. Access Keycloak at http://localhost:8080

The Docker image is based on the official Keycloak image and includes the 2FA extension pre-installed.

After installing using either method, restart Keycloak to load the new extension.

## Usage

This extension provides the following REST endpoints for managing TOTP authentication.

**Note:** Requirement for before using it:
   - Simple URL: `http://keycloak-server:[port]/realms/{realm}/khode-2fa`
   - Replace `{realm}` and `{user_id}` with the appropriate values.

**Authentication Requirements:**
- The API supports two authentication methods:
   1. Client Credentials (Service Account):
      - Requires a bearer token from a service account
      - Can access any user's TOTP settings
   2. User Token:
      - Requires a bearer token from a regular user
      - Can only access their own TOTP settings
- All requests must include an `Authorization: Bearer <token>` header

### Authentication Examples

**Using Client Credentials:**
```bash
# Get service account token
TOKEN=$(curl -X POST \
  "http://keycloak-server/realms/master/protocol/openid-connect/token" \
  -d "grant_type=client_credentials" \
  -d "client_id=your-client" \
  -d "client_secret=your-secret" \
  | jq -r '.access_token')
```

**Using User Token:**
```bash
# Get user token
TOKEN=$(curl -X POST \
  "http://keycloak-server/realms/master/protocol/openid-connect/token" \
  -d "grant_type=password" \
  -d "client_id=your-client" \
  -d "username=your-username" \
  -d "password=your-password" \
  | jq -r '.access_token')
```

### Setup TOTP

```http
POST /realms/{realm}/khode-2fa/totp/setup/{user_id}
```

Returns:

```json
{
  "secret": "BASE32_ENCODED_SECRET",
  "qrCode": "otpauth://totp/...",
  "policy": {
    "algorithm": "HmacSHA1",
    "digits": 6,
    "period": 30,
    "type": "totp"
  },
  "supportedApplications": [
    "FreeOTP",
    "Google Authenticator"
  ],
  "userId": "user-123",
  "code": 0
}
```

Generates a TOTP secret for the user and returns the secret and QR code.

### Verify and Enable TOTP

```http
POST /realms/{realm}/khode-2fa/totp/verify/{user_id}
Content-Type: application/json

{
    "code": "123456"
}
```

Returns:

```json
{
  "message": "TOTP enabled successfully",
  "enabled": true,
  "code": 0
}
```

Verifies the TOTP code and enables TOTP for the user.

### Get TOTP Status

```http
GET /realms/{realm}/khode-2fa/totp/status/{user_id}
```

Returns:

```json
{
  "enabled": true,
  "message": "TOTP is configured for this user",
  "userId": "user-123",
  "code": 0
}
```

Returns the TOTP status and credentials for the user.

### Validate TOTP Code

```http
POST /realms/{realm}/khode-2fa/totp/validate/{user_id}
Content-Type: application/json

{
    "code": "123456"
}
```

Returns:

```json
{
  "message": "TOTP code validated successfully",
  "valid": true,
  "userId": "user-123",
  "code": 0
}
```

Validates the TOTP code for an existing TOTP setup.

### Disable TOTP

```http
DELETE /realms/{realm}/khode-2fa/totp/{user_id}
```

Returns:

```json
{
  "message": "TOTP disabled successfully",
  "enabled": false,
  "userId": "user-123",
  "code": 0
}
```

Disables TOTP for the user.

### Disable TOTP with Validation (Disable with single endpoint request)

```http
POST /realms/{realm}/khode-2fa/totp/disable-with-validation/{user_id}
Content-Type: application/json

{
    "code": "123456"
}
```

Returns:

```json
{
  "message": "TOTP validated and disabled successfully",
  "enabled": false,
  "userId": "user-123",
  "code": 0
}
```

Validates the TOTP code before disabling TOTP for the user. This provides an additional security layer when disabling 2FA.

## Error Handling

The extension provides appropriate error responses for various scenarios, such as invalid codes, missing TOTP setup,
etc.

### API Response Codes

All API endpoints return a standardized `code` field in their responses. Here's what each code means:

| Code | Description | Common Scenarios |
|------|-------------|------------------|
| 0 | Success | Operation completed successfully |
| 1 | Invalid User ID | Missing or malformed user ID |
| 2 | Invalid Code Format | TOTP code is missing or malformed |
| 3 | TOTP Not Enabled | Attempting operations on non-enabled TOTP |
| 4 | TOTP Already Enabled | Attempting to enable already configured TOTP |
| 5 | Server Error | Unexpected server-side errors |
| 6 | TOTP Setup Required | Trying to verify without setup |
| 7 | Invalid TOTP Code | Incorrect TOTP code provided |
| 8 | Operation Failed | Failed to complete the requested operation |
| 9 | Unauthorized | Missing or invalid authentication |
| 10 | Forbidden | Insufficient permissions or access denied |

## Compatibility Matrix

This table shows the compatibility between Keycloak versions and khode-2fa extension versions.

| Keycloak Version | khode-2fa Version | Status      | Testing Status |
|-----------------|-------------------|-------------|----------------|
| 26.0.x          | 1.4.x            | ✅ Supported | ✅ Tested       |
| 25.0.x          | 1.4.x            | ✅ Supported | ✅ Tested       |
| 24.0.x          | 1.4.x            | ⚠️ Limited   | 🧪 In Testing   |
| 23.0.x          | 1.4.x            | ⚠️ Limited   | ❌ Untested     |
| 22.0.x          | 1.4.x            | ⚠️ Limited   | ❌ Untested     |
| < 22.0.0        | < 1.4.x          | ❌ Unsupported| ❌ Untested     |

### Notes

- **Status**
    - ✅ Supported: Fully tested and supported version combinations
    - ⚠️ Limited: Should work but not officially tested
    - ❌ Unsupported: Not tested or known to have compatibility issues

- **Testing Status**
    - ✅ Tested: Fully tested with automated and manual testing
    - 🧪 In Testing: Currently undergoing testing
    - ❌ Untested: No testing performed

### Recommendations

- For production use, we recommend using Keycloak 25.0.x or 26.0.x with khode-2fa 1.4.x
- Other version combinations may work but are not officially tested

## Dependencies

- Keycloak
- Jakarta RESTful Web Services (JAX-RS)
- Microprofile OpenAPI
- Lombok

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
