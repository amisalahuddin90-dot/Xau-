package com.free.smcict

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Feed {
    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** minutes = 15 or 60. Free public data, no key: Binance futures XAUUSDT, Bybit as fallback. */
    fun candles(minutes: Int): List<C> {
        try {
            val iv = if (minutes == 60) "1h" else "${minutes}m"
            val j = JSONArray(get("https://fapi.binance.com/fapi/v1/klines?symbol=XAUUSDT&interval=$iv&limit=500"))
            return (0 until j.length()).map {
                val r = j.getJSONArray(it)
                C(r.getLong(0), r.getString(1).toDouble(), r.getString(2).toDouble(), r.getString(3).toDouble(), r.getString(4).toDouble())
            }
        } catch (e: Exception) { }
        val j = JSONObject(get("https://api.bybit.com/v5/market/kline?category=linear&symbol=XAUUSDT&interval=$minutes&limit=500"))
            .getJSONObject("result").getJSONArray("list")
        return (0 until j.length()).map {
            val r = j.getJSONArray(it)
            C(r.getString(0).toLong(), r.getString(1).toDouble(), r.getString(2).toDouble(), r.getString(3).toDouble(), r.getString(4).toDouble())
        }.reversed()
    }
}
