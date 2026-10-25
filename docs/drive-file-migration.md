# Google Drive permission migration

Prepared on 25 October 2026 for the Google OAuth verification emails of 3 August
and 22 October 2026. This document contains the implementation plan, regression
checks, and the remaining Google Cloud steps. A local commit does not update Cloud
Console, publish an Android release, or complete Google's review.

## Plan and implementation

1. Replace full Drive access with
   `https://www.googleapis.com/auth/drive.file` in every sign-in and API-credential
   path, including member edits, owner actions, role elevation, and Pro activation.
2. Use Google's native Android Picker to authorize existing files and destination
   folders. Keep Google Sign-In and the existing Drive client. Pin
   `play-services-auth` to 21.6.0: it supports folder selection; 22.0.0 removes the
   Google Sign-In APIs used by this app. No hosted picker page or new backend is
   introduced.
3. Check the saved config file, validate the user's roster entry, then check the
   referenced database before loading it. Storage owners also authorize their
   storage folder and any logo needed for owner operations. Request missing file
   grants only, and verify actual Drive access after selection.
4. Keep existing invitations and their encrypted payload unchanged. Joining can
   now ask the recipient to select the config and database. Add explicit restore
   selection because a search under `drive.file` only discovers authorized files.
   Keep folder-link inputs; ask the user to confirm the linked destination in
   Google's Picker during setup and relocation.
5. Preserve the saved connection and local rows on HTTP 404: it can mean the app
   has no file grant. Scanning remains locked until the roster and database have
   been verified. Explicit leave still clears local data. Preserve the
   existing confirmed-gone (HTTP 410) behavior.
6. Authorize the destination's parent before replacing storage during revoke-all.
   Suppress the automatic foreground sync when returning from authorization so
   it cannot interrupt a pending storage operation.
7. Run focused automated checks, complete the live checklist below, then align
   the production Console and send Google's requested confirmation.

The Room schema, Excel/JSON formats, invitation format, QR processing, role rules,
billing product configuration, and sync writes stay as they are. No organization
files are moved or recreated merely to migrate OAuth permissions. The restore
option makes the password screen longer, so that screen now scrolls.

## Affected areas and regression risk

| Area | Risk and expected behavior | Required check |
| --- | --- | --- |
| Google sign-in / account grants | High: new consent and Picker depend on Google Play services and the correct Android OAuth client. | Fresh account, existing grant, cancellation, wrong account. |
| Upgrade with saved organization | High: app authorization can differ from Drive sharing. An inaccessible file must not erase the connection. | Upgrade in place, cancel/retry, confirm roster and member rows are preserved. |
| New organization | High: a pasted link no longer grants API access by itself. | Select the linked folder; confirm file creation and reopen. |
| Restore / joining another account | High: folder selection must not be assumed to authorize its children. | Restore config explicitly, authorize database, join as each supported role. |
| Member edits / import / sync | Medium: all uploads now use narrower credentials. | Edit and import a small file; verify the changed rows from a second device. |
| Owner branding / settings | Medium: saving settings and logos uses authorized config, database, and folder IDs. | Save one setting and replace a logo, then reopen. |
| Relocation / revoke-all | High: creates replacement IDs; other accounts may need to select the new files after receiving a current invitation. | Use a disposable organization; verify old access is denied and the owner's new connection works. |
| Pro activation / status | Medium: purchase handling is unchanged, but writing its result to config uses the new scope. | One Play license-test purchase or existing test-purchase flow, then sync status on the other device. |
| QR camera, member search, local export | Low direct risk: no scope-related code in their core logic. | One existing QR scan and one search after successful sync; no full camera matrix needed solely for this migration. |

The narrow regression set replaces a full unrelated feature retest; it cannot
replace a live consent test. A previous full-Drive grant can hide missing
per-file authorization. Use at least one account with no previous app grant.

## Focused acceptance checklist (pending live device testing)

Use two Google accounts and a disposable organization with a few members. Keep
the same Android signing certificate when testing an in-place upgrade; do not
uninstall the old app for the upgrade case.

- [ ] Fresh consent displays per-file Drive access. Create storage in a chosen
  folder, add a member, restart, and sync without repeated Picker prompts.
- [ ] On a copy of an existing organization, upgrade an installed app. Confirm
  the stored config ID still works or can be explicitly authorized. Cancel file
  selection, retry, and confirm no local rows or connection IDs were erased.
- [ ] Restore an existing config from the explicit restore button. Select a wrong
  file or cancel first; neither action should replace the current organization.
- [ ] Invite account B as Scanner and then Manager using existing invitation
  mechanisms. Authorize the requested files individually if prompted. Confirm a
  wrong invited email is rejected and role restrictions are enforced.
- [ ] Edit a member, toggle status, and import a small sheet as Manager. Sync from
  the other account; check the expected rows. Scan a known QR and search once.
- [ ] Save owner settings, replace a logo, and confirm both persist after restart.
- [ ] Relocate disposable storage. Confirm new invitations work and old links
  cannot load the deleted old database. Authorize new IDs on other accounts.
- [ ] Revoke one user's access, then test revoke-all separately. Confirm the
  removed account cannot scan or sync and the owner can reopen the replacement
  storage. Exercise retry after an interrupted replacement cleanup.
- [ ] Complete one existing license-test purchase flow, and confirm Pro status
  persists in config and reaches the other device. No real purchase is needed.
- [ ] Try offline/retry, Back from Picker, background/foreground, and rotation
  while selecting. After activity/process recreation an interrupted selection
  may need retry; it must not create files or discard the saved connection.
- [ ] If Shared Drives are supported in the deployed app, repeat setup/join and
  owner actions there. Keep the existing account/capability restrictions.

Do not run relocation/revoke-all against a real organization as a smoke test.
Those actions intentionally replace/delete files in the existing app.

## Automated validation

### Follow-up: empty destination folder

The first live setup report showed the linked destination opening as a file
browsing view, asking for an existing file before organization creation. Setup
now uses the pasted folder ID to limit the single-selection folder view. Since
the native Picker opens that folder, users go up one level with its breadcrumb,
select the linked folder itself, and tap Insert. Setup verifies the returned ID
matches the pasted link, then creates organization files on confirmation.
Relocation and owner folder authorization use the same linked-folder selection.
Existing config and database authorization keeps its file-ID filter.

Retest creation in an **empty** folder: tap **Select Folder & Continue**. Confirm
the Picker opens the linked location; use `…` to go up one level, long-press the
linked folder, and tap **Insert**. Then tap **Connect to easyPass**. No existing
config or database should be required. Also cancel once and choose a different
folder once: neither should create files or advance to confirmation. Reopen the
created organization and check that normal file access still works.

Two additional regression tests verify the folder request carries the linked ID
and existing-file authorization retains its exact IDs. All 17 unit tests and
both debug and signed release APK builds pass after this correction. Live
confirmation of the changed Google Picker remains required.

Run from the repository root:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileReleaseKotlin
./gradlew :app:lintDebug
git diff --check
```

The targeted tests cover missing grants, rechecking real access after selection,
cancellation, wrong/partial selections, quota/network errors, parsing Picker
results, and the distinction between HTTP 404 and confirmed-gone HTTP 410. They
do not simulate Google's consent server or prove cross-account Drive grants.

Initial migration results on 25 October 2026 (before the folder-selection correction above):

- `testDebugUnitTest`: 15 tests passed, including 14 new focused regression tests.
- `assembleDebug`: passed; APK at
  `app/build/outputs/apk/debug/app-debug.apk`.
- `compileReleaseKotlin`: passed.
- `git diff --check`: passed; source audit found no full-Drive scope requests.
- `lintDebug`: fails on the existing CameraX `UnsafeOptInUsageError` in
  `KioskScannerScreen.kt:206`. The same error was reproduced from the original
  commit in an isolated `/tmp` checkout. No new lint errors; the SDK pin adds a
  dependency-version warning because 22.0.0 is available, intentionally deferred
  to avoid replacing Google Sign-In in this migration.
- Live OAuth/Picker, cross-account, Play signing, and purchase checks remain
  pending: no phone or emulator was connected. Do not call these checks passed
  based on the successful local build.

## Google Cloud: test environment first

1. Open [Google Cloud Console](https://console.cloud.google.com/) and record the
   production project ID, current Android client IDs, and scope approval states.
   Preserve any **previously approved** scopes as Google's email instructs.
2. Create/select a separate test project. In **APIs & Services > Library**, enable
   **Google Drive API** and **Google Picker API**.
3. In **Google Auth platform > Branding**, configure the app name, support email,
   homepage, privacy URL, authorized domain, and developer contacts. In
   **Audience**, select the audience appropriate to the app; for external testing,
   add accounts A and B as test users.
4. In **Data Access**, add exactly
   `https://www.googleapis.com/auth/drive.file` for Drive. Keep basic sign-in
   identity scopes actually requested by Google Sign-In. Do not add full Drive,
   `drive.readonly`, or `drive.metadata` to make a test pass.
5. In **Clients**, create an **Android** client for package
   `com.easyapps.easypass` and the test build's SHA-1 certificate fingerprint.
   `./gradlew :app:signingReport` identifies build certificates. A package/SHA-1
   pair already registered to another project cannot simply be duplicated: use
   a dedicated test signing certificate configured locally in Android Studio if
   needed. Do not delete production clients to resolve that conflict.
6. This native integration discovers its Android client by package/certificate.
   It needs no web-client secret, API key in Kotlin, or hosted callback page.
   Verify that consent identifies the test project before proceeding. If it
   still identifies production, fix the client/certificate mapping first.
7. Run the checklist with the test client. Test in-place upgrade using an old
   build and new build signed with the same test certificate. Existing test
   files from another Cloud project require explicit authorization; do not
   assume project-to-project app grants carry over.

## Production Console and release sequence

1. After live tests pass, select the project named in Google's verification
   request. Enable Picker there and add `drive.file` under **Data Access**.
2. Confirm its Android OAuth client covers the release certificate. For Google
   Play installs, use **Play Console > App integrity > App signing** to identify
   the app signing SHA-1, which can differ from the upload certificate. Preserve
   working existing clients.
3. Follow the email's distinction: keep previously approved scopes; remove the
   unneeded full-Drive scope from the pending request/Console if it was never
   approved. Installed older app versions still request full Drive, so coordinate
   their upgrade with this configuration change. Removing a Console scope does
   not update their code or revoke earlier user grants. If an approved scope
   must remain temporarily, explain the transition to the reviewer.
4. Update and publish the privacy policy with the disclosures below. Check the
   public homepage links to it and **Branding** uses the same privacy-policy URL.
   Confirm authorized-domain ownership and matching app identity.
5. Use a signed internal/closed Play test release for the live release-client and
   test-purchase checks. A debug build does not verify the Play signing client.
   Choose a new versionCode higher than the current Play release. Stage the
   public rollout only after the focused checks and Console requirements pass.
6. Record the new consent, selected-file authorization, organization creation,
   joining, and sync. Use a clean test account so old broad grants cannot conceal
   the new behavior. Supply the video if requested by the active review.
7. Save/submit the relevant changes in **Verification Center**. Supply reviewer
   installation/navigation instructions and dedicated app-specific test access
   where required. Do not send your personal Google account password.
8. Reply to the original verification email with the wording below, after these
   steps actually pass. Preserve the existing project during review.

`drive.file` avoids restricted-scope review for that Drive scope. It does not
waive branding/domain requirements or resolve the earlier privacy findings by
itself. Historical broad user grants also remain until revoked; test narrower
access with a clean account rather than asking all users to revoke access.

## Privacy-policy content to prepare

Review the hosted policy against these observed app behaviors; do not publish
unverified assurances:

- **Accessed data:** Google sign-in email/basic identity as requested; selected or
  app-created Drive files and metadata; organization config, member spreadsheet,
  logo, and sharing permissions. The spreadsheet contains member names, IDs,
  status, QR hashes and optional contact fields/notes. Config includes approved
  account emails/roles, password verifiers, branding, and Pro/purchase metadata.
- **Use:** connecting an organization, synchronizing its directory/settings,
  displaying branding, enforcing access, and storing organization Pro status.
- **Sharing:** Drive stores the organization files; approved organization Google
  accounts receive the configured read/write permissions. Disclose other actual
  providers and developer access, if any. Review billing and SDK behavior before
  claiming there are no other recipients.
- **Protection:** HTTPS for Google API calls, authenticated Google accounts,
  per-file sharing and roster/role checks, app-private local storage, password
  hashing, and encrypted invitation payloads. Do not claim application-level
  encryption of all spreadsheets, JSON, or Room data: those are ordinary files.
- **Retention/removal:** describe the Drive owner's control, local copies, and
  explicit leave and app-data deletion paths. Revoked or unavailable Drive access locks
  app use; an ambiguous 404 now preserves local rows and connection metadata for
  recovery. Do not promise that revocation itself wipes the device. Android
  backup is enabled with default rules; account for that before promising that
  uninstall permanently deletes every copy. Set accurate retention/contact terms.
- Address Google's Limited Use/AI training disclosure requirements truthfully.
  This Drive integration contains no model-training feature; verify the full
  product and actual data practices before making policy-wide statements.

## Reply after completion

> Confirming narrower scopes
>
> We have migrated easyPass to
> https://www.googleapis.com/auth/drive.file. The app uses the native Google
> Picker to authorize existing organization files and destination folders.
> We have aligned our code and Cloud Console configuration and updated the
> privacy policy: [PUBLIC POLICY URL].
>
> Demonstration: [VIDEO URL, IF REQUESTED].
> Reviewer installation and navigation: [INSTRUCTIONS].
> Previously approved scopes retained, if applicable: [LIST OR NOT APPLICABLE].
>
> Please continue the verification review and let us know if further evidence
> is required.

## Official references

- [Drive scopes and migration](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
- [Native Android Picker](https://developers.google.com/workspace/drive/picker/guides/desktop-mobile-picker)
- [Google Play services releases](https://developers.google.com/android/guides/releases)
- [Minimum scopes and staged migration](https://support.google.com/cloud/answer/13807380)
- [Verification and privacy-policy requirements](https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification)
- [Drive authorization errors](https://developers.google.com/workspace/drive/api/guides/handle-errors)
