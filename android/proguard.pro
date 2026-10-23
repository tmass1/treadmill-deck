# R8 rules for C1250 Deck. Shrinks unused library code; keeps names readable for debugging.
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Android entry points
-keep class com.massaro.c1250deck.MainActivity { *; }
-keep class com.massaro.c1250deck.UpdateReceiver { *; }
-keep class com.massaro.c1250deck.BootReceiver { *; }

# Methods the web page calls through window.DeckNative
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }

# OkHttp 3 / Okio: optional TLS providers and build-time annotations
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn javax.annotation.**
-dontwarn okhttp3.internal.platform.**
-keep class okhttp3.internal.platform.** { *; }
