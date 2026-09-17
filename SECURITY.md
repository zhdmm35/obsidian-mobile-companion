# Security policy

## Supported versions

Only the latest release and the current `main` branch receive security fixes while this project is pre-1.0.

## Reporting a vulnerability

Please do not open a public issue for a security vulnerability. Use GitHub's private vulnerability reporting for this repository, or contact the maintainer through the email address shown on the maintainer's public GitHub profile. Include a concise description, affected version or commit, reproduction steps using synthetic data, and the potential impact. Do not send access tokens, private vault content, or private repository URLs.

The maintainer will acknowledge a report, reproduce it with synthetic fixtures, publish a fix or mitigation, and credit the reporter only with permission.

## Security boundaries

- GitHub tokens are stored through Android Keystore-backed encryption and must never be logged.
- `vaultsync` runs local Git commands and can push automatically; users must review its configured path and remote.
- Codex-assisted reviews use public repository metadata and synthetic fixtures only. Private vaults, tokens, and personal logs are excluded from the maintenance workflow.
