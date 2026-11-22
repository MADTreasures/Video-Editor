# Video Editor

Eine kleine Android-App zum Schneiden von Videos. Sie braucht kein Konto und keine
Internet-Berechtigung; alles passiert auf dem Gerät.

## Was die App kann

- **Videos öffnen** aus der Galerie oder über den Dateimanager, auch per „Öffnen mit“ und
  „Teilen“ aus anderen Apps. Gelesen werden MP4, MOV, MKV, WebM, 3GP und weitere Formate,
  soweit das Gerät den jeweiligen Codec abspielen kann.
- **Kürzen:** Anfang und Ende eines Clips über die weißen Griffe in der Timeline ziehen.
- **Teilen und Löschen:** Clip an der Abspielposition teilen und einzelne Teile entfernen, um
  zum Beispiel ein Stück aus der Mitte herauszuschneiden.
- **Zusammenfügen:** weitere Videos anhängen und die Reihenfolge der Clips ändern.
- **Drehen** in 90°-Schritten, **Ton aus** und **Tempo** von 0,25x bis 4x für das ganze Video.
- **Rückgängig / Wiederholen** für alle Schritte.
- **Export als MP4** (H.264 oder H.265, Ton als AAC) nach `Movies/VideoEditor`, wahlweise
  kleiner skaliert (1080p, 720p, 480p) und in drei Qualitätsstufen. Der Export läuft weiter,
  wenn man die App verlässt.

Wenn nur das Ende gekürzt oder gedreht wurde, kopiert die App Bild und Ton unverändert in die
neue Datei. Das geht sehr schnell und ohne Qualitätsverlust. In allen anderen Fällen wird das
Video neu kodiert.

## Bedienung der Timeline

Der Filmstreifen und die Zeitskala stehen still. Bewegt wird immer nur das, was man anfasst;
es leuchtet beim Berühren auf.

| Geste | Wirkung |
| --- | --- |
| Abspielkopf ziehen (weißer Balken, am Punkt oder an der Linie) | nur der Abspielkopf folgt dem Finger; Vorschau und Zeitanzeige laufen mit |
| Auf der Zeitskala ziehen | der Abspielkopf kommt unter den Finger und folgt ihm |
| Tippen | Abspielkopf dorthin setzen |
| Linken oder rechten Griff ziehen | Anfang oder Ende des Clips kürzen; nur diese Kante bewegt sich, die andere Kante und die Bilder bleiben stehen |
| Filmstreifen ziehen | Ausschnitt verschieben, sobald das Projekt nach dem Vergrößern breiter ist als die Anzeige |
| Zwei Finger auseinander / zusammen | Timeline vergrößern / verkleinern |

Die Griffe sitzen am Clip unter dem Abspielkopf (weiß umrahmt). Beim Ziehen eines Griffs zeigt
die Vorschau das Bild an der Schnittkante, danach wieder das Bild unter dem Abspielkopf. Der
Abspielkopf bleibt beim Kürzen auf seinem Bild stehen; nur wenn dieses Bild weggeschnitten
wird, rückt er an die neue Kante.

Teilen, Löschen, Früher und Später wirken auf den Clip unter dem Abspielkopf. Tempo, Ton und
Drehen gelten für das ganze Video.

## Berechtigungen

- **Benachrichtigungen** (ab Android 13, optional): Die App fragt vor dem ersten Export danach.
  Damit zeigt sie den Fortschritt an und meldet, wenn das Video fertig ist, auch wenn man
  inzwischen eine andere App benutzt. Ohne die Erlaubnis läuft der Export genauso.
- **Speicher** nur bis Android 9, um in den gemeinsamen Ordner `Movies` schreiben zu dürfen.
- Kein Internetzugriff, kein Zugriff auf Kamera, Mikrofon oder Standort. Die App sieht nur die
  Videos, die man ihr über die Auswahl, „Öffnen mit“ oder „Teilen“ gibt.

## Installieren

Die fertige APK auf das Handy laden und antippen. Android fragt beim ersten Mal, ob Apps aus
dieser Quelle installiert werden dürfen. Die App läuft ab Android 8.0.

## Selbst bauen

Benötigt werden ein JDK 17 oder neuer und das Android SDK (`ANDROID_HOME` gesetzt oder
`sdk.dir` in `local.properties`).

```sh
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest # Tests für das Schnittmodell und die Exportplanung
```

Alle Builds werden mit dem Schlüssel in `keystore/` signiert, damit sich eine neuere APK über
eine ältere installieren lässt. Mehr dazu in [`keystore/README.md`](keystore/README.md).

## Aufbau

| Pfad (unter `app/src/main/java/.../videoeditor`) | Inhalt |
| --- | --- |
| `model/` | Projekt, Clips, Schnittoperationen und Exportplanung; reines Kotlin, mit Unit-Tests |
| `media/` | Videodateien auslesen, Vorschaubilder für die Timeline |
| `export/` | Export mit Media3 Transformer, Speichern in der Galerie, Vordergrunddienst |
| `ui/` | Oberfläche in Jetpack Compose: Startbildschirm, Editor, Timeline, Dialoge |
| `EditorViewModel.kt` | Zustand des Editors, Steuerung des Vorschau-Players, Rückgängig-Verlauf |

## Grenzen

- Die Schnitte werden nicht als Projekt gespeichert. Wer die App schließt, ohne zu exportieren,
  fängt neu an.
- Drehen, Ton und Tempo gelten für das ganze Video, nicht für einzelne Clips.
- Neu kodierte Videos werden in normalem Kontrastumfang (SDR) gespeichert, auch wenn die
  Quelle HDR ist.
- Gespeichert wird immer als MP4.
