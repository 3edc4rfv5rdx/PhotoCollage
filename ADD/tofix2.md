# PhotoCollage — deep audit 2

Date: 2026-11-03. Audited at commit `aa41fb3` (v0.2.50 release notes).

Product contract used: `README.md` (Features), `CHANGELOG.md` up to v0.2.50,
the KDoc of each source file, the unit tests under `app/src/test`, and
`ADD/tofix1.md` — all nine of its findings are marked fixed and were re-read
against the current code; none is re-filed here. Where a fix left a window
open, the finding below says so.

Every source file under `app/src/main/java/xx/photocollage` (including `ui/`)
was read end to end, plus the manifest, `res/xml/*`, and `strings.xml` in all
three locales (72 keys each, same set). No finding was probed on a device; all
are reasoned from the code. Where a finding rests on platform behaviour or on
how often something happens, it says so.

## System model

**Components and owned state**

- `Collage` / `Node` (`Collage.kt`) — immutable value: a tree of `Split` cuts
  and `Leaf` cells, each with an optional `CellPhoto` framing. Cells addressed
  by index in reading order.
- `History<T>` — the only mutable holder of the current collage; back/forward
  stacks capped at 100; one open gesture (`gestureStart`). `push` closes an open
  gesture first.
- `CollageViewModel` — owns `history`, `EditorState` (selection, swap source,
  `busy`, `hasDraft`, `draft`, `pendingShare`), the preview cache
  (`previews`, `loading`, `failedAt`, `saidUnreadable`) and the events flow.
  Process-wide statics: `photoLock`, `cleaned`, `pendingSession` and the
  `sessionWriter` scope that writes `current.json` and outlives the VM.
- `Photos.kt` — private photo copies in `filesDir/photos/<uuid>.img`; import,
  decode (EXIF upright, shrinking retries), once-per-process clean-up.
- `Draft.kt` — JSON codec shared by `draft.json` (by hand) and `current.json`
  (the session, written after every non-gesture step).
- `Export.kt` / `Renderer.kt` — `renderCollage` at full size through the same
  `drawCollage` the screen uses (incl. the blend's second pass); save via
  MediaStore or a SAF tree; share via `cache/shared` + FileProvider.
- `MainActivity` — `singleTask`, portrait; Share via `onCreate` (only when
  `savedInstanceState == null`) and `onNewIntent`.
- UI — `MainScreen` (top bar, tool menus, dialogs, busy overlay),
  `CollageCanvas` (all touch), `RotatedFrame`, Settings.

**Lifecycle of the main operation**

Start → VM `init` restores the session (or `pendingSession`), reads the draft
async, then cleans up photo copies not named by draft or session → Share /
pick → `importing` (busy, `photoLock`, clean-up first) → `push` → `publish`
(state, previews, session text) → edits/gestures → Save/Share → `rendering`
(busy, `photoLock`, decode each photo at its needed size, compress, write).

**Untrusted input crosses in at**: shared/picked URIs (content and size), the
Share intent itself (re-delivered by the system), EXIF, the draft/session files,
the typed file name, the SAF tree URI and its grant.

**Invariants**

- I1. A photo copy is deleted only when nothing that can still show it names it.
- I2. What is saved is what was seen.
- I3. The user's collage is not lost or replaced without the user choosing it.
- I4. One user action is one history step, in order.
- I5. The busy overlay is up exactly while background work the editor must
  wait for is running.
- I6. A message to the user states what actually happened, and README states
  what the app actually does.
- I7. One user action brings in its photos once.

## Findings

### 1. P2 [FIXED 31598b8] — Reopening the app from Recents after closing it re-delivers the old Share

- **Affected components:** `MainActivity.onCreate`
  (`if (savedInstanceState == null) viewModel.receiveShared(shared)` with
  `shared = sharedImages(intent)`), `CollageViewModel.receiveShared`,
  `startNew`, `NewCollageDialog`; the close button (`onExit = ::finish`) and
  system Back with nothing selected (no `BackHandler`, so the activity finishes).
- **Current behavior and reproduction:** share photos from a gallery into
  PhotoCollage (the `singleTask` activity starts a task whose base intent is
  `ACTION_SEND` with `EXTRA_STREAM`). Edit, then tap ✕ or Back: the activity
  finishes, the task stays in Recents. Tap it in Recents: Android relaunches the
  task with its base intent and `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`, and
  `savedInstanceState` is null. `onCreate` reads the old `EXTRA_STREAM` again and
  calls `receiveShared`. The restored collage holds work, so the "Start a new
  collage?" dialog appears out of nowhere; if the user starts, the same photos
  are imported a second time (or, if the read grant did not survive, fail with
  "Photos that could not be read"). With a collage that holds no work, the
  photos are imported again immediately with no question. The same happens on
  any later relaunch of that task from Recents. The re-delivery from history is
  documented platform behaviour; whether the URI grant survives it was not
  probed.
- **Root cause:** I7/I3: the code treats "intent present, no saved state" as
  "new Share", but a relaunch from history satisfies that too. Nothing records
  that this intent was already consumed.
- **Required outcome:** a Share is received once. A launch with
  `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` set does not receive the intent's
  photos; it opens the editor as a plain launch would (and may run the start-up
  update check, as a plain launch does).
- **Constraints:** a fresh Share cold start, a Share while the editor is open
  (`onNewIntent`), and recreation after process death (where the collage is
  already in the session) keep today's behaviour. The question before replacing
  work stays.
- **Tests to add:** extract the "should this intent's photos be received"
  decision into a pure function of (action, flags, savedInstanceState != null)
  and unit-test it: SEND fresh → yes; SEND with LAUNCHED_FROM_HISTORY → no;
  SEND with saved state → no; MAIN → no.

### 2. P3 [FIXED 67a41b6] — The busy overlay is one boolean shared by imports and renders: the first to finish lifts it while the other still runs

- **Affected components:** `CollageViewModel.setBusy`, `importing`
  (`setBusy(true)` … `finally { setBusy(false) }`), `rendering`
  (`if (_state.value.busy) return`, `setBusy(true)` … `finally { setBusy(false) }`),
  `receiveShared` (from `onNewIntent`), `MainScreen` overlay (`if (state.busy)`).
- **Current behavior and reproduction:** start a Save of a large collage (the
  render takes seconds), and while it runs share photos into the app from
  another one (the editor is a `singleTask`, so `onNewIntent` →
  `receiveShared`). If the collage holds no work beyond the draft, `addPhotos`
  starts an import immediately: `setBusy(true)` (already true), and when the
  import finishes `setBusy(false)` — while the render is still running. The
  overlay drops, the editor takes touches, and the `rendering` guard no longer
  stops a second Save/Share, which then queues behind `photoLock` and runs a
  second full render. The same applies to two overlapping imports (a picker
  result and a Share). Needs two overlapping pieces of background work; low
  frequency.
- **Root cause:** I5: a boolean answers "is anything running" only for one
  worker at a time.
- **Required outcome:** the overlay stays up until every running import and
  render has ended; a second Save/Share while one runs is refused as today.
- **Constraints:** the overlay must always come down after the last job ends,
  including on exception and cancellation (keep the `finally`).
- **Tests to add:** if the count is pulled into a small class, a unit test:
  start A, start B, end A → still busy; end B → not busy.

### 3. P3 [FIXED 07b2c80] — With a gap, Blend bleeds every outer photo into the collage's outer margin

- **Affected components:** `Renderer.kt` `blendBands` (KDoc: "none past the
  canvas's own edge, where there is no neighbour"), `drawCollage` second pass
  (`blendBands(cell, layout.blend, canvasBox)` with `canvasBox = Box(0f, 0f, width, height)`),
  `layoutOf` (outer margin = `gap`), `GeometryTest.blendBandsLieOnlyWhereANeighbourCanBe`
  (tests only a cell flush with the canvas).
- **Current behavior and reproduction:** set Gap > 0 and Blend > 0. A cell on
  the canvas's left edge has `cell.left = gap` (half a gap of outer inset plus
  half a gap of cell inset). Its left band `Box(cell.left - blend, …, cell.left, …)`
  lies inside `canvasBox`, so it is kept, and the photo is painted into the
  outer margin at half alpha fading out — on all four outer edges, in the editor
  and in the saved image. Between cells the band crosses the gap toward the
  neighbour, as intended; at the outer edge there is no neighbour.
- **Root cause:** the rule "no band where there is no neighbour" is implemented
  as "no band past the canvas", which is the same thing only when the gap is 0.
- **Required outcome:** no band is drawn on a side of a cell that faces the
  outer edge of the collage, whatever the gap. (Clip to the box the cuts divide —
  the canvas inset by half a gap — and treat a cell side within half a gap of
  it as an outer side, or decide outer sides from the layout tree.)
- **Constraints:** with gap 0 the output must not change; bands between cells,
  including across a gap, stay as they are; editor and saved image keep using the
  same `drawCollage`.
- **Tests to add:** `GeometryTest`: a cell inset by a gap from the canvas edge
  gets no band on that side; a cell in the middle still gets four.

### 4. P3 [FIXED 94cf66d] — With Blend on, a save decodes every photo twice, and a failure in the second pass discards a render that already drew it

- **Affected components:** `Renderer.drawCollage` (second pass calls
  `bitmapOf(photo.photo)` again, KDoc says so), `Export.renderCollage`
  (`onDrawn = { it.recycle() }`, `bitmapOf` decodes with `decodeShrinking` from
  the needed size each call, `unreadable += photo` on any null).
- **Current behavior and reproduction:** with `blend > 0`, each photo is decoded
  at full needed size in the first pass, recycled, and decoded again for its
  bands. Save time for the decode share roughly doubles (up to 16 photos of up
  to ~5000 px). If the second decode fails at every size (memory pressure
  differs between the two calls), the photo is added to `unreadable` and the
  whole save fails with "Not saved because photos could not be read", though
  the photo was drawn and the bands are only a fade. How often the second decode
  fails is an empirical assumption — rare; the cost is certain.
- **Root cause:** the one-photo-in-memory rule of `renderCollage` and the
  two-pass blend in `drawCollage` were each written without the other.
- **Required outcome:** each photo is decoded once per save: e.g. the blend
  bands of a cell drawn right after its own photo, from the same bitmap, before
  it is let go — while still drawn above every other cell's photo (the reason
  the pass is separate). One way: draw all photos first, then for the bands
  decode at a size only as large as the band needs, or keep the bitmap until its
  bands are done if the order can be kept. Whatever the order, a band that cannot
  get its bitmap must not turn a drawn photo into a failed save.
- **Constraints:** the editor and the saved image still look the same (I2);
  the save still fails when a photo cannot be drawn at all; memory peak stays at
  about one photo plus the canvas.
- **Tests to add:** a JVM test of the render loop with a counting `bitmapOf`
  fake: with blend on, each photo is requested once (or the second request's
  null does not mark it unreadable).
- **Accepted remainder:** the fix takes the second option — a band's failed
  decode no longer fails the save (`notingUnreadable` in `Export.kt`). Each
  photo is still decoded twice with Blend on: drawing the bands from the cell's
  own bitmap would need `drawCollage`'s two passes reordered. Such a band is
  left out of the saved image, a fade the editor showed.

### 5. P3 [FIXED ff287c8] — Until the draft is read at start, the editor thinks the collage differs from the draft

- **Affected components:** `CollageViewModel.init` (`state.draft` is null until
  the `viewModelScope.launch { readCollage(draftFile) }` completes),
  `newCollageLosesWork` (`hasWorkBeyond(_state.value.draft)`), `receiveShared`,
  `MainScreen` Save draft button and menu item (`enabled = hasWorkBeyond(state.draft)`).
- **Current behavior and reproduction:** save the collage as the draft, close
  the app, then share a photo into it (cold start). `onCreate` creates the VM and
  calls `receiveShared` in the same main-thread pass, before the draft read can
  complete, so `hasWorkBeyond(null)` is true and the "Start a new collage?"
  question is asked although the draft already holds the collage. For the
  first frames the Save draft button is also enabled. Code fact; the cost is an
  unneeded question, never a lost collage.
- **Root cause:** "what the draft holds" has an unknown state that is read as
  "no draft".
- **Required outcome:** decisions that depend on the draft wait for it, or the
  draft is read synchronously in `init` alongside the session file (it is the
  same size and already read there for `hasDraft` existence).
- **Constraints:** the clean-up's keep-set and the "a draft saved meanwhile is
  newer" rule stay correct.
- **Tests to add:** none practical on the JVM beyond `hasWorkBeyond`; check on a
  device with the steps above.

### 6. P3 [FIXED ff5054a] — "Could not save the image" when the folder picked in Settings has lost its grant or is gone

- **Affected components:** `Export.saveToFolder` (returns null on any exception
  from `getTreeDocumentId`/`createDocument`), `CollageViewModel.saveImage`
  (`R.string.image_save_failed`), `AppSettings.saveFolder`, Settings row
  (`folderLabel(saveFolder)` still shows the folder).
- **Current behavior and reproduction:** pick a folder in Settings, then delete
  or rename it in a file manager (or clear the grant by reinstalling a
  provider / removing the SD card). Every Save now ends in "Could not save the
  image", identical to a disk-full failure; Settings still names the folder, and
  nothing says to pick it again. Platform behaviour of revoked grants is reasoned,
  not probed.
- **Root cause:** I6: a failure the user can fix in one tap is reported as one
  they cannot.
- **Required outcome:** when the configured folder cannot be opened, the message
  says the save folder is not available and names it (e.g. "Save folder not
  available: <label>"), in all three locales. Whether to fall back to
  Pictures/PhotoCollage stays a product decision — the current KDoc says the
  file is not made elsewhere behind the user's back; keep that unless the user
  decides otherwise.
- **Constraints:** a failure while writing into an available folder keeps the
  generic message; no new permission.
- **Tests to add:** none on the JVM; device check by deleting the folder.

### 7. P3 [FIXED c7ca9c8] — README does not mention Blend

- **Affected components:** `README.md` Features: "**Look**: the gap's width,
  rounded corners, and the background colour." and "**Tools**: layout, format,
  gap, corners and background under the wrench"; `MainScreen.CollageTool.BLEND`;
  CHANGELOG v0.2.50 "N: Blend in the wrench menu …".
- **Current behavior and reproduction:** the wrench menu has six items; README
  lists five and never names Blend.
- **Root cause:** I6: the v0.2.50 feature commit did not carry its README edit.
- **Required outcome:** both README lines name Blend (photos fading into each
  other across the seam).
- **Constraints:** README only; no changelog entry (AGENTS.md: README-only
  changes go in without one).
- **Tests to add:** none.

### 8. P3 (accepted) — An import copies the whole stream before learning it is no image

- **Affected components:** `Photos.importPhoto`
  (`input.use { src -> file.outputStream().use { src.copyTo(it) } }`, then
  `inJustDecodeBounds`).
- **Current behavior and reproduction:** any app can Share a stream labelled
  `image/*`. Its whole content is copied into `filesDir/photos` before the
  bounds check; a multi-GB stream fills internal storage until the copy fails or
  completes, then is deleted. Decoding itself is bounded (`sampleSize`), so this
  is storage and time, not memory.
- **Root cause:** validation after copy; no size cap.
- **Accepted:** single-user app, the sender is an app the user chose to share
  from, and the copy is removed on failure. Revisit only if a cap is wanted
  (e.g. stop the copy past some hundreds of MB and treat it as unreadable). Any
  rewrite of `importPhoto` should keep the delete-on-failure path.
- **Tests to add:** none.
