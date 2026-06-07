# Keep rules applied only to the androidTest instrumentation APK.
# androidx.test references errorprone annotations absent from the runtime
# classpath; suppress so R8 minify of the test APK doesn't fail.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
