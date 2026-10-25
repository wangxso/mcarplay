# App Icon

The mcarplay mark combines a green gradient, a white C-shaped connection ring,
and a geometric Mi symbol. It is drawn as vector paths, with no downloaded
Apple or Xiaomi image file embedded in the application.

- `mcarplay-icon.svg`: editable presentation version of the icon.
- `mcarplay-icon.png`: 512 x 512 preview for the READMEs and repository page.
- `app/src/main/res/drawable/ic_launcher_background.xml`: Android gradient layer.
- `app/src/main/res/drawable/ic_launcher_foreground.xml`: Android foreground mark.

Android applies its own launcher mask to the adaptive icon. The presentation
SVG shows the central 72dp viewport with rounded corners; the app's foreground
fits within the adaptive icon safe zone. Android 13+ also receives a monochrome
version for themed launcher icons.

Mi is a Xiaomi brand identifier. This experimental project's icon does not
indicate affiliation or certification; third-party marks are not relicensed by
the project's MIT source license.
