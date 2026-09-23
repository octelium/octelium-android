-keep class com.octelium.client.lib.Native { native <methods>; }
-keep class com.octelium.client.lib.NativeResult { <init>(int, long, long, byte[]); }
-keep interface com.octelium.client.lib.NativeCallbacks { *; }
-keep class * implements com.octelium.client.lib.NativeCallbacks { *; }

-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

-dontwarn javax.naming.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.re2j.**
-dontwarn io.grpc.okhttp.internal.**
-dontwarn javax.annotation.**
