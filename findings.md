# Findings

- Native baseline has 44 JVM tests and 10 device smoke checks. JSON Library/AtomicFile repository preserves source fields; query actions require confirmed target and source revision.
- MainActivity currently owns a global image URI and destructive crop dialog; camera only reads thumbnail intent extra. Integration must separate original and effective selection URI, and handle camera success with null Intent.
- MainActivity.request shows a blocking progress dialog; streaming assistant requires a cancellable nonmodal request path with generation guards.
- QueryUi.finishResult is the central success gate; auto-save belongs here with an idempotent result marker and rollback on persist failure, never in message or partial-response callbacks.
- Query result display fields are presentation only. Library.saveResult merges complete meanings; changes must retain its entity/reference contract.
- Original schema: Annotation target_field/target_text/content; QueryHistory input_images/selected_regions; field definitions and source examples are in sibling src/types/index.ts.
- Existing assistant is a ScrollView with every message inflated; replace it with a native recycled list outside ScrollView. Original replies are structured JSON, so partial streams must not execute proposals.

- Image state now has a distinct ink layer and transforms normalized geometry on rotation. Original files are byte-preserving copies; derived network/editor output is bounded at1600px. `native_selected_images` is an additional portable array recognized by ZIP codec; state JSON is URI-free.
- `Library.saveResult` now maps draft selections onto actual merged meaning IDs and retains complete entity content. A user-organized save may update only the explicitly selected notebook display; batch copying into an already-existing reference must preserve that target's display.
- Assistant stream `delta.content` contains the model's action JSON, not an HTTP envelope. The transport returns that content directly for final validation, previews only top-level message strings, and rejects truncated/filtered completion. Background titles use independent client/executor + manual revision/deletion/restore epoch guards.
- Native device tests surfaced two integration defects now repaired: Boolean source_images display flags are excluded from backup image traversal, and assistant attachment removal persists all cleared source metadata before reopening.
- AGP only registers the configured test runner; deviceRunner Gradle property selects dedicated FeatureInstrumentation without altering production manifest. Quote dotted -P arguments in PowerShell.
- Final acceptance:80 JVM,7 selected feature device groups,10 smoke groups,compile/lint/APK pass. See docs/verification/FEATURES.md for behavior, evidence and retained scope limits.
