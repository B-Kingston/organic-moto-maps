package com.organicmoto.geocoder.tool

/**
 * OSM tag -> search-keyword rules, in the spirit of Organic Maps'
 * categories.txt (a small curated English subset for v1). Loaded from
 * poi_keywords.tsv on the classpath.
 *
 * Rule syntax per line:  key[=value] TAB keyword1,keyword2,...
 * A rule with =value matches tags where that key has that value.
 * A bare key rule is a fallback that applies to any feature carrying the
 * key (in addition to any specific rule that matched).
 */
class PoiKeywords private constructor(private val rules: List<Rule>) {

    data class Rule(val key: String, val value: String?, val keywords: List<String>)

    fun keywordsFor(tags: Map<String, String>): List<String> {
        val out = ArrayList<String>(4)
        for (r in rules) {
            if (r.value != null) {
                if (tags[r.key] == r.value) out.addAll(r.keywords)
            } else {
                if (tags.containsKey(r.key)) out.addAll(r.keywords)
            }
        }
        return out
    }

    companion object {
        private const val RESOURCE = "/poi_keywords.tsv"

        /**
         * Loads the rules from poi_keywords.tsv on the classpath. The resource
         * and the embedded copy in this file must be identical (see
         * [checkEmbeddedSync]); a mismatch fails the build loudly instead of
         * silently indexing with a stale keyword set. Falls back to the
         * embedded copy only if the resource is missing entirely.
         */
        fun load(): PoiKeywords {
            val stream = PoiKeywords::class.java.getResourceAsStream(RESOURCE)
                ?: return PoiKeywords(parseRules(EMBEDDED.lineSequence().toList()))
            val resourceRules = stream.use { parseRules(it.bufferedReader().lineSequence().toList()) }
            checkEmbeddedSync(resourceRules)
            return PoiKeywords(resourceRules)
        }

        /**
         * Verifies that poi_keywords.tsv (classpath resource) and the embedded
         * copy in this file are in sync. Throws [IllegalStateException] when
         * they differ, or when either source contains malformed/conflicting
         * rules. Used by `--check-sync`; [load] enforces the same check on
         * every index build.
         */
        fun checkEmbeddedSync() {
            val stream = PoiKeywords::class.java.getResourceAsStream(RESOURCE)
                ?: throw IllegalStateException(
                    "poi_keywords.tsv is missing from the classpath — the embedded fallback " +
                        "in PoiKeywords.kt is in use; restore the resource file"
                )
            val resourceRules = stream.use { parseRules(it.bufferedReader().lineSequence().toList()) }
            checkEmbeddedSync(resourceRules)
        }

        private fun checkEmbeddedSync(resourceRules: List<Rule>) {
            val embeddedRules = parseRules(EMBEDDED.lineSequence().toList())
            if (resourceRules != embeddedRules) {
                throw IllegalStateException(
                    "poi_keywords.tsv and the embedded copy in PoiKeywords.kt are out of sync " +
                        "(resource has ${resourceRules.size} rules, embedded has ${embeddedRules.size}). " +
                        "Apply the change to BOTH files — see AGENTS.md and `--check-sync`."
                )
            }
        }

        /** Parses rule lines; throws on malformed or conflicting rules. */
        private fun parseRules(lines: List<String>): List<Rule> {
            val rules = ArrayList<Rule>(lines.size)
            val seen = HashMap<String, List<String>>()
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val parts = line.split('\t', limit = 2)
                if (parts.size != 2) {
                    throw IllegalStateException("bad poi_keywords rule line (no TAB separator): \"$line\"")
                }
                val left = parts[0].trim()
                val right = parts[1].trim()
                val eq = left.indexOf('=')
                val key = if (eq >= 0) left.substring(0, eq).trim() else left
                val value = if (eq >= 0) left.substring(eq + 1).trim() else null
                if (key.isEmpty()) {
                    throw IllegalStateException("bad poi_keywords rule line (empty key): \"$line\"")
                }
                val keywords = right.split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (keywords.isEmpty()) {
                    throw IllegalStateException("poi_keywords rule has no keywords: \"$line\"")
                }
                val spec = if (value != null) "$key=$value" else key
                val prev = seen.putIfAbsent(spec, keywords)
                if (prev != null && prev != keywords) {
                    throw IllegalStateException(
                        "conflicting poi_keywords rules for \"$spec\": " +
                            "[${prev.joinToString(",")}] vs [${keywords.joinToString(",")}]"
                    )
                }
                rules.add(Rule(key, value, keywords))
            }
            return rules
        }

        /**
         * Copy of poi_keywords.tsv kept in sync with the resource file (the
         * sync is enforced at build time by [load]/[checkEmbeddedSync]). Used
         * only if the classpath resource is missing, so the tool never silently
         * builds an index without category keywords.
         */
        private val EMBEDDED = """
            amenity=fuel	petrol station,fuel,petrol,gas station,gas
            amenity=cafe	cafe,coffee shop,coffee
            amenity=restaurant	restaurant,food
            amenity=fast_food	fast food,takeaway,food
            amenity=pub	pub,hotel
            amenity=bar	bar
            amenity=hospital	hospital
            amenity=clinic	clinic,medical centre,medical center
            amenity=pharmacy	pharmacy,chemist
            amenity=bank	bank
            amenity=atm	atm
            amenity=post_office	post office
            amenity=police	police station,police
            amenity=school	school
            amenity=toilets	toilets,toilet
            amenity=parking	parking,car park
            amenity=charging_station	charging station,ev charging,charger
            amenity=car_wash	car wash
            amenity=motorcycle_parking	motorcycle parking
            amenity=place_of_worship	church
            amenity=marketplace	market
            amenity=library	library
            amenity=cinema	cinema,movie theatre,movie theater
            tourism=hotel	hotel
            tourism=motel	motel
            tourism=hostel	hostel,backpackers
            tourism=guest_house	guest house,bed and breakfast
            tourism=camp_site	camp site,camping,campsite
            tourism=caravan_site	caravan park,caravan site
            tourism=attraction	attraction
            tourism=museum	museum
            tourism=zoo	zoo
            tourism=viewpoint	viewpoint,lookout
            tourism=information	information,tourist information
            tourism=gallery	gallery
            tourism=theme_park	theme park
            shop=supermarket	supermarket,groceries
            shop=convenience	convenience store,convenience
            shop=bakery	bakery
            shop=chemist	chemist,pharmacy
            shop=motorcycle	motorcycle shop,motorcycle,motorbike
            shop=mall	shopping centre,shopping center,mall
            shop	shop
            leisure=park	park
            leisure=sports_centre	sports centre,sports center,gym
            leisure=swimming_pool	swimming pool,pool
            leisure=golf_course	golf course,golf
            historic=memorial	memorial
            historic=castle	castle
            historic=fort	fort
            natural=peak	peak,mountain,summit
            natural=beach	beach
            natural=water	water,lake
            natural=volcano	volcano
            railway=station	station,train station,railway station
            aeroway=aerodrome	airport,aerodrome
            highway=rest_area	rest area,rest stop
            highway=services	services,service centre,service center
            highway=bus_stop	bus stop,bus
            highway=bus_bay	bus stop,bus
            highway=platform	bus stop,bus,platform
            highway=elevator	lift,elevator
            amenity=college	college
            amenity=university	university
        """.trimIndent()
    }
}
