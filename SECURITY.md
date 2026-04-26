# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| Latest  | ✅        |

## Reporting a Vulnerability

If you discover a security vulnerability in Echo Mock Server, please report it responsibly.

**Do NOT open a public GitHub issue for security vulnerabilities.**

Instead, please email: **[security@example.com](mailto:security@example.com)**

> ⚠️ Replace the email above with your actual security contact before publishing.

### What to include

- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Suggested fix (if any)

### Response timeline

- **Acknowledgment**: within 48 hours
- **Initial assessment**: within 7 days
- **Fix release**: depends on severity, typically within 30 days

## Security Best Practices for Deployment

When deploying Echo Mock Server in production:

1. **Change default credentials** — Replace the default `admin/admin` password via `ECHO_ADMIN_PASSWORD` environment variable
2. **Set a unique Remember Me key** — Override `ECHO_REMEMBER_ME_KEY` with a random secret
3. **Disable H2 Console** — Set `spring.h2.console.enabled=false` in production
4. **Use HTTPS** — Deploy behind a reverse proxy (e.g., Nginx) with TLS termination
5. **Restrict network access** — Echo is designed for internal/testing environments; do not expose to the public internet without proper access controls
