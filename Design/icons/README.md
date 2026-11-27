# Genkan's app icon

A noren, the curtain hung at an entrance (genkan): indigo on warm paper, one ring crest split where
it parts, and a column of vertical writing on the page behind. An adaptive icon in three layers, each
an SVG here on the adaptive icon's 108×108 canvas (the middle 66 units always show):

- `noren_background.svg`: the paper colour.
- `noren_foreground.svg`: the curtain, the crest and the page.
- `noren_monochrome.svg`: the same in one colour, for Android's themed icons (its alpha is the mask).

`python3 render.py` (no packages) writes them as vector drawables to
`app/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml`, which
`res/mipmap-anydpi/ic_launcher.xml` puts together. Edit the SVGs and render; never the drawables.
The script takes only what a VectorDrawable draws as it is (rect, line, path; flat colours, strokes,
even-odd fills, opacity) and stops at anything else.
