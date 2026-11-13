# T3 synthetic backup compatibility fixtures

These two encrypted archives contain synthetic test cards only. They were exported
by the unmodified production sources at commit
`9c6ef63157026106b7cd45f29e2d4a18f9934354` (T3), in an isolated checkout during
the 2026-10-19 investigation. They are not user backups and contain no real card data.

- Test password: `synthetic-诊断-2026` (public test data, not a user credential).
- Manifest version: 2; archive header: `MYCARD02`.
- Three template cards: template IDs 0, 5, and 15; no imported artwork.
- Two consecutive exports of the same synthetic wallet, with independent salt/nonces.
- `TemplateBackupCompatibilityTest` loads these resources directly; no historical
  checkout, fixture generator, device, or existing build directory is required.

Keep these historical bytes unchanged. Generating replacements with current code
would remove the independent historical compatibility check.

| File | SHA-256 |
| --- | --- |
| template-1.mycard | 911F400E4550F99933263D9E61295FAB2896C7A451F8B1129EA9E5844FBBE029 |
| template-2.mycard | 393FBF03C0D834497A83E022FB143E2469EAD64C5B4AFB7AE01EAB3CD42B1901 |
