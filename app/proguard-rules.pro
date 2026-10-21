# kotlinx-serialization: keep generated serializers for @Serializable classes (nav routes, JSON, stored profiles).
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
