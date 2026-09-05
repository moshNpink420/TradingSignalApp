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
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())

    // IMPORTANT:
    // এখানে আপনার আসল Twelve Data API key বসাবেন।
    // উদাহরণ:
    // private val apiKey = "abc123..."
    private val apiKey = "404594e1a458416998da981e69787f31"

    private lateinit var btcPrice: TextView
    private lateinit var btcSignal: TextView

    private lateinit var goldPrice: TextView
    private lateinit var goldSignal: TextView

    private val updateRunnable = object : Runnable {
        override fun run() {
            loadMarketData()
            handler.postDelayed(this, 120000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        btcPrice = findViewById(R.id.btcPrice)
        btcSignal = findViewById(R.id.btcSignal)

        goldPrice = findViewById(R.id.goldPrice)
        goldSignal = findViewById(R.id.goldSignal)

        loadMarketData()
    }

    override fun onResume() {
        super.onResume()

        handler.removeCallbacks(updateRunnable)
        handler.postDelayed(updateRunnable, 120000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    private fun loadMarketData() {

        btcPrice.text = "Price: loading..."
        btcSignal.text = "Signal: WAIT"

        goldPrice.text = "Price: loading..."
        goldSignal.text = "Signal: WAIT"

        Thread {

            val btcResult = getSignal("BTC/USD")
            val goldResult = getSignal("XAU/USD")

            runOnUiThread {

                updateUI(
                    btcResult,
                    btcPrice,
                    btcSignal
                )

                updateUI(
                    goldResult,
                    goldPrice,
                    goldSignal
                )
            }

        }.start()
    }

    private fun getSignal(symbol: String): MarketResult {

        try {

            // API key check
            if (apiKey.isBlank() ||
                apiKey == "YOUR_API_KEY"
            ) {
                return MarketResult(
                    null,
                    "WAIT",
                    "API key not set"
                )
            }

            val encodedSymbol =
                URLEncoder.encode(symbol, "UTF-8")

            val url =
                "https://api.twelvedata.com/time_series" +
                        "?symbol=$encodedSymbol" +
                        "&interval=5min" +
                        "&outputsize=100" +
                        "&apikey=$apiKey"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->

                val body =
                    response.body?.string() ?: ""

                // HTTP error
                if (!response.isSuccessful) {

                    val errorMessage = try {
                        val errorJson = JSONObject(body)

                        errorJson.optString(
                            "message",
                            "HTTP ${response.code}"
                        )

                    } catch (e: Exception) {
                        "HTTP ${response.code}"
                    }

                    return MarketResult(
                        null,
                        "WAIT",
                        "API Error ${response.code}: $errorMessage"
                    )
                }

                val json = JSONObject(body)

                // Twelve Data API error
                if (json.has("code")) {

                    val code =
                        json.optInt("code")

                    val message =
                        json.optString(
                            "message",
                            "Unknown API error"
                        )

                    if (code == 429) {

                        return MarketResult(
                            null,
                            "WAIT",
                            "Rate limit: please wait"
                        )
                    }

                    return MarketResult(
                        null,
                        "WAIT",
                        "API Error $code: $message"
                    )
                }

                val values =
                    json.optJSONArray("values")

                if (values == null) {

                    return MarketResult(
                        null,
                        "WAIT",
                        "No candle data received"
                    )
                }

                if (values.length() < 30) {

                    return MarketResult(
                        null,
                        "WAIT",
                        "Not enough candle data"
                    )
                }

                val closes =
                    ArrayList<Double>()

                // Twelve Data normally returns newest first.
                // Reverse it so calculations use oldest -> newest.
                for (i in values.length() - 1 downTo 0) {

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

                if (closes.size < 30) {

                    return MarketResult(
                        null,
                        "WAIT",
                        "Not enough valid candles"
                    )
                }

                val currentPrice =
                    closes.last()

                val previousPrice =
                    closes[closes.size - 2]

                val ema9 =
                    calculateEMA(closes, 9)

                val ema21 =
                    calculateEMA(closes, 21)

                val rsi =
                    calculateRSI(closes, 14)

                val momentum =
                    ((currentPrice - previousPrice) /
                            previousPrice) * 100.0

                var buyScore = 0
                var sellScore = 0

                // EMA confirmation
                if (ema9 > ema21) {
                    buyScore++
                } else if (ema9 < ema21) {
                    sellScore++
                }

                // RSI confirmation
                if (rsi >= 50.0 && rsi <= 70.0) {
                    buyScore++
                }

                if (rsi <= 50.0 && rsi >= 30.0) {
                    sellScore++
                }

                // Momentum confirmation
                if (momentum > 0.03) {
                    buyScore++
                } else if (momentum < -0.03) {
                    sellScore++
                }

                val signal = when {

                    buyScore >= 3 &&
                            buyScore > sellScore ->
                        "STRONG BUY"

                    sellScore >= 3 &&
                            sellScore > buyScore ->
                        "STRONG SELL"

                    buyScore >= 2 &&
                            buyScore > sellScore ->
                        "BUY"

                    sellScore >= 2 &&
                            sellScore > buyScore ->
                        "SELL"

                    else ->
                        "WAIT"
                }

                return MarketResult(
                    currentPrice,
                    signal,
                    ""
                )
            }

        } catch (e: Exception) {

            return MarketResult(
                null,
                "WAIT",
                "Connection error: ${e.message ?: "unknown"}"
            )
        }
    }

    private fun calculateEMA(
        prices: List<Double>,
        period: Int
    ): Double {

        if (prices.size < period) {
            return prices.last()
        }

        val multiplier =
            2.0 / (period + 1)

        var ema =
            prices.take(period).average()

        for (i in period until prices.size) {

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
                loss += abs(change)
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
                if (change < 0) abs(change) else 0.0

            averageGain =
                ((averageGain * (period - 1)) +
                        currentGain) / period

            averageLoss =
                ((averageLoss * (period - 1)) +
                        currentLoss) / period
        }

        if (averageLoss == 0.0) {
            return 100.0
        }

        val rs =
            averageGain / averageLoss

        return 100.0 -
                (100.0 / (1.0 + rs))
    }

    private fun updateUI(
        result: MarketResult,
        priceView: TextView,
        signalView: TextView
    ) {

        if (result.price != null) {

            priceView.text =
                String.format(
                    Locale.US,
                    "Price: %.2f",
                    result.price
                )

        } else {

            priceView.text =
                "Price: --"
        }

        signalView.text =
            if (result.info.isNotEmpty()) {
                "Signal: WAIT\n${result.info}"
            } else {
                "Signal: ${result.signal}"
            }
    }

    data class MarketResult(
        val price: Double?,
        val signal: String,
        val info: String
    )
}
