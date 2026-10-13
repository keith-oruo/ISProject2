package ke.co.pesapath

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ExchangeRateContext(val prompt: String, val sourceNote: String)

class ExchangeRateSource(context: Context) {
    private val cache = context.applicationContext.getSharedPreferences("exchange_rates", Context.MODE_PRIVATE)

    fun contextForChat(): ExchangeRateContext {
        return try {
            val now = System.currentTimeMillis()
            val savedAt = cache.getLong("retrievedAt", 0L)
            val cached = cache.getString("response", null)
            val useCache = cached != null && now >= savedAt && now - savedAt < DAY_MILLIS
            val raw = if (useCache) cached!! else fetch()
            val data = JSONObject(raw)
            require(data.getString("result") == "success" && data.getString("base_code") == "KES")
            val publishedAt = data.getLong("time_last_update_unix") * 1000L
            require(publishedAt > 0L && publishedAt <= now + 300_000L && now - publishedAt < 2 * DAY_MILLIS)
            val rates = data.getJSONObject("rates")
            val verifiedRates = listOf("USD", "EUR", "GBP", "UGX", "TZS").joinToString("; ") { code ->
                val rate = rates.getDouble(code)
                require(rate.isFinite() && rate > 0.0)
                "1 KES = ${rates.getString(code)} $code"
            }
            if (!useCache) cache.edit().putString("response", raw).putLong("retrievedAt", now).apply()
            val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm 'UTC'", Locale.UK).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val published = dateFormat.format(Date(publishedAt))
            val retrieved = dateFormat.format(Date(if (useCache) savedAt else now))
            ExchangeRateContext(
                "Daily indicative exchange rates (not live bank quotes): $verifiedRates. " +
                    "Published: $published. Retrieved: $retrieved. " +
                    "Bank fees and spreads are excluded. No inflation, investment prices, or historical rates are supplied.",
                "Rates By Exchange Rate API\nhttps://www.exchangerate-api.com\n" +
                    "Published: $published\nRetrieved: $retrieved\nDaily indicative rates; bank fees and spreads excluded."
            )
        } catch (_: Exception) {
            ExchangeRateContext(
                "Exchange-rate data unavailable or outdated. Do not quote current exchange rates or invent market data. " +
                    "Explain the limitation if asked about currency conversion. Budgeting chat can continue.",
                "Exchange-rate data unavailable. No current market rates were supplied for this answer."
            )
        }
    }

    private fun fetch(): String {
        val connection = URL("https://open.er-api.com/v6/latest/KES").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            require(connection.responseCode == HttpURLConnection.HTTP_OK)
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val response = StringBuilder()
                val buffer = CharArray(4096)
                var count = reader.read(buffer)
                while (count != -1) {
                    require(response.length + count <= 65_536)
                    response.append(buffer, 0, count)
                    count = reader.read(buffer)
                }
                response.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}