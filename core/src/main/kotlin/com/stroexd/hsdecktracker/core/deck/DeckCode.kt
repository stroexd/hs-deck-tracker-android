package com.stroexd.hsdecktracker.core.deck

import com.stroexd.hsdecktracker.core.cards.GameFormat
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Karte im Sideboard (z. B. E.T.C., Bandmanager oder Zilliax-Module). */
@Serializable
data class SideboardCard(val dbfId: Int, val count: Int, val ownerDbfId: Int)

/** Inhalt eines Hearthstone-Deck-Codes. `cards` bildet dbfId → Anzahl ab. */
data class DeckDefinition(
    val heroes: List<Int>,
    val format: GameFormat,
    val cards: Map<Int, Int>,
    val sideboards: List<SideboardCard> = emptyList(),
) {
    val cardCount: Int get() = cards.values.sum()
}

class InvalidDeckCodeException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Kodiert/dekodiert das Deckstring-Format (Version 1) von Hearthstone:
 * Base64 aus Varints – Header, Format, Helden, Karten x1/x2/xn und optional Sideboards.
 */
object DeckCode {
    private const val VERSION = 1

    fun decode(code: String): DeckDefinition {
        val cleaned = code.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) throw InvalidDeckCodeException("Leerer Deck-Code")
        val padded = cleaned.trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) }
        val bytes = try {
            Base64.getDecoder().decode(padded)
        } catch (e: IllegalArgumentException) {
            throw InvalidDeckCodeException("Kein gültiges Base64", e)
        }
        val reader = VarIntReader(bytes)
        try {
            if (reader.readByte() != 0) throw InvalidDeckCodeException("Ungültiger Header")
            val version = reader.readVarInt()
            if (version != VERSION) throw InvalidDeckCodeException("Nicht unterstützte Version $version")
            val format = GameFormat.fromId(reader.readVarInt())
            val heroes = List(reader.readVarInt()) { reader.readVarInt() }
            val cards = linkedMapOf<Int, Int>()
            repeat(reader.readVarInt()) { cards.merge(reader.readVarInt(), 1, Int::plus) }
            repeat(reader.readVarInt()) { cards.merge(reader.readVarInt(), 2, Int::plus) }
            repeat(reader.readVarInt()) {
                val id = reader.readVarInt()
                cards.merge(id, reader.readVarInt(), Int::plus)
            }
            val sideboards = mutableListOf<SideboardCard>()
            if (reader.hasMore() && reader.readByte() == 1) {
                repeat(reader.readVarInt()) { sideboards += SideboardCard(reader.readVarInt(), 1, reader.readVarInt()) }
                repeat(reader.readVarInt()) { sideboards += SideboardCard(reader.readVarInt(), 2, reader.readVarInt()) }
                repeat(reader.readVarInt()) {
                    val id = reader.readVarInt()
                    val count = reader.readVarInt()
                    sideboards += SideboardCard(id, count, reader.readVarInt())
                }
            }
            if (heroes.isEmpty()) throw InvalidDeckCodeException("Kein Held im Deck-Code")
            return DeckDefinition(heroes, format, cards, sideboards)
        } catch (e: IndexOutOfBoundsException) {
            throw InvalidDeckCodeException("Deck-Code ist unvollständig", e)
        }
    }

    fun decodeOrNull(code: String): DeckDefinition? = try {
        decode(code)
    } catch (e: InvalidDeckCodeException) {
        null
    }

    fun encode(definition: DeckDefinition): String {
        val out = VarIntWriter()
        out.writeByte(0)
        out.writeVarInt(VERSION)
        out.writeVarInt(definition.format.id)
        val heroes = definition.heroes.sorted()
        out.writeVarInt(heroes.size)
        heroes.forEach(out::writeVarInt)

        val entries = definition.cards.filterValues { it > 0 }.toSortedMap()
        val x1 = entries.filterValues { it == 1 }.keys
        val x2 = entries.filterValues { it == 2 }.keys
        val xn = entries.filterValues { it > 2 }
        out.writeVarInt(x1.size); x1.forEach(out::writeVarInt)
        out.writeVarInt(x2.size); x2.forEach(out::writeVarInt)
        out.writeVarInt(xn.size); xn.forEach { (id, count) -> out.writeVarInt(id); out.writeVarInt(count) }

        val sideboards = definition.sideboards.filter { it.count > 0 }
            .sortedWith(compareBy<SideboardCard>({ it.dbfId }, { it.ownerDbfId }))
        if (sideboards.isEmpty()) {
            out.writeByte(0)
        } else {
            out.writeByte(1)
            val s1 = sideboards.filter { it.count == 1 }
            val s2 = sideboards.filter { it.count == 2 }
            val sn = sideboards.filter { it.count > 2 }
            out.writeVarInt(s1.size); s1.forEach { out.writeVarInt(it.dbfId); out.writeVarInt(it.ownerDbfId) }
            out.writeVarInt(s2.size); s2.forEach { out.writeVarInt(it.dbfId); out.writeVarInt(it.ownerDbfId) }
            out.writeVarInt(sn.size)
            sn.forEach { out.writeVarInt(it.dbfId); out.writeVarInt(it.count); out.writeVarInt(it.ownerDbfId) }
        }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private class VarIntReader(private val bytes: ByteArray) {
        private var pos = 0
        fun hasMore(): Boolean = pos < bytes.size
        fun readByte(): Int = bytes[pos++].toInt() and 0xFF
        fun readVarInt(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = readByte()
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 28) throw InvalidDeckCodeException("Varint zu lang")
            }
        }
    }

    private class VarIntWriter {
        private val out = ByteArrayOutputStream()
        fun writeByte(b: Int) = out.write(b)
        fun writeVarInt(value: Int) {
            var v = value
            while (true) {
                if (v and 0x7F.inv() == 0) {
                    out.write(v)
                    return
                }
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
        }
        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
