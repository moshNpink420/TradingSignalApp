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
import kotlin.math.abs

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

    /*
     * BTC আগে এবং Gold কিছুক্ষণ পরে।
     * এতে API-তে একসাথে request যায় না।
     */
    private fun loadAllMarkets() {

        loadMarketData(markets[0])

        handler.postDelayed({

            loadMarketData(markets[1])

        }, 5_000)

        /*
         * প্রতি 60 সেকেন্ডে update
         */
        handler.postDelayed({

            loadAllMarkets()

        }, 60_000)
    }

    private fun loadMarketData(market: Market) {

        Thread {

            try {

                val apiKey =
                    BuildConfig.TWELVE_DATA_API_KEY

                if (apiKey.isBlank()) {

                    showError(
                        market,
                        "ERROR: API KEY EMPTY"
                    )

                    return@Thread
                }

                /*
                 * 5 minute candle ব্যবহার করছি।
                 * Strong signal-এর জন্য 1 minute-এর চেয়ে
                 * noise কম হবে।
                 */
                val url =
                    "https://api.twelvedata.com/time_series" +
                            "?symbol=${market.symbol}" +
                            "&interval=5min" +
                            "&outputsize=35" +
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
                         * HTTP 429
                         */
                        if (response.code == 429) {

                            showError(
                                market,
                                "RATE LIMIT - WAIT"
                            )

                            return@use
                        }

                        /*
                         * অন্য HTTP error
                         */
                        if (!response.isSuccessful) {

                            showError(
                                market,
                                "HTTP ERROR: ${response.code}"
                            )

                            return@use
                        }

                        if (body.isBlank()) {

                            showError(
                                market,
                                "ERROR: EMPTY RESPONSE"
                            )

                            return@use
                        }

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
                         * API error
                         */
                        if (
                            json.has("status") &&
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

                        if (!json.has("values")) {

                            showError(
                                market,
                                "ERROR: VALUES NOT FOUND"
                            )

                            return@use
                        }

                        val values =
                            json.getJSONArray("values")

                        if (values.length() < 25) {

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

                        if (closes.size < 25) {

                            showError(
                                market,
                                "ERROR: CLOSE DATA MISSING"
                            )

                            return@use
                        }

                        val currentPrice =
                            closes.last()

                        /*
                         * Indicators
                         */
                        val ema9 =
                            calculateEMA(
                                closes,
                                9
                            )

                        val ema21 =
                            calculateEMA(
                                closes,
                                21
                            )

                        val rsi =
                            calculateRSI(
                                closes,
                                14
                            )

                        /*
                         * Short momentum
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
                         * Recent trend
                         */
                        val trend =
                            calculateTrend(
                                closes
                            )

                        /*
                         * EMA strength
                         */
                        val emaDifference =
                            if (ema21 != 0.0) {

                                abs(
                                    ema9 - ema21
                                ) / ema21 * 100.0

                            } else {
                                0.0
                            }

                        /*
                         * Strong Signal
                         */
                        val signal =
                            calculateStrongSignal(
                                ema9,
                                ema21,
                                rsi,
                                momentum,
                                trend,
                                emaDifference
                            )

                        /*
                         * UI update
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
                    "ERROR: ${
                        e.message ?: "CONNECTION ERROR"
                    }"
                )
            }

        }.start()
    }

    /*
     * EMA
     */
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

        for (
            i in period until prices.size
        ) {

            ema =
                ((prices[i] - ema) * multiplier) +
                        ema
        }

        return ema
    }

    /*
     * RSI 14
     */
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

        for (
            i in period + 1 until prices.size
        ) {

            val change =
                prices[i] - prices[i - 1]

            val currentGain =
                if (change > 0)
                    change
                else
                    0.0

            val currentLoss =
                if (change < 0)
                    -change
                else
                    0.0

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

    /*
     * শেষ কয়েকটি candle-এর trend
     */
    private fun calculateTrend(
        prices: List<Double>
    ): Int {

        if (prices.size < 6) {

            return 0
        }

        val recent =
            prices.last()

        val old =
            prices[prices.size - 6]

        return when {

            recent > old -> 1

            recent < old -> -1

            else -> 0
        }
    }

    /*
     * Strong Signal Engine
     *
     * সব condition একসাথে না মিললে
     * WAIT দেখাবে।
     */
    private fun calculateStrongSignal(
        ema9: Double,
        ema21: Double,
        rsi: Double,
        momentum: Int,
        trend: Int,
        emaDifference: Double
    ): String {

        var buyScore = 0
        var sellScore = 0

        /*
         * EMA direction
         */
        if (ema9 > ema21) {

            buyScore++

        } else if (ema9 < ema21) {

            sellScore++
        }

        /*
         * RSI confirmation
         *
         * BUY:
         * RSI 55-68
         *
         * SELL:
         * RSI 32-45
         */
        if (rsi >= 55.0 && rsi <= 68.0) {

            buyScore++

        } else if (rsi >= 32.0 && rsi <= 45.0) {

            sellScore++
        }

        /*
         * Momentum
         */
        if (momentum > 0) {

            buyScore++

        } else if (momentum < 0) {

            sellScore++
        }

        /*
         * Recent trend
         */
        if (trend > 0) {

            buyScore++

        } else if (trend < 0) {

            sellScore++
        }

        /*
         * EMA distance
         *
         * EMA দুটির মধ্যে যথেষ্ট separation
         * থাকলে trend stronger ধরা হবে।
         */
        if (emaDifference >= 0.03) {

            if (ema9 > ema21) {

                buyScore++

            } else if (ema9 < ema21) {

                sellScore++
            }
        }

        /*
         * Strong signal-এর জন্য
         * কমপক্ষে 4টি confirmation দরকার।
         */
        return when {

            buyScore >= 4 &&
                    buyScore > sellScore ->

                "STRONG BUY"

            sellScore >= 4 &&
                    sellScore > buyScore ->

                "STRONG SELL"

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
     * Error screen
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
