package pl.lawinski.radiolauncher

/*
 * Rodzaj radia. Kokpit działa na dowolnym radiu z Androidem; funkcje zależne od oprogramowania Witstek
 * (dane auta z CAN, telefon przez moduł Bluetooth radia, głośność wyjścia radia, ekran auta, przyciski
 * kierownicy, ustawienia Witstek) włączają się tylko tam. Wykrywanie po właściwości systemu
 * „ro.wits.machine.id” (np. YA32); w Ustawienia → Radio i system można rodzaj wymusić.
 * Reszta kodu pyta wyłącznie o Radio.witstek – nowa funkcja zależna od radia też ma tak robić.
 */
object Radio {
    /** oznaczenie radia Witstek (np. „YA32”) albo pusty tekst, gdy to inne radio */
    val model: String by lazy {
        runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "ro.wits.machine.id") as String
        }.getOrNull().orEmpty().trim()
    }

    val detected: Boolean get() = model.isNotEmpty()

    /** Stałe do końca procesu – zmiana rodzaju w Ustawieniach uruchamia Kokpit od nowa. */
    val witstek: Boolean by lazy {
        when (Prefs.radioMode) { "witstek" -> true; "inne" -> false; else -> detected }
    }

    fun describe(): String = when (Prefs.radioMode) {
        "witstek" -> "Wymuszone: Witstek · dotknij, by zmienić"
        "inne" -> "Wymuszone: inne radio · dotknij, by zmienić"
        else -> if (detected) "Auto: Witstek $model · dotknij, by zmienić" else "Auto: inne radio · dotknij, by zmienić"
    }

    fun nextMode(): String = when (Prefs.radioMode) { "" -> "witstek"; "witstek" -> "inne"; else -> "" }
}
