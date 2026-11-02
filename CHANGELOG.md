# CHANGELOG
> Newest entries on top.
> N=new feature, E=error fix, F=fine-tune, R=refactor, I=infrastructure

## Unreleased
- N: The Format dialog picks the way round, the shape, the size it is saved at and a preset — Instagram, Stories, HD to 4K, prints — each a drop-down, and the size belongs to the collage instead of Settings
- N: Save goes into a folder picked in Settings, or Pictures/PhotoCollage, as PhotoCollage-<date>_<time>.jpg
- F: A close button at the top left leaves the app
- F: Cancel and the other ways out of a dialog are filled with the accent colour, not a pale wash of it
- F: A black or white line round the collage on screen shows where it ends against a pale window
- E: The collage no longer paints over the rest of the screen, so the top bar and the Layout dialog's counts and previews show again
- F: 1920 pixels is offered as the saved size, so 16:9 comes out as Full HD
- F: Portrait or landscape is picked at the top of the Format dialog instead of by a tool of its own, and each format shows the size it is saved at
- E: Dragging the gap or corner slider from the screen's left edge no longer starts the system back gesture and closes the app
- R: Lint findings cleared: the app name in every locale, the KTX bitmap and canvas helpers, no always-false check in the renderer
- N: Collage editor: photos from the picker or Share, ready-made layouts, cells cut and removed, borders dragged, photos moved, zoomed, turned and swapped, format, gap, corners and background, undo and redo, saved to the gallery or shared, one draft saved by hand
- I: Build set from the XXX template with PhotoBox's Gradle setup, the shared updater and About dialog, and 23-ToUpdate.sh
