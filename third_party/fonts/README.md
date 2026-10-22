# Bundled font

The app draws all of its text, icons and borders with one bundled font, so the
terminal look is the same on every phone:

    app/src/main/res/font/jetbrainsmono_nerd_mono_regular.ttf
    app/src/main/res/font/jetbrainsmono_nerd_mono_bold.ttf

These are the unmodified **JetBrainsMono Nerd Font Mono** Regular and Bold
fonts (`JetBrainsMonoNerdFontMono-Regular.ttf` and `-Bold.ttf`), copied byte
for byte from the official Nerd Fonts v3.5.1 release archive:

    https://github.com/ryanoasis/nerd-fonts/releases/download/v3.5.1/JetBrainsMono.zip

Nerd Fonts patches icon sets into JetBrains Mono 2.304. The *Mono* variant
squeezes every icon into exactly one character cell, which keeps columns and
box borders lined up (the same reason the terminal app recommends it).

## Licences

- The font itself (JetBrains Mono, as patched by Nerd Fonts) is licensed under
  the SIL Open Font License 1.1. The licence text from the release archive is
  in `OFL.txt` next to this file.
- The only icons zerohour shows are Font Awesome 6.5.1 glyphs (the `nf-fa-*`
  names in `zerohour-core`'s `icons.rs`). Font Awesome's icons are licensed
  under CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/), by Fonticons,
  Inc. (https://fontawesome.com). The font also carries the other Nerd Fonts
  icon sets, which the app never displays.

No other fonts or icon sets are bundled.

## Checksums

SHA-256 of the files, so a reviewer can compare them with the release:

    fab782a66f7d3019da64f6572db9fc5d3a4bcb19f9fa13e2d8a62e3693d6396e  JetBrainsMono.zip (the release archive)
    f2a5ea6cfab397445ffab00c0370927b66d61e560a05db5db271b42006381c1a  jetbrainsmono_nerd_mono_regular.ttf
    bfcf9a917276ffc058867d87cbc8a5b2f1ab0f4b710e9170dc02763ccb80bd4b  jetbrainsmono_nerd_mono_bold.ttf

(Android resource names must be lower case letters, digits and underscores,
which is why the files are renamed.)
