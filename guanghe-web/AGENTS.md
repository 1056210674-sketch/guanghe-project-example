# Frontend collaboration rules

- Prefer the existing components, application shell, and theme tokens.
- Generate API types from the Java Browser OpenAPI; do not duplicate generated business DTOs by hand.
- Never import Mock data or test fixtures into production code.
- The Web application may call only Java `/api/v1/**` browser endpoints; never call Python or `/internal/v1/**`.
- Page containers own data loading and mutations; presentation components remain Props-driven.
