# Keep rules for the glasses listener app.
# R8 default for debug is otherwise too aggressive for reflection-heavy code.

# ----------------------------------------------------------------------------
# Android entry points (services, activities, receivers, app class). Manifest
# registration already flags these as entry points for R8, but explicit keeps
# guard against accidental removal of constructors / fields.
# ----------------------------------------------------------------------------
-keep class com.repository.glasses.listener.GlassesListenerApp { *; }
-keep class com.repository.glasses.listener.MainActivity { *; }
-keep class com.repository.glasses.listener.service.** { *; }
-keep class com.repository.glasses.listener.boot.** { *; }

# Remote input (watch bezel today, any future InputSource tomorrow). The
# instrumented tests drive these directly and share the target app's dex, so R8
# inlining a trivial accessor or dropping a member the app never calls from
# Kotlin source makes them fail with NoSuchMethodError -- the same problem the
# Kotlin stdlib keep below documents, observed here for
# RemoteInputBridgeClient.sinkBinder. These are also the classes a new input
# device is written against, so a stable shape is worth more than the bytes.
-keep class com.repository.glasses.listener.input.remote.** { *; }
-keep class com.repository.glasses.listener.ui.** { *; }

# ----------------------------------------------------------------------------
# Wakeword classes. AcdNativeDetector is JNI-bound (libacd_native.so calls
# back into the Kotlin SpeechCallback via reflection); keep the whole subtree
# so the JVM-side method ids resolve.
# ----------------------------------------------------------------------------
-keep class com.repository.glasses.listener.wakeword.** { *; }
-keep class com.repository.glasses.listener.capture.** { *; }
-keep class com.repository.glasses.listener.bt.** { *; }

# ----------------------------------------------------------------------------
# AIDL generated classes (service bindings to Rokid MasterAssistService, our
# own FileSync IPC, BtManager IPC). Keep all AIDL Stubs + callback interfaces.
# ----------------------------------------------------------------------------
-keep class ** extends android.os.IInterface { *; }
-keep class ** extends android.os.Binder { *; }
-keep class com.repository.glasses.** implements android.os.IInterface { *; }

# ----------------------------------------------------------------------------
# CXR-S bridge SDK uses reflection internally; the jar doesn't ship consumer
# rules so keep its public API surface conservatively.
# ----------------------------------------------------------------------------
-keep class com.rokid.** { *; }
-dontwarn com.rokid.**

# ----------------------------------------------------------------------------
# Kotlin metadata -- required for Kotlin reflection to resolve class names,
# property names, sealed class hierarchies, etc.
# ----------------------------------------------------------------------------
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# ----------------------------------------------------------------------------
# HiddenApiBypass already ships consumer rules via org.lsposed; no explicit
# keeps needed.
#
# ONNX Runtime (onnxruntime-android-qnn 1.20.0) does NOT ship consumer
# proguard rules. Its native side (libonnxruntime4j_jni.so) constructs Java
# classes like ai.onnxruntime.TensorInfo via JNI FindClass+GetMethodID+
# NewObject. R8 cannot see those call sites, so it strips the 3-arg
# TensorInfo(long[], String[], int) constructor and crashes at runtime with
# NoSuchMethodError: <init>([J[Ljava/lang/String;I)V inside OrtSession.run.
# Keep the whole ai.onnxruntime.** surface (classes, fields, constructors,
# methods) so JNI reflection can resolve every symbol the native lib needs.
# ----------------------------------------------------------------------------
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ----------------------------------------------------------------------------
# ML Kit face detection similarly relies on reflection into its SDK classes.
# ----------------------------------------------------------------------------

# ----------------------------------------------------------------------------
# Don't fail the build on unused references to classes that do exist at
# runtime but aren't on our classpath (vendor/system frameworks).
# ----------------------------------------------------------------------------
-dontwarn android.hardware.soundtrigger.**
-dontwarn android.media.soundtrigger.**
-dontwarn android.media.permission.**
-dontwarn android.service.voice.**
-dontwarn dalvik.system.VMRuntime

# androidTest (androidx.test) references errorprone annotations not on the
# runtime classpath. Only affects the instrumentation APK, not the shipped app.
-dontwarn com.google.errorprone.annotations.**

# Keep the Kotlin stdlib intact. The androidTest instrumentation APK shares this
# (the target app's) dex at runtime, so R8 stripping stdlib methods the app
# itself never calls (Regex.containsMatchIn, Sequence.filter, String.split, etc.)
# makes instrumented tests fail with NoSuchMethodError. Keeping the stdlib also
# protects reflection-driven call sites in the app.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**

# ----------------------------------------------------------------------------
# Keep source file info so stack traces remain readable. Debug build already
# ships debuggable; this is mostly insurance for line-number mapping.
# ----------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
