# Video Editor

Eine kleine Android-App zum Schneiden von Videos. Sie braucht kein Konto und keine
Internet-Berechtigung; alles passiert auf dem Gerät.

## Was die App kann

- **Videos öffnen** aus der Galerie oder über den Dateimanager, auch per „Öffnen mit“ und
  „Teilen“ aus anderen Apps. Gelesen werden MP4, MOV, MKV, WebM, 3GP, AVI und weitere Formate,
  soweit das Gerät den jeweiligen Codec abspielen kann.
- **Kürzen:** Anfang und Ende eines Clips über die weißen Griffe in der Timeline ziehen.
- **Teilen und Löschen:** Clip am Abspielkopf teilen und einzelne Teile entfernen, um zum Beispiel
  ein Stück aus der Mitte herauszuschneiden.
- **Zusammenfügen:** weitere Videos anhängen und die Reihenfolge der Clips ändern.
- **Tonspur:** Musik oder andere Audiodateien (MP3, M4A, AAC, WAV, OGG, FLAC …) unter das Video
  legen, verschieben, kürzen, teilen und in der Lautstärke anpassen. Der Originalton bleibt
  erhalten und wird mit der Musik gemischt; mit „Ton aus“ bleibt nur die Musik.
- **Drehen** in 90°-Schritten, **Ton aus** und **Tempo** von 0,25x bis 4x für das ganze Video.
  Hinzugefügte Musik läuft immer in normalem Tempo.
- **Rückgängig / Wiederholen** für alle Schritte.
- **Umwandeln und Exportieren** in das Format deiner Wahl, unabhängig vom Format der Quelle:

  | Format | Bild | Ton | Gut für |
  | --- | --- | --- | --- |
  | MP4 | H.264, H.265, AV1 | AAC | überall |
  | MOV | H.264, H.265 | AAC | Apple-Geräte, iMovie, Final Cut |
  | MKV | H.264, VP9 | AAC | PC-Player wie VLC |
  | WebM | VP9, VP8 | Opus | Web |
  | 3GP | H.264 | AAC | kleine Dateien, ältere Handys |
  | M4A | – | AAC | nur den Ton speichern |
  | OGG | – | Opus | nur den Ton speichern |

  Angeboten werden nur die Codecs, die das Gerät kodieren kann. Scheitert der Encoder eines
  Geräts trotzdem, speichert die App automatisch mit dem nächsten passenden Codec (am Ende
  H.264) und sagt das im Ergebnis. Videos landen in
  `Movies/VideoEditor`, reine Tondateien in `Music/VideoEditor`. Dazu kommen drei Auflösungen
  (1080p, 720p, 480p) und drei Qualitätsstufen. Der Export läuft weiter, wenn man die App
  verlässt, und meldet sich per Benachrichtigung.

Passt der Codec der Quelle in das gewählte Format, übernimmt die App das Bild unverändert. Das
gilt zum Beispiel für H.264 aus MP4 nach MKV, MOV oder 3GP, wenn nur das Ende gekürzt oder
gedreht wurde. Das geht sehr schnell und ohne Qualitätsverlust. In allen anderen Fällen wird neu
kodiert.

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
| Auf die leere Tonspur tippen | Musik oder Audio hinzufügen |
| Auf ein Audiostück tippen | auswählen; es bekommt eigene Griffe zum Kürzen |
| Audiostück ziehen | verschieben; Kanten rasten am Abspielkopf und an Schnitten ein |

Die Griffe sitzen am Clip unter dem Abspielkopf (weiß umrahmt). Beim Ziehen eines Griffs zeigt
die Vorschau das Bild an der Schnittkante, danach wieder das Bild unter dem Abspielkopf. Der
Abspielkopf bleibt beim Kürzen auf seinem Bild stehen; nur wenn dieses Bild weggeschnitten
wird, rückt er an die neue Kante.

Teilen, Löschen, Früher und Später wirken auf den Clip unter dem Abspielkopf. Tempo, Ton und
Drehen gelten für das ganze Video. Ist ein Audiostück ausgewählt, zeigt die Werkzeugleiste
dessen Werkzeuge: Lautstärke, Teilen am Abspielkopf und Löschen; „Fertig“ führt zurück.

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
| `export/` | Export mit Media3 Transformer, eigener Matroska-Schreiber für MKV und WebM, Speichern in der Galerie, Vordergrunddienst |
| `ui/` | Oberfläche in Jetpack Compose: Startbildschirm, Editor, Timeline, Dialoge |
| `EditorViewModel.kt` | Zustand des Editors, Steuerung des Vorschau-Players, Rückgängig-Verlauf |

## Grenzen

- Die Schnitte werden nicht als Projekt gespeichert. Wer die App schließt, ohne zu exportieren,
  fängt neu an.
- Drehen, Ton und Tempo gelten für das ganze Video, nicht für einzelne Clips.
- Neu kodierte Videos werden in normalem Kontrastumfang (SDR) gespeichert, auch wenn die
  Quelle HDR ist.
- MP3 kann die App nicht schreiben, weil Android dafür keinen Encoder mitbringt; für reinen Ton
  gibt es M4A und OGG. AVI und GIF werden ebenfalls nicht geschrieben.
- WebM und OGG brauchen für den Ton einen Opus-Encoder, den Android erst ab Version 10 hat.
  Auf älteren Geräten wird WebM ohne Ton gespeichert; die App weist darauf hin.
- MKV und WebM kennen keine Drehungs-Angabe. Hochkant-Videos werden dafür immer aufrecht neu
  kodiert.
