# App icon

The launcher icon is a green hourglass with peach sand on a dark navy
background, in the Catppuccin Macchiato palette (base `#24273a`, green
`#a6da95`, peach `#f5a97f`). This is artwork option C9; it replaced an
earlier version with the same shapes in mauve and green.

## Source

- `zerohour-icon.svg` (this folder): the master artwork, a 512×512 square.
  Its shapes stay inside the middle of the square, so Android's icon masks
  never cut them.
- `fastlane/metadata/android/en-US/images/icon.png`: the 512×512 store icon,
  a PNG rendered from the SVG and exported with it, copied unchanged. It is
  32-bit RGBA with every pixel fully opaque, as Google Play asks
  (sha256 `6243ec6ffa0dbf86e137c0934ab2976b74e38b55952b901cf3552af2972b19c6`).

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
- `app/src/debug/res/`: debug builds only, the same layers with the
  hourglass at 84% inside a thin ring in Catppuccin Macchiato blue
  (`#8aadf4`, a colour the icon does not use), so the `.debug` app is easy to
  tell from the release app. The ring is drawn in the monochrome layer too,
  so it still shows with themed icons.

If the SVG changes, copy its path data into both drawables again (and the
debug copies), and its colours into `ic_launcher_colors.xml`.

## Licence

The artwork was made for zerohour and is licensed under the MIT License, the
same licence as this repository (see `LICENSE` in the repository root).
