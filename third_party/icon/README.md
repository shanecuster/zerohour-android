# App icon

The launcher icon is a purple hourglass with green sand on a dark navy
background, in the Catppuccin Macchiato palette (base `#24273a`, mauve
`#c6a0f6`, green `#a6da95`).

## Source

- `zerohour-icon.svg` (this folder): the master artwork, a 512×512 square.
  Its shapes stay inside the middle of the square, so Android's icon masks
  never cut them.
- `fastlane/metadata/android/en-US/images/icon.png`: the 512×512 store icon.
  The original was supplied as a WebP image; it was converted to PNG (with an
  alpha channel, as Google Play asks) without changing its pixels.

The Android launcher icon is drawn from the SVG, not from the PNG:

- `app/src/main/res/drawable/ic_launcher_foreground.xml`: the SVG's two paths
  (the outline and the sand; not its background rectangle), with round line
  caps and joins, scaled to 90% around the centre so they sit inside the 66dp
  safe zone of the 108dp adaptive-icon canvas.
- `app/src/main/res/values/ic_launcher_colors.xml`: the background colour and
  the two shape colours.
- `app/src/main/res/drawable/ic_launcher_monochrome.xml`: the same shapes in
  one colour, for themed icons (Android 13+).
- `app/src/main/res/mipmap-anydpi/ic_launcher.xml` and `ic_launcher_round.xml`:
  the adaptive icon that stacks the three layers.
- `app/src/debug/res/`: debug builds only, the same layers plus a small peach
  badge, so the `.debug` app is easy to tell from the release app.

If the SVG changes, copy its path data into both drawables again (and the
debug copies).

## Licence

The artwork was made for zerohour and is licensed under the MIT License, the
same licence as this repository (see `LICENSE` in the repository root).
