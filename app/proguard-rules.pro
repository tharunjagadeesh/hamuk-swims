# kotlinx.serialization: keep generated serializers for our @Serializable classes.
-keepclassmembers class com.hamuk.swimpro.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.hamuk.swimpro.**$$serializer { *; }
# WorkManager instantiates workers reflectively.
-keep class com.hamuk.swimpro.SyncWorker { <init>(...); }
