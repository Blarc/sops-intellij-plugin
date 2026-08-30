# SOPS editor content flow

The SOPS editor works with several versions of the same file. Each `SopsContent` contains both the
encrypted file text and the plaintext produced by decrypting it:

```text
SopsContent
├── encryptedText
└── decryptedText
```

## Content overview

```mermaid
flowchart TD
    vcs[Latest VCS revision<br/>encrypted] --> revision[SopsEditorRevisionTracker]
    revision -->|decrypt| vcsPlain[Decrypted VCS revision]
    vcsPlain --> gutter[Line-status gutter<br/>base revision]
    revision -->|onRevisionLoaded| rollback[rollbackContent<br/>initially: VCS encrypted + VCS plaintext]

    disk[Encrypted editor document<br/>latest content on disk] -->|beginDecryption| request[DecryptionRequest<br/>request number + encrypted snapshot]
    request -->|decrypt| external[External content<br/>disk encrypted + decrypted plaintext]

    local[Decrypted editor document<br/>local plaintext] --> decision{Compare with<br/>previous synced plaintext<br/>and external plaintext}
    synced[syncedContent<br/>last accepted disk version] --> decision
    external --> decision

    decision -->|local unchanged or<br/>local equals external| accepted[Accept external version]
    accepted --> synced
    accepted --> local

    decision -->|local and external<br/>changed differently| conflict[externalConflict<br/>external version awaiting a decision]
    conflict -->|Load External Changes| synced
    conflict -->|Load External Changes| local
    conflict -->|Keep Local Changes| keep[Move external version to syncedContent<br/>then encrypt local plaintext]
    keep --> synced

    external -. plaintext matches rollback plaintext .-> metadata[Metadata-only update]
    metadata -. replace only encryptedText .-> rollback
```

## Responsibilities

| Value | Owner | Meaning | Used for |
| --- | --- | --- | --- |
| `encryptedRevision` | `SopsEditorRevisionTracker` | Encrypted text from the latest VCS revision | Avoiding repeated revision decryption |
| `decryptedRevision` | `SopsEditorRevisionTracker` | Plaintext from the latest VCS revision | Gutter change markers |
| `rollbackContent` | `SopsEditorContentState` | Plaintext baseline and ciphertext to restore when the editor returns to that plaintext | Undoing an edit without creating new SOPS ciphertext |
| `syncedContent` | `SopsEditorContentState` | Latest disk version accepted by the editor | Detecting local changes and preserving accepted external ciphertext |
| `externalConflict` | `SopsEditorContentState` | External disk version that conflicts with local plaintext | Waiting for the user to keep local or load external changes |
| Decrypted editor document | IntelliJ editor | Current local plaintext | What the user sees and edits |
| Encrypted editor document | IntelliJ editor | Current encrypted file content | What SOPS reads or the plugin restores |

## Revision and rollback relationship

The revision tracker and `rollbackContent` initially contain the same VCS content:

```mermaid
flowchart LR
    revision[VCS revision<br/>E₀ + P₀] -->|revision loaded| rollback[rollbackContent<br/>E₀ + P₀]
```

They can intentionally diverge after an external metadata-only operation such as
`sops updatekeys` or `sops rotate`. If the new ciphertext still decrypts to the rollback plaintext,
the plugin keeps that ciphertext as the new rollback target:

```mermaid
flowchart LR
    revision[VCS revision<br/>E₀ + P₀]
    external[External metadata update<br/>E₁ + P₀]
    rollback[rollbackContent<br/>E₁ + P₀]

    revision -. remains the gutter base .-> rollback
    external -->|same plaintext P₀| rollback
```

Here `E₀` and `E₁` are different encrypted representations, while `P₀` is the same plaintext.
Keeping `E₁` prevents a later editor save from undoing the externally updated SOPS metadata.

## Saving decrypted edits

```mermaid
flowchart TD
    save[Save local plaintext] --> rollbackMatch{Matches rollback<br/>plaintext?}
    rollbackMatch -->|yes| restoreRollback[Restore rollbackContent.encryptedText]
    rollbackMatch -->|no| syncedMatch{Matches synced<br/>plaintext?}
    syncedMatch -->|yes| restoreSynced[Restore syncedContent.encryptedText]
    syncedMatch -->|no| encrypt[Run SOPS edit encryption]
```

The rollback comparison uses semantic formatting equality. Conflict detection uses exact plaintext
equality so the plugin does not silently choose between independently edited local and external
content.

## External change decision

Given:

- `local` = plaintext currently in the decrypted editor;
- `previous synced` = plaintext last accepted from disk;
- `external` = plaintext just decrypted from the changed encrypted file.

The decision is:

| Condition | Result |
| --- | --- |
| `local == previous synced` | The editor has no local edit, so load the external version |
| `local == external` | Both sides independently reached the same plaintext, so accept the external ciphertext |
| Otherwise | Store `externalConflict` and ask the user which version to keep |

Only the latest `DecryptionRequest` may make this decision. A result is discarded when a newer
request exists or the encrypted editor document no longer matches the request's encrypted snapshot.
