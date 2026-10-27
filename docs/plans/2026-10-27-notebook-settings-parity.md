# Notebook and service configuration parity

**Goal:** Improve the native Java notebook and provider configuration experience against the same-device Expo reference while retaining the native version's working capabilities and credential safeguards.

**Baseline:** The original checkout is read-only at d465b969. Native baseline comparison is 5c6eb5c; existing screenshot report was saved separately as 5e67de2. Use the existing independent native repository/branch.

**Notebook acceptance:** Default/global category order, title/actions, type and tag filters, compact first-meaning summary, icon styling and spacing should be closer to Expo. Batch operations, tag selection, reference sharing, editor, annotations, settings-based display and search remain usable. Verify the same two-meaning fixture visually and exercise selection and editing.

**Service configuration acceptance:** Provider presets, model selection, vision state, API key actions, connection probes, reasoning and manual fields should follow the original hierarchy more closely. Maintain native dynamic model discovery, custom providers, selected model metadata, per-provider encrypted keys, draft behavior, cancellation and verified offline/real-service functions. TokenDance OAuth/account/recharge must be honest about remaining scope rather than represented by inactive controls. Verify unconfigured and expanded screens plus selecting another provider, editing/testing/saving a draft, rotation/nonsecret state and cancellation.

**Tasks:**

- [x] Notebook agent implements and self-checks its owned UI files.
- [x] Settings agent implements and self-checks its owned UI files.
- [x] Read-only specification and regression review.
- [x] Run `testDebugUnitTest lintDebug assembleDebug` and emulator feature, smoke, and model configuration checks.
- [x] Capture before/after on the same Pixel 9a and fixture; inspect notebook, service overview and expanded provider form; document remaining differences.
- [x] Check original checkout unchanged and restore emulator data and credentials. Commit after validation. No network model requests were made for the visual comparison.

All delegated agents use gpt-6.1-terra/high per current user instruction. Avoid overriding the pre-existing source baseline report. Long commands/downloads/permission failures: maximum three attempts unless a known cause is fixed; use cached/Aliyun dependencies.
