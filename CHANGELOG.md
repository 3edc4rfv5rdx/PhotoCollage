# CHANGELOG
> Newest entries on top.
> N=new feature, E=error fix, F=fine-tune, R=refactor, I=infrastructure

## Unreleased
- I: The test of the three-photo layouts measures cells before rounding them
- F: Save draft is off while the draft already holds the collage
- F: Save draft is back under the collage
- F: The gap, corner and blend sliders show how far along they are as a percent
- E: With no gap, no hairline of the background shows between square-cornered cells
- F: New collage leaves an empty collage with its Choose photos button instead of opening the picker
- N: Blend in the wrench menu fades each photo into its neighbours across the seam instead of a hard line
- F: The background dialog shows the colour in force as a pill beside its title and rings the pick in the square
- F: The background is picked from a square of 144 colours in a dialog of its own instead of a strip of 12
- F: The Layout dialog picks the photo count from a drop-down, 1 to 7, and offers no layout that is another turned or mirrored
- N: The Layout dialog turns and mirrors its layouts and offers more: all in a line, 1-3-1 and the like, 2-1-2 and the like
- F: The Layout dialog draws its layouts' cells in the accent colour instead of pale grey
- R: The dialog button's modifier is its first optional parameter, as lint asks
- E: A photo that once failed to show in the editor is tried again instead of staying grey for the session
- E: A photo turned a quarter stays turned when another layout is picked
- E: A photo import that fails on the way ends with a message and never leaves the editor waiting
- E: A save or share that fails in the system ends with a message instead of closing the app
- E: The saved message names the file as the system numbered it when the name was taken
- E: An undo in the middle of a drag no longer fills the history with a step for every frame of the rest of it
- E: A narrow cell can be tapped and its photo moved, and a cut, a dragged border or the gap no longer shrinks a cell to nothing
- E: Photos shared into the app as it starts are no longer deleted by the start's clean-up
- E: A saved or shared image never has an empty cell where a photo was: a photo short of memory is drawn smaller, and an unreadable one stops the save with a message
- E: The collage being edited comes back at the next start, and a new collage asks before it replaces one that holds work
- I: Lint no longer warns that targetSdk is behind the newest Android: raising it is a decision for every project at once
## v0.1.26 (2026-11-02)
- F: A floppy-disk button in the top bar saves the image, and Save draft sits in the free strip under the collage
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
