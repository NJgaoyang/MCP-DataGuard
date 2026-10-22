# MCP DataGuard login theme for Keycloak 26.4

An isolated Keycloak login theme derived from `keycloak.v2`. It styles **only**
the sign-in UI: background, brand, title, input fields, buttons and responsive
layout. The parent theme still owns all actual authentication, OIDC code/PKCE,
MFA, password-reset flow and error-handling templates.

Reference design: `dataguard-web/src/App.vue` (Vue admin console).
Theme directory: `deploy/keycloak/themes/dataguard/login`.

Compose volume on the test identity service:

```yaml
volumes:
  - ./keycloak/themes/dataguard:/opt/keycloak/themes/dataguard:ro
```

After recreating the Keycloak container, select `loginTheme=dataguard` for
Realm `dataguard`, keep `internationalizationEnabled=true`,
`supportedLocales=["zh-CN"]`, and `defaultLocale=zh-CN`.
Set Realm `displayNameHtml` to the literal `MCP DataGuard` for the same brand
shown in the Vue admin console. No password storage or client settings change.

Only supported for the Keycloak 26.4.x login markup, which currently uses
PatternFly v5 classes; re-verify appearance after upgrading Keycloak.
