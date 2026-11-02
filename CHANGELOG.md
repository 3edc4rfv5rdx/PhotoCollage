# CHANGELOG
> Newest entries on top.
> N=new feature, E=error fix, F=fine-tune, R=refactor, I=infrastructure

## Unreleased
- E: The layouts for three photos are four that look different — one over two, one beside two, three across, three down — instead of two pairs alike
- N: A photo tilts to any angle as two fingers turn on it, still covering its whole cell; a quarter turn straightens it again
- E: An empty cell can be selected, and so removed: a tap selects it as it opens the picker, and it stays selected if no photo is picked
- F: The gap, corner and background strips close with a ✓ on the inverse colour instead of ×
- F: The Format dialog has Cancel, which puts back the format it opened on
- N: The collage's settings sit under a wrench at the top, and a selected cell's tools under the same button as a pencil, so nothing under the collage hides off the screen or makes it jump
- F: Undo and redo sit at the right of the top bar, beside the ⋮ menu
- N: Properties in the ⋮ menu show the collage's way round, shape, size, preset, cells and photos
- N: Save asks for the file name, the moment by default, and says the folder and name it saved under
- N: The collage turns with the phone by the sensor even with auto-rotate off, while the editor stays upright; Settings can make it follow the system or never turn
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
