package io.github.conchad.smartgarage.feature.vehicles

import java.time.LocalDate
import java.util.Locale

/**
 * The built-in makes, models and years the Add vehicle form suggests as the user types (D-A62), offline. North
 * American market first, plus the major global brands. Make and model stay free text: a make or model that isn't
 * here saves unchanged, it just gets no suggestions.
 *
 * Matching ([suggestMakes], [suggestModels]) works like the insurer list (D-A50): case-insensitive, punctuation
 * ignored ("f150" finds "F-150", "mercedes benz" finds "Mercedes-Benz"), name-start matches first, then matches at
 * the start of a later word ("rover" finds "Land Rover"), alphabetical within each. Pure Kotlin, unit-tested.
 */
object VehicleCatalog {
    /** The oldest model year offered; older cars can still be typed. */
    const val OLDEST_YEAR = 1950

    // Declared before the lists: the object initializes in order and [makes] already folds names.
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Make to its popular current and recent-past models, spelled and cased as the brands do. */
    val models: Map<String, List<String>> = linkedMapOf(
        "Acura" to listOf("CL", "ILX", "Integra", "MDX", "NSX", "RDX", "RL", "RLX", "RSX", "TL", "TLX", "TSX", "ZDX"),
        "Alfa Romeo" to listOf("4C", "Giulia", "Giulietta", "Stelvio", "Tonale"),
        "Aston Martin" to listOf("DB11", "DB12", "DBS", "DBX", "Vantage"),
        "Audi" to listOf(
            "A3", "A4", "A4 allroad", "A5", "A6", "A6 allroad", "A7", "A8", "e-tron", "e-tron GT", "Q3", "Q4 e-tron",
            "Q5", "Q7", "Q8", "R8", "RS 3", "RS 5", "RS 6", "S3", "S4", "S5", "TT",
        ),
        "Bentley" to listOf("Bentayga", "Continental GT", "Flying Spur", "Mulsanne"),
        "BMW" to listOf(
            "1 Series", "2 Series", "3 Series", "4 Series", "5 Series", "6 Series", "7 Series", "8 Series", "i3", "i4",
            "i5", "i7", "i8", "iX", "M2", "M3", "M4", "M5", "X1", "X2", "X3", "X4", "X5", "X6", "X7", "Z4",
        ),
        "Buick" to listOf("Cascada", "Enclave", "Encore", "Encore GX", "Envision", "Envista", "LaCrosse", "Lucerne", "Regal", "Verano"),
        "BYD" to listOf("Atto 3", "Dolphin", "Han", "Seal", "Tang"),
        "Cadillac" to listOf("ATS", "CT4", "CT5", "CT6", "CTS", "DTS", "Escalade", "Lyriq", "SRX", "STS", "XT4", "XT5", "XT6", "XTS"),
        "Chevrolet" to listOf(
            "Avalanche", "Aveo", "Blazer", "Bolt EV", "Bolt EUV", "Camaro", "Captiva", "Cavalier", "Cobalt", "Colorado",
            "Corvette", "Cruze", "Equinox", "Express", "HHR", "Impala", "Malibu", "Monte Carlo", "Silverado 1500",
            "Silverado 2500HD", "Silverado 3500HD", "Sonic", "Spark", "Suburban", "Tahoe", "Tracker", "Trailblazer",
            "Traverse", "Trax", "Uplander", "Volt",
        ),
        "Chrysler" to listOf("200", "300", "Aspen", "Crossfire", "Pacifica", "PT Cruiser", "Sebring", "Town & Country", "Voyager"),
        "Citroën" to listOf("Berlingo", "C3", "C4", "C5 Aircross", "C5 X"),
        "Cupra" to listOf("Born", "Formentor", "Leon"),
        "Dacia" to listOf("Duster", "Jogger", "Logan", "Sandero", "Spring"),
        "Dodge" to listOf(
            "Avenger", "Caliber", "Challenger", "Charger", "Dakota", "Dart", "Durango", "Grand Caravan", "Hornet",
            "Journey", "Magnum", "Neon", "Nitro", "Ram 1500", "Ram 2500", "Ram 3500", "Stratus", "Viper",
        ),
        "Ferrari" to listOf("296", "488", "812", "F8", "Portofino", "Purosangue", "Roma", "SF90"),
        "Fiat" to listOf("124 Spider", "500", "500e", "500L", "500X", "Panda", "Tipo"),
        "Fisker" to listOf("Karma", "Ocean"),
        "Ford" to listOf(
            "Bronco", "Bronco Sport", "C-Max", "Crown Victoria", "E-Series", "EcoSport", "Edge", "Escape", "Excursion",
            "Expedition", "Explorer", "F-150", "F-150 Lightning", "F-250", "F-350", "Fiesta", "Flex", "Focus", "Fusion",
            "Kuga", "Maverick", "Mustang", "Mustang Mach-E", "Puma", "Ranger", "Taurus", "Transit", "Transit Connect",
        ),
        "Genesis" to listOf("G70", "G80", "G90", "GV60", "GV70", "GV80"),
        "GMC" to listOf("Acadia", "Canyon", "Envoy", "Hummer EV", "Savana", "Sierra 1500", "Sierra 2500HD", "Sierra 3500HD", "Terrain", "Yukon", "Yukon XL"),
        "Honda" to listOf(
            "Accord", "Civic", "Clarity", "CR-V", "CR-Z", "Element", "Fit", "HR-V", "Insight", "Jazz", "Odyssey",
            "Passport", "Pilot", "Prologue", "Ridgeline", "S2000",
        ),
        "Hummer" to listOf("H1", "H2", "H3"),
        "Hyundai" to listOf(
            "Accent", "Azera", "Elantra", "Genesis", "i10", "i20", "i30", "Ioniq", "Ioniq 5", "Ioniq 6", "Kona",
            "Nexo", "Palisade", "Santa Cruz", "Santa Fe", "Sonata", "Tucson", "Veloster", "Venue",
        ),
        "Infiniti" to listOf("EX35", "FX35", "G35", "G37", "JX35", "Q50", "Q60", "Q70", "QX30", "QX50", "QX55", "QX60", "QX80"),
        "Isuzu" to listOf("Ascender", "D-Max", "Rodeo", "Trooper"),
        "Jaguar" to listOf("E-Pace", "F-Pace", "F-Type", "I-Pace", "XE", "XF", "XJ", "XK"),
        "Jeep" to listOf("Cherokee", "Commander", "Compass", "Gladiator", "Grand Cherokee", "Grand Wagoneer", "Liberty", "Patriot", "Renegade", "Wagoneer", "Wrangler"),
        "Kia" to listOf(
            "Carnival", "Ceed", "EV6", "EV9", "Forte", "K5", "Niro", "Optima", "Rio", "Sedona", "Seltos", "Sorento",
            "Soul", "Sportage", "Stinger", "Telluride",
        ),
        "Lamborghini" to listOf("Aventador", "Huracán", "Revuelto", "Urus"),
        "Land Rover" to listOf("Defender", "Discovery", "Discovery Sport", "LR2", "LR3", "LR4", "Range Rover", "Range Rover Evoque", "Range Rover Sport", "Range Rover Velar"),
        "Lexus" to listOf("CT", "ES", "GS", "GX", "IS", "LC", "LS", "LX", "NX", "RC", "RX", "RZ", "TX", "UX"),
        "Lincoln" to listOf("Aviator", "Continental", "Corsair", "MKC", "MKS", "MKT", "MKX", "MKZ", "Nautilus", "Navigator", "Town Car"),
        "Lotus" to listOf("Eletre", "Elise", "Emira", "Evora", "Exige"),
        "Lucid" to listOf("Air", "Gravity"),
        "Maserati" to listOf("Ghibli", "GranTurismo", "Grecale", "Levante", "MC20", "Quattroporte"),
        "Mazda" to listOf("CX-3", "CX-30", "CX-5", "CX-50", "CX-7", "CX-70", "CX-9", "CX-90", "Mazda2", "Mazda3", "Mazda5", "Mazda6", "MX-30", "MX-5 Miata", "RX-8", "Tribute"),
        "McLaren" to listOf("570S", "720S", "750S", "Artura", "GT"),
        "Mercedes-Benz" to listOf(
            "A-Class", "AMG GT", "B-Class", "C-Class", "CLA", "CLS", "E-Class", "EQB", "EQE", "EQS", "G-Class", "GLA",
            "GLB", "GLC", "GLE", "GLS", "S-Class", "SL", "SLK", "Sprinter",
        ),
        "Mercury" to listOf("Grand Marquis", "Mariner", "Milan", "Mountaineer", "Sable"),
        "MINI" to listOf("Clubman", "Cooper", "Countryman", "Paceman"),
        "Mitsubishi" to listOf("Eclipse", "Eclipse Cross", "Galant", "Lancer", "Mirage", "Outlander", "Outlander Sport", "Pajero"),
        "Nissan" to listOf(
            "350Z", "370Z", "Altima", "Ariya", "Armada", "Frontier", "GT-R", "Juke", "Kicks", "Leaf", "Maxima", "Murano",
            "Pathfinder", "Qashqai", "Rogue", "Sentra", "Titan", "Versa", "Xterra", "Z",
        ),
        "Opel" to listOf("Astra", "Corsa", "Grandland", "Insignia", "Mokka", "Zafira"),
        "Peugeot" to listOf("208", "2008", "308", "3008", "508", "5008"),
        "Polestar" to listOf("Polestar 1", "Polestar 2", "Polestar 3", "Polestar 4"),
        "Pontiac" to listOf("Aztek", "Bonneville", "G6", "G8", "Grand Am", "Grand Prix", "Montana", "Solstice", "Sunfire", "Torrent", "Vibe"),
        "Porsche" to listOf("718 Boxster", "718 Cayman", "911", "Cayenne", "Macan", "Panamera", "Taycan"),
        "Ram" to listOf("1500", "1500 Classic", "2500", "3500", "Dakota", "ProMaster", "ProMaster City"),
        "Renault" to listOf("Captur", "Clio", "Austral", "Mégane", "Scénic", "Twingo", "Zoe"),
        "Rivian" to listOf("R1S", "R1T", "R2"),
        "Rolls-Royce" to listOf("Cullinan", "Ghost", "Phantom", "Spectre", "Wraith"),
        "Saab" to listOf("9-3", "9-5"),
        "Saturn" to listOf("Aura", "Ion", "Outlook", "Sky", "Vue"),
        "Scion" to listOf("FR-S", "iA", "iM", "tC", "xB", "xD"),
        "SEAT" to listOf("Arona", "Ateca", "Ibiza", "Leon", "Tarraco"),
        "Škoda" to listOf("Enyaq", "Fabia", "Kamiq", "Karoq", "Kodiaq", "Octavia", "Superb"),
        "smart" to listOf("#1", "#3", "fortwo"),
        "Subaru" to listOf("Ascent", "BRZ", "Crosstrek", "Forester", "Impreza", "Legacy", "Outback", "Solterra", "WRX", "XV"),
        "Suzuki" to listOf("Grand Vitara", "Jimny", "Kizashi", "Swift", "SX4", "Vitara"),
        "Tesla" to listOf("Cybertruck", "Model 3", "Model S", "Model X", "Model Y", "Roadster"),
        "Toyota" to listOf(
            "4Runner", "86", "Avalon", "bZ4X", "C-HR", "Camry", "Corolla", "Corolla Cross", "Crown", "FJ Cruiser",
            "GR Corolla", "GR86", "Grand Highlander", "Highlander", "Land Cruiser", "Matrix", "Mirai", "Prius", "RAV4",
            "Sequoia", "Sienna", "Supra", "Tacoma", "Tundra", "Venza", "Yaris",
        ),
        "Volkswagen" to listOf(
            "Arteon", "Atlas", "Atlas Cross Sport", "Beetle", "CC", "Eos", "Golf", "Golf GTI", "Golf R", "ID.3", "ID.4",
            "ID. Buzz", "Jetta", "Passat", "Polo", "Routan", "T-Roc", "Taos", "Tiguan", "Touareg", "Touran",
        ),
        "Volvo" to listOf("C40", "EX30", "EX90", "S40", "S60", "S80", "S90", "V60", "V90", "XC40", "XC60", "XC70", "XC90"),
    )

    /** The make names, alphabetical (ignoring case and accents is left to the matcher). */
    val makes: List<String> = models.keys.sortedBy { fold(it) }

    private val makeIndex by lazy { Matcher(makes) }
    private val modelIndex: Map<String, Matcher> by lazy { models.mapValues { Matcher(it.value) } }

    /** The makes matching [query], best first; empty for a blank query. */
    fun suggestMakes(query: String): List<String> = makeIndex.suggest(query)

    /** The known make for [make] as typed ("dodge", " Dodge "), or null when it isn't on the list. */
    fun canonicalMake(make: String): String? {
        val key = fold(make)
        if (key.isEmpty()) return null
        return makes.firstOrNull { fold(it) == key }
    }

    /** All models of [make] (as typed; see [canonicalMake]), alphabetical; empty for an unknown make. */
    fun modelsFor(make: String): List<String> = canonicalMake(make)?.let { modelIndex.getValue(it).sorted } ?: emptyList()

    /** The models of [make] matching [query], best first; empty for a blank query or an unknown make. */
    fun suggestModels(make: String, query: String): List<String> =
        canonicalMake(make)?.let { modelIndex.getValue(it).suggest(query) } ?: emptyList()

    /**
     * Whether a [model] already in the form still goes with a newly typed [make]: yes while the make isn't a known
     * one (still being typed, or free text), when the model is blank, or when the make lists it; no once the make is
     * a known make that doesn't (Dodge Charger, make changed to Ford: clear the model).
     */
    fun keepsModel(make: String, model: String): Boolean {
        if (model.isBlank()) return true
        val known = canonicalMake(make) ?: return true
        val key = fold(model)
        return models.getValue(known).any { fold(it) == key }
    }

    /** Model years from next year down to [OLDEST_YEAR], as text. */
    fun years(today: LocalDate = LocalDate.now()): List<String> = ((today.year + 1) downTo OLDEST_YEAR).map { it.toString() }

    /** The [years] starting with the digits of [query], newest first; empty when there are no digits. */
    fun suggestYears(query: String, today: LocalDate = LocalDate.now()): List<String> {
        val digits = query.filter { it.isDigit() }
        if (digits.isEmpty()) return emptyList()
        return years(today).filter { it.startsWith(digits) }
    }

    /** Ranked prefix matching over a fixed list (see the class comment). */
    private class Matcher(names: List<String>) {
        private class Entry(val name: String, val words: List<String>) {
            val joined = words.joinToString(" ")
            val compact = words.joinToString("")
        }

        private val entries = names.map { Entry(it, words(it)) }
        val sorted: List<String> = entries.sortedWith(compareBy({ it.joined }, { it.name })).map { it.name }

        fun suggest(query: String): List<String> {
            val q = words(query)
            if (q.isEmpty()) return emptyList()
            val joined = q.joinToString(" ")
            val compact = q.joinToString("")
            return entries.mapNotNull { entry ->
                val tier = when {
                    entry.joined.startsWith(joined) || entry.compact.startsWith(compact) -> 0
                    (1 until entry.words.size).any { i -> entry.words.drop(i).joinToString(" ").startsWith(joined) } -> 1
                    else -> return@mapNotNull null
                }
                Triple(tier, entry.joined, entry.name)
            }.sortedWith(compareBy({ it.first }, { it.second }, { it.third })).map { it.third }
        }
    }

    /** Lower-case words with punctuation as separators and accents dropped ("Škoda" is "skoda"). */
    private fun words(text: String): List<String> = fold(text).split(' ').filter { it.isNotEmpty() }

    private fun fold(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.ROOT)
            .replace(NON_WORD, " ")
            .trim()
}
