# Signing key for hand-installed builds

`sideload.jks` signs every APK built from this repository (store password, key alias and key
password are all `videoeditor`, see `app/build.gradle.kts`).

Android only installs an update over an existing app if both are signed with the same key.
Keeping one key in the repository means an APK built later, on any machine, installs over the
one already on the phone without uninstalling first.

The key is deliberately not secret, so it proves nothing about who built an APK. Do not use it
to publish the app in a store; create a private key for that.
