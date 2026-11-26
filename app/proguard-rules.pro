# Keep R8 shrinking and optimization enabled, but preserve original names.
-dontobfuscate

# Preserve JNI method names
-keep class fr.local.convertisseuraudio.RnnoiseNative { *; }
