# Troubleshooting

## The Deekseep Entry Does Not Appear

1. Confirm that the installed APK matches the framework interface.
2. Confirm the module is enabled.
3. Scope it to `com.deepseek.chat` and the module's own package.
4. Force-stop DeepSeek instead of only leaving the activity.
5. Open the root DeepSeek settings screen; the entry is intentionally removed
   on other routes.
6. Check the module launcher activation status.

For a current LSPosed installation, use the stable API 102 build. If loading is
uncertain, install and enable the API 102 load probe by itself.

## Android Reports an Incompatible Update

The modern and legacy forms of a channel use the same package ID but separate
development signing keys. Disable and uninstall the old module APK before
installing the other interface variant.

Uninstalling the module package does not uninstall DeepSeek or automatically
remove prior database edits.

## DeepSeek Crashes After an Update

Disable Deekseep, force-stop DeepSeek, and confirm that the host works without
the module. Obfuscated symbols probably changed if the crash began immediately
after a DeepSeek update.

Report:

- exact DeepSeek version name and code;
- exact Deekseep asset name;
- Android and framework versions;
- only the redacted exception and nearby module log lines.

Do not upload an entire response log or database.

## Prompt Injection Does Not Work

- Confirm a prompt file was imported successfully.
- Confirm the injection switch is enabled.
- Reimport if the original document was moved or permission was revoked.
- Test with a new conversation.
- Check the private copied prompt rather than relying only on a displayed shared
  storage path.

## The Prompt Appears in Stored User Messages

The stable API 102 build strips the module's exact system prefix from displayed
and persisted user request fragments. Restart once to allow background cleanup.
If a manually edited prefix differs from the exact wrapper, it is intentionally
not removed.

## An Answer Still Disappears

Response preservation must be enabled before the replacement event arrives. It
only handles the known client-side `CONTENT_FILTER` replacement path. It cannot
restore output the server did not send or data already deleted before the hook
observed it.

## Added Reasoning and the Answer Both Disappeared

Install the v1.7 r2 APK matching your framework, ensure it is the only active
Deekseep hook, and force-stop/restart DeepSeek. Every complete variant now runs
the startup migration, which should report a positive
`repairMalformedThinkFragments fixed=N` once and zero later.

If the database row no longer contains the original `RESPONSE`, restore it from
a database backup.

## A Search Result Does Not Open

Search includes user input, model output, and deep-reasoning fragments. Opening
uses DeepSeek's native sidebar session controller, so the target conversation
must belong to the currently logged-in account and be present in its native
session list. Switch to the account named by the local database and search
again. Deekseep intentionally does not fall back to its editor for this action.

## Local Edits Revert

The editor raises the local session cache version after a successful save, but a
different account database, a server-side replacement, or another active module
can still cause unexpected results. Confirm account selection, disable duplicate
variants, and inspect a backup copy before editing again.

## Expert Image Relay Fails

- Confirm the expert option is enabled before entering or reselecting the model.
- Test one image first.
- Confirm the account can use the required server models and upload path.
- Expect failure after host or server protocol changes.
- Enable relay diagnostics only for the shortest possible test.

Relay failure should fall back to the original request, but the original expert
path may still reject images.

## Images Disappear After Reopening a Relay Conversation

The current relay stores image fragment metadata and merges it only into a
marked relay request that has no server image. Signed image paths can expire,
and older relay messages created before capture support may have no persisted
source. New host history schemas can also break restoration.

## Backup or Export Files Are Missing

Check Android storage permission and the application's external files directory.
On recent Android versions, general file-manager visibility and all-files access
are controlled separately. Automatic backups are private and rotate to the five
newest copies.

## Safe Log Sharing

Before sharing, remove:

- prompts and responses;
- account and session IDs;
- file IDs and names;
- signed URLs or paths;
- authorization and proof-of-work values;
- database paths tied to an account.

Prefer a ten-to-twenty-line excerpt around the first error.
