package com.pokeskies.cobblemonplaceholders.utils

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokedex.Dexes
import com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress
import com.cobblemon.mod.common.api.pokedex.PokedexManager
import com.cobblemon.mod.common.api.pokedex.entry.PokedexEntry
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.pokeskies.cobblemonplaceholders.config.ConfigManager
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.ConcurrentHashMap

object DexUtils {
    val NAT_DEX_ID: ResourceLocation = ResourceLocation.parse("cobblemon:national")

    /**
     * Entry lists resolved once per dex instead of on every placeholder request.
     *
     * PokedexDef.getEntries() is not a cheap accessor. SimplePokedexDef maps its ids through
     * DexEntries into a new list, and AggregatePokedexDef (which the national dex is) walks every
     * sub-dex, calls getEntries() on each of them, and flatMaps the results into another list,
     * optionally squashing duplicates through a LinkedHashMap. Resolving the national dex therefore
     * allocates several collections over thousands of entries.
     *
     * Placeholders are expanded on the server thread, and a proxy or tab list can request them for
     * every player several times a second, so doing that work per request is enough to stall the
     * tick. The entry data only changes when datapacks reload, so it is resolved once per dex here
     * and dropped by [invalidate].
     */
    private val rawEntries = ConcurrentHashMap<ResourceLocation, List<PokedexEntry>>()
    private val uniqueEntries = ConcurrentHashMap<ResourceLocation, List<PokedexEntry>>()
    private val shinyEntries = ConcurrentHashMap<ResourceLocation, List<PokedexEntry>>()

    /** Drops the resolved lists. Runs on datapack reload, when [Dexes] is rebuilt. */
    fun invalidate() {
        rawEntries.clear()
        uniqueEntries.clear()
        shinyEntries.clear()
    }

    fun getDexManager(player: ServerPlayer): PokedexManager {
        return Cobblemon.playerDataManager.getPokedexData(player)
    }

    private fun raw(dexId: ResourceLocation): List<PokedexEntry>? {
        val def = Dexes.dexEntryMap[dexId] ?: return null
        return rawEntries.computeIfAbsent(dexId) { def.getEntries() }
    }

    /** Entries with duplicate ids collapsed, keeping the last occurrence as associateBy did. */
    private fun unique(dexId: ResourceLocation): List<PokedexEntry>? {
        val entries = raw(dexId) ?: return null
        return uniqueEntries.computeIfAbsent(dexId) {
            val byId = LinkedHashMap<ResourceLocation, PokedexEntry>()
            entries.forEach { byId[it.id] = it }
            byId.values.toList()
        }
    }

    private fun shiny(dexId: ResourceLocation): List<PokedexEntry>? {
        val entries = unique(dexId) ?: return null
        return shinyEntries.computeIfAbsent(dexId) {
            entries.filter { it.conditionAspects.contains("shiny") }
        }
    }

    fun getDexTotal(dexId: ResourceLocation): Int? {
        // The count stays live because it depends on the includeUnimplemented setting, which a
        // config reload can change. Only the entry list itself is reused.
        return raw(dexId)?.count { entry ->
            if (ConfigManager.CONFIG.placeholders.pokedex.includeUnimplemented) {
                true
            } else {
                PokemonSpecies.getByIdentifier(entry.speciesId)?.implemented == true
            }
        }
    }

    fun getDexProgress(manager: PokedexManager, dexId: ResourceLocation, knowledge: PokedexEntryProgress): Int? {
        return unique(dexId)?.count { manager.getKnowledgeForSpecies(it.speciesId).ordinal >= knowledge.ordinal }
    }

    fun getShinyDexProgress(manager: PokedexManager, dexId: ResourceLocation, knowledge: PokedexEntryProgress): Int? {
        return shiny(dexId)?.count { manager.getKnowledgeForSpecies(it.speciesId).ordinal >= knowledge.ordinal }
    }
}
