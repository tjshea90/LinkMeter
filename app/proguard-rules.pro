# ShellMain is started by app_process from the APK, so nothing in the app refers to it.
-keep class com.tj.linkmeter.ShellMain { *; }
-keep class com.tj.linkmeter.ShellMain$* { *; }

# Priv.java calls Shizuku.newProcess by reflection, so R8 must not remove or rename it.
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
