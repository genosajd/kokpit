# Asystent AI (anthropic-java): OpenTelemetry odwołuje się do adnotacji AutoValue, której nie ma w APK.
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.auto.value.AutoValue$*

# Vosk (hasło „OK BMW”): JNA woła klasy przez refleksję
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
