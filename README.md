# PhotoCollage

**Photo collages on your phone: pick photos, pick a layout, move the borders,
save or share.**

![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

## Features

- **Photos in**: from the system's picker — which lists every app that hands
  out images, PhotoBox among them — or shared to PhotoCollage from another app.
- **Layouts**: ready-made ones for 1 to 9 photos; any cell can be cut in two,
  across or down, or removed, up to 16 cells.
- **Borders**: drag a gap between cells to move it.
- **Photo in its cell**: drag to move it, pinch to zoom, turn it a quarter at a
  time, replace or clear it.
- **Swap**: long-press a photo and drop it on another cell, or select a cell,
  tap Swap and tap the other one.
- **Look**: format 1:1, 4:5, 3:4, 2:3, 9:16 or A4, portrait or landscape; the
  gap's width, rounded corners, and the background colour.
- **Undo and redo** for every step.
- **Out**: save as a JPEG of 1080 to 4096 pixels on its long side (Settings),
  named by the moment it was saved, into `Pictures/PhotoCollage` or a folder
  picked in Settings; or share it.
- **Draft**: save the collage by hand and open it again later; the app keeps
  its own copy of every photo in it.
- English, Russian and Ukrainian; light and dark themes with accent colours.

No permissions besides the updater's: photos come in through the picker or
Share, saving into Pictures needs none, and another folder is granted once
through the system's folder picker.

## Build

The numbered scripts build, install and release it; `AGENTS.md` says how they
fit together. `./00-MakeAll.sh` is the usual day.

## License

GPL v3, see [LICENSE](LICENSE).
