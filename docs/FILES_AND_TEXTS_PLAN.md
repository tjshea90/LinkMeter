# Files, attach-to-phone-apps and Texts: plan, design, status

Read `HANDOFF.md` first (it says how to resume and has the rules). This file is the design and the progress list for ONE big
request from the user (2026-11-08):

> Send and receive SMS from the phone on the tablet, with an easy way to attach photos and files from the tablet to the SMS;
> also attach photos and files from the tablet's storage to ANY app on the phone while using it remotely; and revamp the
> app's file manager into a polished one (copy / paste / delete, thumbnails, ...) so files move easily between phone and
> tablet. Research popular file managers such as File Manager + (which the user likes).

Keep the **Status** list at the bottom true. Every phase is released on its own (plain `tools/checkpoint.sh`), so the user can try it.

## Research (what polished file managers do, and what we take)

Sources: File Manager Plus (package `com.alphainventor.filemanager`, listed on APKMirror / SourceForge / malavida), roundups on
anexplorer.io, bestforandroid.com, maketecheasier, androidheadlines (Solid Explorer, X-plore, Material Files, Files by Google).

| Pattern | Seen in | Our version |
|---|---|---|
| Copy / cut / paste, delete, rename, compress, bookmarks | File Manager Plus | all except compress (later); bookmarks = shortcut chips |
| After a paste: "Copy again" and "Return to source folder" | File Manager Plus (update note) | same two buttons on the paste bar |
| Storage categories (Images, Videos, Audio, Documents, Downloads, Recent) and a storage analyzer | File Manager Plus, Files by Google | shortcut chips + Recent + used/free bar per device |
| Breadcrumb path bar, sort, list/grid, hidden files | all of them | same |
| Long-press selects, then an action bar for the selection | all of them | same |
| Dual pane | Solid Explorer, X-plore | one pane with a This-tablet / Phone switch and ONE shared clipboard (portrait tablet is 600 px wide); two panes side by side when the width allows (later) |
| Thumbnails for images/video/PDF/APK | all of them | small cached thumbnails, fetched from the phone by `/fs/thumb` |
| Conflict dialog (replace / keep both / skip, "do for all") | Solid Explorer | same |
| Built-in image viewer | File Manager Plus | simple full-screen viewer (later) |

Not taken: cloud/NAS/FTP connections (out of scope), root explorer, app manager.

## Design

### The big idea: a `Store` per device, one transfer engine
* `Store` (pure Java) is the small set of things the engine needs from a device's storage: stat, list, read(offset), write
  (temp file, then rename), mkdirs, delete, rename, and optional same-device `copyWithin`.
* `DiskStore` = `java.io.File` rooted at shared storage, with the confinement rule (a path may not leave the root). Used by the
  tablet for its own files, by the phone's `/fs/*` server, and by the offline tests (temp dir).
* `RemoteStore` (Android) = the same `Store` over the HTTP routes below (`Client`, JSON). Thin, not testable offline.
* `TransferEngine` (pure) copies or moves a list of paths from one `Store` to another: plans the folder tree, resolves name
  conflicts (replace / keep both / skip / ask, "for all"), moves only delete the source after the copy succeeded, cancel at any
  moment removes the half-written file, progress by bytes, one failure does not stop the rest, never copies a folder into itself.
* The tablet UI never does file logic itself: it asks the engine and shows what it reports.

### Phone `/fs/*` routes (all need the paired token; `Files.serve`, same code on the tablet for "let the phone browse the tablet")
Existing: `ls`, `get`, `put`, `mkdir`, `rm`. New (older phone apps answer 404 for them: the tablet checks `/fs/info` first and falls
back to the basic routes plus a banner "update the phone"):
* `GET /fs/info` -> `{"v":2,"root":..,"total":..,"free":..,"short":[{"n","p","c"}]}` (capabilities, space, shortcut folders that exist)
* `GET /fs/ls?p=&cnt=1` -> items `{n,d,s,t,c?}` (c = child count for folders when asked)
* `GET /fs/thumb?p=&s=160` -> JPEG bytes (404 = no thumbnail)
* `POST /fs/rename?p=&name=`; `POST /fs/mv?from=&to=&pol=`; `POST /fs/cp?from=&to=&pol=` -> `{job}`; `GET /fs/job?id=`; `POST /fs/job/cancel?id=`
* `GET /fs/stat?p=`, `GET /fs/du?p=` (bounded), `GET /fs/search?p=&q=` (bounded), `GET /fs/recent?kind=&limit=` (MediaStore)
* `GET /fs/get?p=` honours `Range: bytes=a-` (206); `POST /fs/put?p=&pol=&mtime=` (pol = both|replace|skip, keeps the modification time)
* every write that ends in a media folder is announced to MediaStore (`MediaScannerConnection`), so Photos / pickers see it at once.

### Tablet UI (rewrite of `FilesTab`, pure code views, no libraries)
Top: [This tablet | Phone] switch with a used/free bar; breadcrumb bar; shortcut chips at the root (Camera, Pictures, Screenshots,
Downloads, Documents, Music, Movies, Recent); list or grid (thumbnails) with sort and hidden-files in the overflow menu; long-press
selection with an action bar (Copy, Cut, Delete, Rename, Details, "Send to Phone" / "Get from Phone"); a paste bar while the clipboard
holds something (Paste here, Cancel, then "Copy again" / "Return to source folder"); a transfer card with progress and Cancel; a
conflict dialog; open a file with another app through our own tiny `ContentProvider` (no androidx), images in a simple viewer.
Low-RAM tablet (Android 10 Go): `ListView`/`GridView` recycling, thumbnails 160 px in an 8 MB `LruCache` + a small disk cache, two
loader threads, one transfer at a time.

### Attach to any phone app while remote (phase B)
Paperclip in the Screen tab -> picker (recent tablet photos first, "Browse", system picker for other sources) -> upload to the phone's
`Pictures/LinkMeter` (images, video) or `Download/LinkMeter` (the rest) + media scan -> the user taps Attach in the phone app and
finds the file at the top of Recent / Gallery / Downloads. That works in every app, with no per-app code.
(Considered and left out for now: share sheet through `am start` (shell cannot send a list of URIs), clipboard paste.)

### Texts (phase C)
Phone side (`/sms/*`, off unless the user switches "Let the tablet use my texts" on in the phone's app):
* permissions `READ_SMS`, `SEND_SMS`, `READ_CONTACTS` are requested the normal way, or granted through Shizuku
  (`appops set <pkg> ACCESS_RESTRICTED_SETTINGS allow`, then `pm grant`), because a side-loaded app's SMS permissions are "restricted".
* read: threads from `content://mms-sms/conversations?simple=true` + `canonical-addresses`; SMS from `content://sms`; MMS from
  `content://mms` + `part` + `addr`; names from `ContactsContract.PhoneLookup`.
* send: `SmsManager.sendTextMessage / sendMultipartTextMessage`; with attachments or several recipients an MMS PDU is built by our own
  encoder and sent with `SmsManager.sendMultimediaMessage` (needs our own `ContentProvider` + a URI grant, like FileProvider).
* a non-default SMS app cannot write to the SMS provider, so what the tablet sends is kept in a small local log and merged into the
  thread view (duplicates removed if the provider also has it). Delivery state comes from the sent/delivered PendingIntents.
* new messages: the tablet polls `/sms/stamp` (cheap) while the tab is open and refetches when it changes. No broadcast receiver needed.
* fallback when direct MMS fails: send the files to the phone and open its own Messages app with the text and files (user taps send).
Tablet: new "Texts" tab (conversation list, thread with bubbles and image thumbnails, composer with an attach button that reuses the
phase-B picker, new-message with contact search).

## Test strategy
Everything with logic is pure Java and tested offline (`tools/test.sh`): paths, kinds, sorting, clipboard, store + engine (temp dirs,
failing stores, cancel at every moment, conflict policies, random stories, mutation checks), later the MMS PDU encoder (round trip
through a spec-based decoder written in the test) and the thread merge. Shell commands run on the phone are tested the way Android's
shell runs them (see `SurveyCommandTest`: no built-in printf, 128 KB argument limit). Android views are only compiled by CI, so they
stay thin.

## Risks / unknowns only the devices can answer
* Does `pm grant` + the restricted-settings appop make the SMS permissions work on this phone (Samsung A15, Android 16)?
* Does the carrier accept our MMS PDU (hand-built)? The fallback is the phone's own Messages app.
* Do thumbnails fetched over the hotspot feel fast enough? (small JPEGs, cached on disk)

## Status (keep true)
- [x] plan written (this file) and checkpointed
- [x] A1 core: FsEntry, FsPath, FileKind, FileSort, FsFormat, Clip, Store, DiskStore, TransferEngine + tests (FilesCoreTest ~10,400 checks, TransferEngineTest ~4,600 checks against an in-memory model, 12 deliberate breakages caught)
- [x] A2 phone routes + RemoteStore + media scan: protocol layer tested offline end to end (FsProtocolTest ~600 checks, JsonTest ~4000, 11 breakages caught); Android adapters written (Files.serve on FsApi, AndroidMedia, ThumbMaker) and CI-compiled
- [x] A3 Files tab UI written and CI-compiled (FilesTab, FileBrowser, Transfers, FileDialogs, Thumbs, FileIcon, FilesPrefs, ShareProvider); released; waiting for the user's field test
- [ ] B attach from the remote screen, release
- [ ] C Texts: pure parts + tests
- [ ] C Texts: phone side
- [ ] C Texts: tablet UI, release
