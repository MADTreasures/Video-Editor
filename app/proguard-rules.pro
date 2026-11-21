# The libraries used here (Compose, Media3, coroutines) ship their own keep rules.

# Unused code is still removed, but names stay readable so that stack traces in logcat can be
# understood without a mapping file. There is nothing to hide in an open-source app.
-dontobfuscate
