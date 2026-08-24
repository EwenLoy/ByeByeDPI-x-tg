# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class io.github.romanvht.byedpi.core.ByeDpiProxy { *; }

-keep,allowoptimization class io.github.romanvht.byedpi.core.TProxyService { *; }

# JNA — required for libtgwsproxy.so (Rust MTProto proxy)
-dontwarn com.sun.jna.**
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keepclassmembers class * implements com.sun.jna.Library {
    <methods>;
}
-keep interface io.github.romanvht.byedpi.ewenloy.tgws.ProxyLibrary { *; }
-keep class io.github.romanvht.byedpi.ewenloy.tgws.NativeProxy { *; }
-keep,allowoptimization class io.github.romanvht.byedpi.activities.** { *; }
-keep,allowoptimization class io.github.romanvht.byedpi.services.** { *; }
-keep,allowoptimization class io.github.romanvht.byedpi.receiver.** { *; }

-keep class io.github.romanvht.byedpi.fragments.** {
    <init>();
}

-keep,allowoptimization class io.github.romanvht.byedpi.data.** {
    <fields>;
}

-keepattributes Signature
-keepattributes *Annotation*

-repackageclasses 'ru.romanvht'
-renamesourcefileattribute ''
-keepattributes SourceFile,InnerClasses,EnclosingMethod,Signature,RuntimeVisibleAnnotations,*Annotation*,*Parcelable*
-allowaccessmodification
-overloadaggressively
-optimizationpasses 5
-verbose
-dontusemixedcaseclassnames
-adaptclassstrings
-adaptresourcefilecontents **.xml,**.json
-adaptresourcefilenames **.xml,**.json