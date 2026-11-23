package com.wmopener

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Card(val rarity: String, val name: String)

data class Entry(
    val time: Long,
    val source: String,       // "auto" ou "manuel"
    val opened: Int,
    val cards: List<Card>,
    val note: String = "",    // ce qui s'est passé quand aucune carte n'a été repérée
) {
    fun toJson(): JSONObject = JSONObject()
        .put("t", time).put("s", source).put("n", opened)
        .put("o", note)
        .put("c", JSONArray().apply { cards.forEach { put(JSONObject().put("r", it.rarity).put("n", it.name)) } })

    companion object {
        fun fromJson(o: JSONObject) = Entry(
            o.getLong("t"), o.optString("s", "auto"), o.optInt("n"),
            (o.optJSONArray("c") ?: JSONArray()).let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { c -> Card(c.optString("r"), c.optString("n")) } }
            },
            o.optString("o", ""),
        )
    }
}

/** Historique des ouvertures, stocké dans un fichier JSON privé (500 entrées max). */
object History {
    private const val MAX = 500
    /** Ordre de rareté supposé (non documenté par le site), utilisé pour l'affichage. */
    val RARITY_ORDER = listOf("C", "PC", "R", "SR", "UR", "L")

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "history.json")

    @Synchronized
    fun all(ctx: Context): List<Entry> = try {
        val a = JSONArray(file(ctx).readText())
        (0 until a.length()).map { Entry.fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    @Synchronized
    fun add(ctx: Context, e: Entry) = save(ctx, (listOf(e) + all(ctx)).take(MAX))

    @Synchronized
    fun remove(ctx: Context, time: Long) = save(ctx, all(ctx).filterNot { it.time == time })

    @Synchronized
    fun clear(ctx: Context) { file(ctx).delete() }

    private fun save(ctx: Context, list: List<Entry>) {
        val tmp = File(file(ctx).path + ".tmp")
        tmp.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
        tmp.renameTo(file(ctx))
    }

    fun parseCards(r: JSONObject): List<Card> {
        val a = r.optJSONArray("cards") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { Card(it.optString("rarity"), it.optString("name")) }
        }.filter { it.name.isNotBlank() }
    }
}
