# Méthodes appelées depuis le JavaScript de la page : ne pas renommer ni supprimer.
-keepclassmembers class com.wmopener.AutoOpener$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
