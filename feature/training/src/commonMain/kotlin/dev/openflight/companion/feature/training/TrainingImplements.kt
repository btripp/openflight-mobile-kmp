// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.training

import dev.openflight.companion.core.model.pi.TrainingImplement

/**
 * The implement picker, grouped like the web UI's `TRAINING_IMPLEMENTS`
 * (`ui/src/data/trainingImplements.ts`) but built from the **server's** valid keys
 * ([TrainingImplement.KNOWN], server.py `TRAINING_IMPLEMENT_LABELS`), so every option is one the
 * server accepts. The server's legacy `speed-stick-*` aliases are left out: they duplicate the
 * SuperSpeed labels and the web UI doesn't offer them either.
 */
object TrainingImplements {
    const val DEFAULT_ID: String = "driver"
    private const val GENERAL = "General"
    private const val LEGACY_ALIAS_PREFIX = "speed-stick-"

    /** Group names in picker order, each with the key prefix its implements share. */
    private val FAMILIES = listOf("SuperSpeed" to "superspeed", "TheStack" to "stack", "Rypstick" to "rypstick")

    val groups: List<ImplementGroup> by lazy {
        val offered = TrainingImplement.KNOWN.filterKeys { !it.startsWith(LEGACY_ALIAS_PREFIX) }
        val families =
            FAMILIES.map { (name, prefix) ->
                ImplementGroup(
                    name = name,
                    options = offered.filterKeys { it == prefix || it.startsWith("$prefix-") }.toOptions(),
                )
            }
        val grouped = families.flatMap { group -> group.options.map { it.id } }.toSet()
        listOf(ImplementGroup(GENERAL, offered.filterKeys { it !in grouped }.toOptions())) + families
    }

    /** The option for [id], or `null` when the server doesn't know it. */
    fun find(id: String): ImplementOption? = TrainingImplement.KNOWN[id]?.let { ImplementOption(id, it) }

    val default: ImplementOption get() = ImplementOption(DEFAULT_ID, TrainingImplement.KNOWN.getValue(DEFAULT_ID))

    private fun Map<String, String>.toOptions(): List<ImplementOption> =
        map { (id, label) -> ImplementOption(id, label) }
}
