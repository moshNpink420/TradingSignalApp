package com.moshnpink420.tradingsignalapp

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

private val handler = Handler(Looper.getMainLooper())

private val markets = listOf(
    Market(
        "BTC/USD",
        "BTC/USD",
        R.id.btcPrice,
        R.id.btcSignal
    ),
    Market(
        "XAU/USD",
        "XAU/USD",
        R.id.goldPrice,
        R.id.goldSignal
    )
)

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContentView(R.layout.activity_main)

    loadAllMarkets()
}

private fun loadAllMarkets() {

    for (market in markets) {
        loadMarketData(market)
    }

    handler.postDelayed(object : Runnable {

        override fun run() {

            loadAllMarkets()

            handler.postDelayed(this, 60_000)
        }

    }, 60_000)
}

private fun loadMarketData(market: Market) {

    Thread {

        try {

            val apiKey =
                BuildConfig.TWELVE_DATA_API_KEY

            /*
             * API key check
             */
            if (apiKey.isBlank()) {

                showError(
                    market,
                    "ERROR: API KEY EMPTY"
                )

                return@Thread
            }

            val url =
                "https://api.twelvedata.com/time_series" +
                        "?symbol=${market.symbol}" +
                        "&interval=1min" +
                        "&outputsize=50" +
                        "&apikey=$apiKey"

            val request =
                Request.Builder()
                    .url(url)
                    .get()
                    .build()

            client.newCall(request)
                .execute()
                .use { response ->

                    val body =
                        response.body?.string() ?: ""

                    /*
                     * HTTP error
                     */
                    if (!response.isSuccessful) {

                        showError(
                            market,
                            "HTTP ERROR: ${response.code}"
                        )

                        return@use
                    }

                    /*
                     * Empty response
                     */
                    if (body.isBlank()) {

                        showError(
                            market,
                            "ERROR: EMPTY RESPONSE"
                        )

                        return@use
                    }

                    /*
                     * JSON parse
                     */
                    val json = try {

                        JSONObject(body)

                    } catch (e: Exception) {

                        showError(
                            market,
                            "ERROR: INVALID JSON"
                        )

                        return@use
                    }

                    /*
                     * Twelve Data error message
                     */
                    if (json.has("status") &&
                        json.getString("status") == "error"
                    ) {

                        val message =
                            json.optString(
                                "message",
                                "Unknown API error"
                            )

                        showError(
                            market,
                            "API: $message"
                        )

                        return@use
                    }

                    /*
                     * No values
                     */
                    if (!json.has("values")) {

                        val message =
                            json.optString(
                                "message",
                                "VALUES NOT FOUND"
                            )

                        showError(
                            market,
                            "ERROR: $message"
                        )

                        return@use
                    }

                    val values =
                        json.getJSONArray("values")

                    if (values.length() < 22) {

                        showError(
                            market,
                            "ERROR: NOT ENOUGH DATA"
                        )

                        return@use
                    }

                    /*
                     * Close prices
                     */
                    val closes =
                        mutableListOf<Double>()

                    for (
                        i in values.length() - 1 downTo 0
                    ) {

                        val candle =
                            values.getJSONObject(i)

                        val close =
                            candle
                                .optString("close")
                                .toDoubleOrNull()

                        if (close != null) {
                            closes.add(close)
                        }
                    }

                    if (closes.size < 22) {

                        showError(
                            market,
                            "ERROR: CLOSE DATA MISSING"
                        )

                        return@use
                    }

                    /*
                     * Current price
                     */
                    val currentPrice =
                        closes.last()

                    /*
                     * EMA 9
                     */
                    val ema9 =
                        calculateEMA(
                            closes,
                            9
                        )

                    /*
                     * EMA 21
                     */
                    val ema21 =
                        calculateEMA(
                            closes,
                            21
                        )

                    /*
                     * RSI 14
                     */
                    val rsi =
                        calculateRSI(
                            closes,
                            14
                        )

                    /*
                     * Momentum
                     */
                    val previousPrice =
                        closes[closes.size - 2]

                    val momentum =
                        when {

                            currentPrice > previousPrice ->
                                1

                            currentPrice < previousPrice ->
                                -1

                            else ->
                                0
                        }

                    /*
                     * Signal
                     */
                    val signal =
                        calculateSignal(
                            ema9,
                            ema21,
                            rsi,
                            momentum
                        )

                    /*
                     * UI
                     */
                    runOnUiThread {

                        findViewById<TextView>(
                            market.priceId
                        ).text =
                            "Price: ${
                                formatPrice(
                                    currentPrice
                                )
                            }"

                        findViewById<TextView>(
                            market.signalId
                        ).text =
                            signal
                    }
                }

        } catch (e: Exception) {

            showError(
                market,
                "ERROR: ${e.message ?: "CONNECTION ERROR"}"
            )
        }

    }.start()
}

private fun calculateEMA(
    prices: List<Double>,
    period: Int
): Double {

    if (prices.size < period) {
        return prices.lastOrNull() ?: 0.0
    }

    val multiplier =
        2.0 / (period + 1)

    var ema =
        prices.take(period).average()

    for (i in period until prices.size) {

        ema =
            ((prices[i] - ema) * multiplier) +
                    ema
    }

    return ema
}

private fun calculateRSI(
    prices: List<Double>,
    period: Int
): Double {

    if (prices.size <= period) {
        return 50.0
    }

    var gain = 0.0
    var loss = 0.0

    for (i in 1..period) {

        val change =
            prices[i] - prices[i - 1]

        if (change > 0) {
            gain += change
        } else {
            loss += -change
        }
    }

    var averageGain =
        gain / period

    var averageLoss =
        loss / period

    for (i in period + 1 until prices.size) {

        val change =
            prices[i] - prices[i - 1]

        val currentGain =
            if (change > 0) change else 0.0

        val currentLoss =
            if (change < 0) -change else 0.0

        averageGain =
            (
                averageGain * (period - 1) +
                        currentGain
                ) / period

        averageLoss =
            (
                averageLoss * (period - 1) +
                        currentLoss
                ) / period
    }

    if (averageLoss == 0.0) {
        return 100.0
    }

    val rs =
        averageGain / averageLoss

    return 100.0 -
            (
                100.0 /
                        (1.0 + rs)
                )
}

private fun calculateSignal(
    ema9: Double,
    ema21: Double,
    rsi: Double,
    momentum: Int
): String {

    return when {

        ema9 > ema21 &&
                rsi >= 50.0 &&
                rsi < 70.0 &&
                momentum > 0 ->

            "BUY"

        ema9 < ema21 &&
                rsi > 30.0 &&
                rsi < 50.0 &&
                momentum < 0 ->

            "SELL"

        else ->
            "WAIT"
    }
}

private fun formatPrice(
    price: Double
): String {

    return String.format(
        Locale.US,
        "%.2f",
        price
    )
}

/*
 * আসল error screen-এ দেখাবে
 */
private fun showError(
    market: Market,
    message: String
) {

    runOnUiThread {

        findViewById<TextView>(
            market.priceId
        ).text =
            "Price: --"

        findViewById<TextView>(
            market.signalId
        ).text =
            message
    }
}

override fun onDestroy() {

    super.onDestroy()

    handler.removeCallbacksAndMessages(null)

    client.dispatcher
        .executorService
        .shutdown()
}

data class Market(
    val name: String,
    val symbol: String,
    val priceId: Int,
    val signalId: Int
)

}
