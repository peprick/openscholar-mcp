# ADR 0010: Store one encrypted offline metadata pack

- Status: accepted
- Date: 2026-08-24

## Context

The account-neutral PWA shell in [ADR 0007](0007-use-an-account-neutral-pwa-shell.md)
starts safely without a network connection, but it intentionally contains no
research data. A researcher may still want a chosen collection's bibliography,
reading state, and tags when the Next.js, Spring Boot, or PostgreSQL services
cannot be reached. Caching an owned page or React Server Component response
would cross the hosted-session boundary, and storing an encryption key beside
the ciphertext would provide no meaningful protection on a shared device.

Browser storage is also neither an authorization boundary nor a durable backup.
Same-origin script injection, a malicious extension, or a compromised device can
observe data while it is unlocked. IndexedDB can be cleared or evicted, and the
server cannot reach a disconnected browser to delete a local copy.

## Decision

### Bounded server snapshot

Expose `GET /api/v1/collections/{collectionId}/offline-pack` under the existing
library scope. The operation resolves the current owner before reading saved
papers, uses one repeatable-read transaction, makes no provider request, and
returns missing and other-owner collections through the same not-found contract.
It serializes once with the configured JSON mapper, rejects more than 500 papers
or more than 1,048,576 exact UTF-8 response bytes, and returns those same bytes
with `Cache-Control: no-store`.

Schema version 1 contains the collection name/description plus stored
bibliographic fields, identifiers, reading status, and tags in deterministic
order. It contains no abstract or full text, source document, PDF byte, access or
provider URL, provenance payload, citation metric, job state, account identifier,
or authorization material. The same-origin Next.js BFF validates and forwards
this no-store contract; neither server writes the pack into a browser cache.

### One explicit browser copy

Version 1 supports one active collection pack per browser origin/profile. Saving
a second collection explicitly replaces the first. A refresh is a manual,
complete snapshot replacement: there is no background sync, delta, tombstone,
offline mutation, conflict resolution, or promise that the copy is current.
PostgreSQL remains authoritative.

The online collection page requires a user-entered passphrase of 12 to 128
Unicode code points and at most 256 UTF-8 bytes, entered twice when saving without
trimming or normalization. It generates a
fresh 16-byte random salt and 12-byte random IV for every replacement, derives a
non-extractable AES-256-GCM key with PBKDF2-HMAC-SHA-256 at exactly 600,000
iterations, and uses a 128-bit authentication tag. The passphrase, encoded
passphrase bytes, derived key, and plaintext are never intentionally persisted,
logged, exported, or sent to OpenScholar. Temporary byte arrays are cleared where
JavaScript permits; garbage-collected strings cannot be guaranteed to be
zeroized.

IndexedDB database `openscholar-private-offline-v1`, object store `packs`, key
`active`, contains only:

- the exact envelope, payload, KDF, and cipher versions;
- an opaque HMAC-derived owner scope and a SHA-256 collection-UUID digest;
- the fixed iteration count, random salt and IV; and
- authenticated ciphertext.

The same store also contains one non-secret `control` record with the envelope
version, opaque owner scope (or `null` after a full purge), and a fresh random
lifecycle epoch. It contains no collection metadata, plaintext, passphrase, key,
or bearer credential.

The opaque owner scope is a stable mismatch detector, not a bearer credential or
authorization decision. In hosted mode it is a purpose-separated HMAC of the
configured issuer and subject under the server-only session-sealing key; local
mode uses a fixed local scope. AES-GCM additional authenticated data is a fixed,
ordered UTF-8 tuple—not object serialization—that binds the application label,
envelope version, record ID, normalized origin, opaque owner scope, collection
digest, exact KDF/hash/work factor, encoded salt, cipher/key length, encoded IV,
and tag length. The envelope is treated as untrusted: versions, algorithms, exact
work factor, field sizes, IV/salt lengths, and ciphertext ceiling are checked
before key derivation. Wrong passphrases, unsupported envelopes, and
modified/corrupt ciphertext share one public error and never cause automatic
deletion.

Before encryption, the runtime save captures the current control epoch for the
verified owner. Encryption and validation complete before the final read-write
transaction opens.
That transaction rereads and verifies the owner and epoch before `put(active)`
atomically replaces the record. Purge and owner transitions rotate the control
epoch in their deletion transaction; a delayed save that observes a different
epoch is rejected. The old pack is never deleted first during ordinary
replacement, so an abort or quota failure leaves it intact. A storage estimate
may give an early warning, but the IndexedDB transaction is decisive. Version 1
does not request persistent-storage permission: this is a reproducible best-effort
copy, not a backup. The UI states that the browser may remove it.

### Cold-offline reader and lifecycle

The service worker continues to cache no owned response. It installs the neutral
`/offline.html` and one same-origin static reader runtime as a required, versioned
pair; any existing worker remains active unless both assets are fetched and
written successfully. The active worker serves those exact paths cache-only, so
per-request or background refresh cannot mix shell and runtime versions. The
manifest/icons and runtime-static assets retain the account-neutral policy from
ADR 0007. The service worker never opens IndexedDB or decrypts a pack. The
fallback shows generic locked controls until the user explicitly enters the
passphrase. After authenticated decryption it validates the payload, renders
untrusted strings with DOM `textContent`, and offers read-only local filtering,
Lock, and Delete local copy. It has no paper/provider links, mutation controls,
access checks, background requests, or PDF reader. It clears rendered plaintext
on Lock, `pagehide`, and hidden visibility.

When `/api/auth/status` is reachable, both the normal app and static reader purge
a hosted pack before unlock if its opaque owner scope is missing or different.
When that check is unreachable, the offline owner may unlock with the passphrase;
no account fact is inferred from a network failure. Cross-tab messages contain
only `LOCK`, `PURGE`, or `REPLACED` events and cause visible plaintext to lock.

The normal UI attempts targeted local purge before collection deletion, account
deletion, and hosted logout. A failed server deletion may therefore require the
researcher to recreate the offline copy, which is preferable to a committed or
response-lost deletion leaving readable local data behind. Successful hosted
logout and successful account deletion also return `Clear-Site-Data: "storage"`
as defense in depth. That directive is browser-dependent and deliberately clears
the neutral PWA registration/cache too; a later online visit reinstalls it. It is
not used for ordinary collection deletion. Direct API use, browser crashes, and
session expiry cannot synchronously erase a disconnected device, so ciphertext
remains locked until a later owner check or explicit local deletion.

## Consequences

- A researcher can browse one selected collection's metadata and reading state
  during a true cold-offline start without storing source documents.
- CacheStorage stays account-neutral, while the only owned browser record is
  opt-in and encrypted under a secret not recoverable from browser storage.
- Forgotten passphrases have no recovery path. Weak passphrases remain vulnerable
  to offline guessing despite the fixed work factor.
- Same-origin XSS, extensions, screen capture, and compromised devices remain able
  to observe plaintext or input while unlocked. Encryption protects only the
  locked at-rest copy.
- The pack can be stale, quota-limited, evicted, or cleared. It is not a backup and
  never overrides PostgreSQL.
- The lifecycle fence begins only when the runtime save captures its epoch. It does
  not yet serialize the earlier owner/snapshot fetch against collection/account
  deletion or hosted logout in another tab. A workflow that passes pre-purge can
  therefore capture the new epoch while the server mutation is pending and later
  restore a local pack. Workflow-wide cross-context exclusion or a durable deletion
  tombstone remains required; `Clear-Site-Data` is only browser-dependent defense
  in depth for successful hosted logout/account deletion.
- Supporting multiple packs, offline writes, PDFs, summaries, or automatic sync
  requires a new decision and conflict/legal/security review.

## Validation

Tests must cover owner-indistinguishable not-found behavior, deterministic and
metadata-only payloads, zero provider calls, exact byte/item bounds including
multibyte UTF-8, no-store forwarding, fixed cryptographic parameters, round-trip,
wrong-passphrase/tamper equivalence, untrusted-envelope bounds, atomic replacement
and quota failure, owner mismatch, logout/deletion purge, cross-tab locking, and
the absence of owned values in CacheStorage. A production-mode browser test must
save online, start cold offline, unlock and read through the neutral fallback,
pass an accessibility scan, then lock and remove the local copy.

## Guidance

- [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- [OWASP HTML5 Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/HTML5_Security_Cheat_Sheet.html)
- [W3C Web Cryptography API](https://www.w3.org/TR/WebCryptoAPI/)
- [W3C Indexed Database API](https://www.w3.org/TR/IndexedDB/)
