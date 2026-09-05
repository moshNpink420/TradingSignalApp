package com.moshnpink420.tradingsignalapp

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MainActivity : Activity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())

    private var isLoading = false

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

        // First request
        loadAllMarkets()
    }

    private fun loadAllMarkets() {

        // Prevent overlapping refresh cycles
        if (isLoading) return

        isLoading = true

        // BTC request
        loadMarketData(markets[0])

        // XAU request after 20 seconds
        handler.postDelayed({

            loadMarketData(markets[1])

        }, 20000)

        // Allow next refresh after both requests
        handler.postDelayed({

            isLoading = false

        }, 25000)

        // Refresh every 2 minutes
        handler.postDelayed({

            loadAllMarkets()

        }, 120000)
    }

    private fun loadMarketData(market: Market) {

        Thread {

            try {

                val apiKey =
                    BuildConfig.TWELVE_DATA_API_KEY

                if (apiKey.isBlank()) {

                    showError(
                        market,
                        "API KEY EMPTY"
                    )

                    return@Thread
                }

                val encodedSymbol =
                    URLEncoder.encode(
                        market.symbol,
                        "UTF-8"
                    )

                val url =
                    "https://api.twelvedata.com/time_series" +
                            "?symbol=$encodedSymbol" +
                            "&interval=5min" +
                            "&outputsize=35" +
                            "&apikey=$apiKey"

                val request =
                    Request.Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .get()
                        .build()

                client.newCall(request)
                    .execute()
                    .use { response ->

                        val body =
                            response.body?.string() ?: ""

                        /*
                         * HTTP 429
                         *
                         * Too many requests.
                         */
                        if (response.code == 429) {

                            showError(
                                market,
                                "RATE LIMIT - WAIT"
                            )

                            return@use
                        }

                        if (!response.isSuccessful) {

                            showError(
                                market,
                                "HTTP ${response.code}"
                            )

                            return@use
                        }

                        if (body.isBlank()) {

                            showError(
                                market,
                                "EMPTY RESPONSE"
                            )

                            return@use
                        }

                        val json =
                            try {

                                JSONObject(body)

                            } catch (e: Exception) {

                                showError(
                                    market,
                                    "INVALID JSON"
                                )

                                return@use
                            }

                        /*
                         * Twelve Data API error
                         */
                        if (
                            json.optString("status")
                                .equals("error", true)
                        ) {

                            val errorMessage =
                                json.optString(
                                    "message",
                                    "UNKNOWN API ERROR"
                                )

                            showError(
                                market,
                                "API ERROR"
                            )

                            return@use
                        }

                        if (!json.has("values")) {

                            showError(
                                market,
                                "NO DATA"
                            )

                            return@use
                        }

                        val values =
                            json.optJSONArray("values")

                        if (values == null) {

                            showError(
                                market,
                                "VALUES ERROR"
                            )

                            return@use
                        }

                        if (values.length() < 25) {

                            showError(
                                market,
                                "NOT ENOUGH DATA"
                            )

                            return@use
                        }

                        /*
                         * Convert candle closes.
                         *
                         * Twelve Data normally sends
                         * newest candle first.
                         *
                         * Reverse to:
                         * oldest -> newest
                         */
                        val closes =
                            mutableListOf<Double>()

                        for (
                            i in values.length() - 1 downTo 0
                        ) {

                            val candle =
                                values.optJSONObject(i)

                            if (candle != null) {

                                val close =
                                    candle
                                        .optString("close")
                                        .toDoubleOrNull()

                                if (close != null) {

                                    closes.add(close)
                                }
                            }
                        }

                        if (closes.size < 25) {

                            showError(
                                market,
                                "CLOSE DATA ERROR"
                            )

                            return@use
                        }

                        val currentPrice =
                            closes.last()

                        val previousPrice =
                            closes[
                                closes.size - 2
                            ]

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

                        val momentum =
                            when {

                                currentPrice >
                                        previousPrice ->
                                    1

                                currentPrice <
                                        previousPrice ->
                                    -1

                                else ->
                                    0
                            }

                        val trend =
                            calculateTrend(
                                closes
                            )

                        val emaDifference =
                            if (ema21 != 0.0) {

                                abs(
                                    ema9 - ema21
                                ) /
                                        abs(ema21) *
                                        100.0

                            } else {

                                0.0
                            }

                        val signal =
                            calculateStrongSignal(
                                ema9,
                                ema21,
                                rsi,
                                momentum,
                                trend,
                                emaDifference
                            )

                        runOnUiThread {

                            val priceView =
                                findViewById<TextView>(
                                    market.priceId
                                )

                            val signalView =
                                findViewById<TextView>(
                                    market.signalId
                                )

                            priceView.text =
                                "Price: ${
                                    formatPrice(
                                        currentPrice,
                                        market.symbol
                                    )
                                }"

                            signalView.text =
                                signal
                        }
                    }

            } catch (e: Exception) {

                showError(
                    market,
                    "CONNECTION ERROR"
                )
            }

        }.start()
    }

    private fun calculateEMA(
        prices: List<Double>,
        period: Int
    ): Double {

        if (prices.size < period) {

            return prices.lastOrNull()
                ?: 0.0
        }

        val multiplier =
            2.0 / (period + 1)

        var ema =
            prices.take(period).average()

        for (
            i in period until prices.size
        ) {

            ema =
                ((prices[i] - ema) *
                        multiplier) + ema
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
                    averageGain *
                            (period - 1) +
                            currentGain
                    ) / period

            averageLoss =
                (
                    averageLoss *
                            (period - 1) +
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

    private fun calculateTrend(
        prices: List<Double>
    ): Int {

        if (prices.size < 6) {

            return 0
        }

        val recent =
            prices.last()

        val old =
            prices[
                prices.size - 6
            ]

        return when {

            recent > old -> 1

            recent < old -> -1

            else -> 0
        }
    }

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

        // EMA confirmation
        if (ema9 > ema21) {

            buyScore++

        } else if (ema9 < ema21) {

            sellScore++
        }

        // RSI confirmation
        if (
            rsi >= 55.0 &&
            rsi <= 68.0
        ) {

            buyScore++

        } else if (
            rsi >= 32.0 &&
            rsi <= 45.0
        ) {

            sellScore++
        }

        // Momentum confirmation
        if (momentum > 0) {

            buyScore++

        } else if (momentum < 0) {

            sellScore++
        }

        // Trend confirmation
        if (trend > 0) {

            buyScore++

        } else if (trend < 0) {

            sellScore++
        }

        // EMA separation
        if (emaDifference >= 0.03) {

            if (ema9 > ema21) {

                buyScore++

            } else if (ema9 < ema21) {

                sellScore++
            }
        }

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
        price: Double,
        symbol: String
    ): String {

        return if (symbol == "BTC/USD") {

            String.format(
                Locale.US,
                "%.2f",
                price
            )

        } else {

            String.format(
                Locale.US,
                "%.2f",
                price
            )
        }
    }

    private fun showError(
        market: Market,
        message: String
    ) {

        runOnUiThread {

            val priceView =
                findViewById<TextView>(
                    market.priceId
                )

            val signalView =
                findViewById<TextView>(
                    market.signalId
                )

            priceView.text =
                "Price: --"

            signalView.text =
                message
        }
    }

    override fun onDestroy() {

        handler.removeCallbacksAndMessages(null)

        client.dispatcher
            .executorService
            .shutdown()

        super.onDestroy()
    }

    data class Market(
        val name: String,
        val symbol: String,
        val priceId: Int,
        val signalId: Int
    )
}
