-keep class com.octelium.client.lib.Native { native <methods>; }
-keep class com.octelium.client.lib.NativeResult { <init>(int, long, long, java.lang.String); }
-keep class com.octelium.client.lib.NativeNetworkConfig { <init>(...); }
-keep class com.octelium.client.lib.NativeDNSConfig { <init>(...); }
-keep class com.octelium.client.lib.NativeConfig { <fields>; }
-keep class com.octelium.client.lib.NativeDualStackNetwork { <fields>; }
-keep class com.octelium.client.lib.NativeGateway { <fields>; }
-keep class com.octelium.client.lib.NativeGatewayWireGuard { <fields>; }
-keep class com.octelium.client.lib.NativeGatewayQUICV0 { <fields>; }
-keep interface com.octelium.client.lib.NativeCallbacks { *; }
-keep class * implements com.octelium.client.lib.NativeCallbacks { *; }

-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

-dontwarn javax.naming.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.re2j.**
-dontwarn io.grpc.okhttp.internal.**
-dontwarn javax.annotation.**
