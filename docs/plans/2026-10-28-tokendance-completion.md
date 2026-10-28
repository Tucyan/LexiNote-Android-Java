# TokenDance completion — 2026-10-28

Baseline: independent Java repo at 22a8362. Expo is a read-only reference. Latest user request continues the seven-item gap audit.

- [x] Built-in 34-model reference catalog, exact unsupported model rules, explicit custom model metadata and offline picker.
- [x] Native account card with authorization, account/keys, usage and help; browser/WebView authorization with PKCE, strict callback and one-time-code fallback.
- [x] Device-encrypted pending authorization and atomic activation/key persistence; credentials never in Bundle, logs or backup.
- [x] Payment session creation and guarded user-initiated payment link; lifecycle-aware status checks, expiry/terminal states and cancellation. No real payment is performed by the agent.
- [x] Gateway-only recovery headers integrated into query errors and connection probes with recharge/reauthorize routes.
- [x] Password visibility, default TokenDance controls and collapsed advanced providers preserved.
- [x] Offline JVM tests, device instrumentation, build/lint, actual screenshots, review and APK delivery.

Workers: gpt-6.1-terra/high. Catalog and protocol worker files staged separately; root owns integration/UI/security/verification. Native sibling is outside sandbox write roots; all integration/build writes require approved elevated exec. Existing changes are clean; previous checkpoint 22a8362.

Scope boundary: implement the original app flow against the original service API contract. Live login, third-party callback availability and actual payment settlement require the user's account; report those as unverified unless explicitly exercised. No temporary model API credentials are used.

Verification completed:117 JVM tests and22 emulator groups. Reviews corrected missing custom flags, omitted payment URLs, interrupted creation state, expiry-versus-settlement handling and external navigation. Actual service login/payment not performed; documented as unverified. Final APK debug signature verified.
