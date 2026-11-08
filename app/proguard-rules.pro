# Priv.java calls Shizuku.newProcess by reflection, so R8 must not remove or rename it.
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
