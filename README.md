# PhotoCollage

**Photo collages on your phone: pick photos, pick a layout, move the borders,
save or share.**

![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

| Collage | Cell | Settings |
|---|---|---|
| ![Three photos, one tall beside two, with the wrench menu open: layout, format, gap, corners, background](docs/screens/scr1.jpg) | ![A selected cell with the pencil menu open: replace, swap, turn, into columns, into rows, clear, remove cell](docs/screens/scr2.jpg) | ![Settings: theme, colour, language, update check, turning the collage, save folder](docs/screens/scr3.jpg) |

## Features

- **Photos in**: from the app's own picker, folder by folder — as pictures
  with their names or as a list of names — several at once in the order
  tapped; from the system's picker under Other apps — which lists
  every app that hands out images, PhotoBox among them; or shared to
  PhotoCollage from another app.
- **Layouts**: ready-made ones for 1 to 7 photos, turned and mirrored in the
  Layout dialog; any cell can be cut in two, across, down or diagonally, or
  removed, up to 16 cells. A cell with a photo cut diagonally has the photo on
  both sides, each framed on its own.
- **Borders**: drag a gap between cells to move it, slant and all; tap it for
  a dot on each end and drag a dot to tilt it, as far as corner to corner.
- **Overlays** (wrench): up to 6 photos in shapes of their own over the
  cells — circle, square, triangle, diamond, hexagon, star, heart. Fingers
  move the photo in one; tapped, the overlay itself moves, pinches and turns,
  and the dot on its corner sizes and turns it; the pencil changes its photo
  and shape, moves it up or down the stack, or removes it. The gap rings it
  in the background's colour, the corners round it, and Blend softens its
  edge. Hide overlays (wrench) leaves only their outlines and lets the cells
  under them be worked on; a saved image still has them.
- **Photo in its cell**: drag to move it, pinch to zoom, turn two fingers to
  tilt it to any angle, turn it a quarter at a time — which also straightens a
  tilt — replace or clear it; Photo info says where it came from — folder and
  name where the app that gave it tells them — and its size in pixels and
  megapixels.
- **Swap**: long-press a photo and drop it on another cell, or select a cell,
  tap Swap and tap the other one.
- **Format**: portrait or landscape, the shape — 1:1, 4:5, 3:4, 2:3, 9:16 or
  A4 — and the size it is saved at; or a preset that sets all three at once:
  Instagram, Stories, HD, Full HD, 2K, 4K, a 10×15 print, A4.
- **Look**: the gap's width, rounded corners, blend — each photo fading
  into its neighbours across the seam instead of a hard line — and the
  background colour.
- **Tools**: layout, format, gap, corners, blend and background under the
  wrench at the top; while a cell is selected the same button is a pencil
  and holds the cell's own tools.
- **Undo and redo** for every step.
- **Turning**: the collage turns with the phone by the sensor even with
  auto-rotate off, while the editor stays upright — or as the system says, or
  never (Settings).
- **Out**: save as a JPEG under a name of your own — the moment, unless
  changed — into `Pictures/PhotoCollage` or a folder picked in Settings; or
  share it.
- **Properties** (⋮): the way round, shape, size, preset, and how many cells
  and photos.
- **Kept**: the collage being edited is there again at the next start, even
  after the system closed the app; a new collage — from the menu or photos
  shared in — asks first, and can save the old one as its draft.
- **Drafts**: save the collage by hand (under it, or ⋮) as a draft and come back to it
  later; saved again, it writes over its own draft, and Save as new draft (⋮) keeps a
  copy apart. Drafts (⋮) shows them all as pictures, the latest first; a tap shows one
  large, to open — asking first over a collage that holds work — or delete. The app
  keeps its own copy of every photo in them.
- English, Russian and Ukrainian; light and dark themes with accent colours.

One permission besides the updater's: reading photos, for the app's own
picker — refused, the system's picker is used instead. Share needs none,
saving into Pictures needs none, and another folder is granted once through
the system's folder picker.

## Build

The numbered scripts build, install and release it; `AGENTS.md` says how they
fit together. `./00-MakeAll.sh` is the usual day.

## License

GPL v3, see [LICENSE](LICENSE).
