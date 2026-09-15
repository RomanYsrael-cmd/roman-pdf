# ROMAN PDF keeps its Room entities and PDF/recognition entry points discoverable.
-keep class com.romanysrael.romanpdf.data.** { *; }
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
