-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class com.earthwatch.face.** { *; }

-keepclassmembers class com.earthwatch.face.** {
    public <init>(...);
}

-keep class androidx.wear.watchface.** { *; }
-keep interface androidx.wear.watchface.** { *; }

-keepclassmembers class * {
    public void on*(...);
}

-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
