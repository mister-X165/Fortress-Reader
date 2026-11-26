# Fortress Reader — release (R8) rules.
#
# Core libraries (Hilt, Room, kotlinx.coroutines, androidx.*) ship their own
# consumer rules inside their AARs; the rules below cover the remaining
# edge cases so `minifyEnabled` builds stay stable.

# Keep crash reports readable: line numbers in production stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx-coroutines: keep debug metadata so stack traces name the coroutine.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# javax.crypto / java.security: JCA provider lookups by name.
-dontwarn javax.naming.**

# Room: entity/DAO classes are accessed via generated implementations.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { <fields>; }

# The vault payload classes must not be repackaged (identified by name in
# error reports only; no reflection is used at runtime).
-keepnames class com.fortress.reader.security.** { *; }

# Hilt generated components live in the dagger.hilt package.
-keep class dagger.hilt.internal.** { *; }
-dontwarn dagger.hilt.**
