# kotlinx.serialization: keep generated serializers for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.bettergecko.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
