package com.stroexd.hsdecktracker.core.collection

import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.util.PrettyJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class CollectionImportResult(
    val collection: CardCollection,
    val detectedFormat: String,
    val importedCards: Int,
    val importedCopies: Int,
    val unresolved: List<String>,
    val dust: Int?,
)

class CollectionImportException(
    val reason: Reason,
    val unresolved: List<String> = emptyList(),
) : IllegalArgumentException(reason.name) {
    enum class Reason { EMPTY, INVALID_JSON, UNKNOWN_FORMAT, NO_CARDS }
}

object CollectionImporter {
    private val idKeys = listOf("dbfid", "dbf_id", "dbf", "id", "cardid", "card_id", "name", "karte", "kartenname", "card")
    private val countKeys = listOf("count", "normal", "anzahl", "amount", "quantity", "owned", "menge", "copies", "plain")
    private val goldenKeys = listOf("golden", "gold", "goldene", "premium", "goldencount", "golden_count")
    private val diamondKeys = listOf("diamond", "diamant")
    private val signatureKeys = listOf("signature", "signatur")
    private val dustKeys = listOf("dust", "staub", "arcane_dust", "arcanedust")

    fun import(text: String, db: CardDatabase, now: Long): CollectionImportResult {
        val trimmed = text.trim().removePrefix("﻿")
        if (trimmed.isEmpty()) throw CollectionImportException(CollectionImportException.Reason.EMPTY)
        val acc = Accumulator(db)
        val format = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val element = try {
                PrettyJson.parseToJsonElement(trimmed)
            } catch (e: Exception) {
                throw CollectionImportException(CollectionImportException.Reason.INVALID_JSON)
            }
            importJson(element, acc)
        } else {
            importText(trimmed, acc)
        }
        if (acc.cards.isEmpty()) {
            throw CollectionImportException(CollectionImportException.Reason.NO_CARDS, acc.unresolved)
        }
        return CollectionImportResult(
            collection = CardCollection(cards = acc.cards, dust = acc.dust ?: 0, updatedAt = now, source = format),
            detectedFormat = format,
            importedCards = acc.cards.size,
            importedCopies = acc.cards.values.sumOf { it.total },
            unresolved = acc.unresolved,
            dust = acc.dust,
        )
    }

    private class Accumulator(val db: CardDatabase) {
        val cards = linkedMapOf<Int, OwnedCard>()
        val unresolved = mutableListOf<String>()
        var dust: Int? = null

        fun add(identifier: String, owned: OwnedCard) {
            if (owned.total <= 0) return
            val dbfId = resolve(identifier)
            if (dbfId == null) {
                if (unresolved.size < 200) unresolved += identifier
                return
            }
            cards[dbfId] = (cards[dbfId] ?: OwnedCard()) + owned
        }

        fun canResolve(identifier: String): Boolean = resolve(identifier) != null

        private fun resolve(identifier: String): Int? {
            val id = identifier.trim().trim('"', '\'')
            id.toIntOrNull()?.let { number -> return if (db.isEmpty || db.byDbfId(number) != null) number else null }
            return db.resolve(id)?.dbfId
        }
    }

    private fun importJson(element: JsonElement, acc: Accumulator): String {
        when (element) {
            is JsonObject -> {
                findInt(element, dustKeys)?.let { acc.dust = it }
                val collection = element["collection"] ?: element["cards"] ?: element["sammlung"]
                if (collection != null) {
                    importJsonCards(collection, acc)
                    return if (element.containsKey("collection")) "HSReplay JSON" else "JSON"
                }
                importJsonCards(element, acc)
                return "JSON map"
            }
            is JsonArray -> {
                importJsonCards(element, acc)
                return "JSON list"
            }
            else -> throw CollectionImportException(CollectionImportException.Reason.UNKNOWN_FORMAT)
        }
    }

    private fun importJsonCards(element: JsonElement, acc: Accumulator) {
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                if (key.lowercase() in dustKeys) continue
                acc.add(key, ownedFromJsonValue(value))
            }
            is JsonArray -> for (item in element) {
                when (item) {
                    is JsonArray -> {
                        val id = item.getOrNull(0)?.let { primitiveString(it) } ?: continue
                        val counts = item.drop(1).map { primitiveInt(it) ?: 0 }
                        acc.add(id, ownedFromList(counts.ifEmpty { listOf(1) }))
                    }
                    is JsonObject -> {
                        val id = idKeys.firstNotNullOfOrNull { key -> findValue(item, key)?.let { primitiveString(it) } }
                            ?: continue
                        val owned = ownedFromObject(item)
                        acc.add(id, if (owned.total == 0 && findInt(item, countKeys) == null) OwnedCard(normal = 1) else owned)
                    }
                    is JsonPrimitive -> primitiveString(item)?.let { acc.add(it, OwnedCard(normal = 1)) }
                    JsonNull -> Unit
                }
            }
            else -> Unit
        }
    }

    private fun ownedFromJsonValue(value: JsonElement): OwnedCard = when (value) {
        is JsonArray -> ownedFromList(value.map { primitiveInt(it) ?: 0 })
        is JsonObject -> ownedFromObject(value)
        is JsonPrimitive -> OwnedCard(normal = primitiveInt(value) ?: 0)
        JsonNull -> OwnedCard()
    }

    private fun ownedFromList(counts: List<Int>) = OwnedCard(
        normal = counts.getOrElse(0) { 0 },
        golden = counts.getOrElse(1) { 0 },
        diamond = counts.getOrElse(2) { 0 },
        signature = counts.getOrElse(3) { 0 },
    )

    private fun ownedFromObject(obj: JsonObject) = OwnedCard(
        normal = findInt(obj, countKeys) ?: 0,
        golden = findInt(obj, goldenKeys) ?: 0,
        diamond = findInt(obj, diamondKeys) ?: 0,
        signature = findInt(obj, signatureKeys) ?: 0,
    )

    private fun findValue(obj: JsonObject, key: String): JsonElement? =
        obj.entries.firstOrNull { it.key.lowercase() == key }?.value

    private fun findInt(obj: JsonObject, keys: List<String>): Int? =
        keys.firstNotNullOfOrNull { key -> findValue(obj, key)?.let { primitiveInt(it) } }

    private fun primitiveInt(element: JsonElement): Int? =
        (element as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }

    private fun primitiveString(element: JsonElement): String? =
        (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private val countFirstRegex = Regex("^(\\d+)\\s*[x×]?\\s+(\\D.*)$", RegexOption.IGNORE_CASE)
    private val countPrefixXRegex = Regex("^(\\d+)\\s*[x×]\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val trailingCountRegex = Regex("^(.+?)\\s+[x×]?(\\d{1,3})$", RegexOption.IGNORE_CASE)
    private val dustLineRegex = Regex("^(?:dust|staub|arkanstaub)\\s*[:;,=]?\\s*(\\d+)$", RegexOption.IGNORE_CASE)

    private fun importText(text: String, acc: Accumulator): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
        if (lines.isEmpty()) return "Text"
        val delimiter = detectDelimiter(lines)
        val header = delimiter?.let { d -> parseHeader(splitCsv(lines.first(), d))?.let { it to d } }
        val body = if (header != null) lines.drop(1) else lines
        for (line in body) {
            val dustMatch = dustLineRegex.find(line)
            if (dustMatch != null) {
                acc.dust = dustMatch.groupValues[1].toInt()
                continue
            }
            if (header != null) {
                val (columns, headerDelimiter) = header
                val cols = splitCsv(line, headerDelimiter)
                val id = cols.getOrNull(columns.idColumn)?.takeIf { it.isNotBlank() } ?: continue
                val owned = OwnedCard(
                    normal = columns.countColumn?.let { cols.getOrNull(it)?.trim()?.toIntOrNull() } ?: 1,
                    golden = columns.goldenColumn?.let { cols.getOrNull(it)?.trim()?.toIntOrNull() } ?: 0,
                    diamond = columns.diamondColumn?.let { cols.getOrNull(it)?.trim()?.toIntOrNull() } ?: 0,
                    signature = columns.signatureColumn?.let { cols.getOrNull(it)?.trim()?.toIntOrNull() } ?: 0,
                )
                acc.add(id, owned)
            } else {
                parseFreeLine(line, delimiter, acc)
            }
        }
        return if (delimiter != null) "CSV" else "Text"
    }

    private fun parseFreeLine(line: String, delimiter: Char?, acc: Accumulator) {
        if (delimiter != null) {
            val cols = splitCsv(line, delimiter).map { it.trim() }.filter { it.isNotEmpty() }
            if (cols.isEmpty()) return
            if (cols.size == 2 && cols[0].toIntOrNull() != null && cols[1].toIntOrNull() == null) {
                acc.add(cols[1], OwnedCard(normal = cols[0].toInt()))
                return
            }
            val counts = cols.drop(1).mapNotNull { it.removeSuffix("x").toIntOrNull() }
            acc.add(cols[0], ownedFromList(counts.ifEmpty { listOf(1) }))
            return
        }
        val match = countPrefixXRegex.find(line) ?: countFirstRegex.find(line)
        if (match != null) {
            acc.add(match.groupValues[2].trim(), OwnedCard(normal = match.groupValues[1].toInt()))
            return
        }
        if (acc.canResolve(line)) {
            acc.add(line, OwnedCard(normal = 1))
            return
        }
        val trailing = trailingCountRegex.find(line)
        if (trailing != null) {
            acc.add(trailing.groupValues[1].trim(), OwnedCard(normal = trailing.groupValues[2].toInt()))
            return
        }
        acc.add(line, OwnedCard(normal = 1))
    }

    private fun detectDelimiter(lines: List<String>): Char? {
        val sample = lines.take(20)
        return listOf(';', '\t', ',', '|').maxByOrNull { d -> sample.count { d in it } }
            ?.takeIf { d -> sample.count { d in it } >= (sample.size + 1) / 2 }
    }

    private data class Header(
        val idColumn: Int,
        val countColumn: Int?,
        val goldenColumn: Int?,
        val diamondColumn: Int?,
        val signatureColumn: Int?,
    )

    private fun parseHeader(columns: List<String>): Header? {
        val lower = columns.map { it.trim().lowercase() }
        if (lower.any { it.toIntOrNull() != null }) return null
        fun indexOf(keys: List<String>) = lower.indexOfFirst { it in keys }.takeIf { it >= 0 }
        val id = idKeys.firstNotNullOfOrNull { key -> lower.indexOf(key).takeIf { it >= 0 } } ?: return null
        return Header(id, indexOf(countKeys), indexOf(goldenKeys), indexOf(diamondKeys), indexOf(signatureKeys))
    }

    internal fun splitCsv(line: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == delimiter && !inQuotes -> { result += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        result += current.toString()
        return result
    }
}

object CollectionExporter {
    fun toJson(collection: CardCollection): String = PrettyJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            putJsonObject("collection") {
                for ((id, owned) in collection.cards.toSortedMap()) {
                    putJsonArray(id.toString()) {
                        add(JsonPrimitive(owned.normal))
                        add(JsonPrimitive(owned.golden))
                        add(JsonPrimitive(owned.diamond))
                        add(JsonPrimitive(owned.signature))
                    }
                }
            }
            put("dust", collection.dust)
        },
    )

    fun toCsv(collection: CardCollection, db: CardDatabase): String = buildString {
        appendLine("dbfId;cardId;name;normal;golden;diamond;signature")
        for ((id, owned) in collection.cards.toSortedMap()) {
            val card = db.byDbfId(id)
            val name = card?.name?.replace("\"", "\"\"")?.let { "\"$it\"" } ?: ""
            appendLine("$id;${card?.id ?: ""};$name;${owned.normal};${owned.golden};${owned.diamond};${owned.signature}")
        }
        appendLine("dust;${collection.dust}")
    }
}
