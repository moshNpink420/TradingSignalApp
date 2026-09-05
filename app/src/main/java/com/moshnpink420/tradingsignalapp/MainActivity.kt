package com.moshnpink420.tradingsignalapp

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())

    /*
     * এখন শুধু BTC/USD এবং XAU/USD
     */
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
     * সব market update
     */
    private fun loadAllMarkets() {

        for (market in markets) {
            loadMarketData(market)
        }

        /*
         * প্রতি ১ মিনিটে update
         */
        handler.postDelayed(object : Runnable {

            override fun run() {

                loadAllMarkets()

                handler.postDelayed(this, 60_000)
            }

        }, 60_000)
    }

    /*
     * Twelve Data থেকে 1-minute candle নেওয়া
     */
    private fun loadMarketData(market: Market) {

        Thread {

            try {

                val apiKey =
                    BuildConfig.TWELVE_DATA_API_KEY

                if (apiKey.isBlank()) {

                    showError(market)

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

                        val json = try {

                            JSONObject(body)

                        } catch (e: Exception) {

                            showError(market)

                            return@use
                        }

                        if (!json.has("values")) {

                            showError(market)

                            return@use
                        }

                        val values =
                            json.getJSONArray("values")

                        if (values.length() < 22) {

                            showError(market)

                            return@use
                        }

                        /*
                         * Candle close prices
                         *
                         * Twelve Data সাধারণত newest
                         * candle আগে দেয়।
                         *
                         * তাই oldest -> newest করা হচ্ছে।
                         */
                        val closes =
                            mutableListOf<Double>()

                        for (i in values.length() - 1 downTo 0) {

                            val candle =
                                values.getJSONObject(i)

                            val close =
                                candle
                                    .getString("close")
                                    .toDoubleOrNull()

                            if (close != null) {
                                closes.add(close)
                            }
                        }

                        if (closes.size < 22) {

                            showError(market)

                            return@use
                        }

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
                         * Signal
                         */
                        val signal =
                            calculateSignal(
                                ema9,
                                ema21,
                                rsi
                            )

                        /*
                         * UI update
                         */
                        runOnUiThread {

                            findViewById<TextView>(
                                market.priceId
                            ).text =
                                "Price: " +
                                        formatPrice(
                                            currentPrice,
                                            market.symbol
                                        )

                            findViewById<TextView>(
                                market.signalId
                            ).text =
                                signal
                        }
                    }

            } catch (e: Exception) {

                showError(market)
            }

        }.start()
    }

    /*
     * EMA calculation
     */
    private fun calculateEMA(
        prices: List<Double>,
        period: Int
    ): Double {

        if (prices.isEmpty()) {
            return 0.0
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

    /*
     * RSI calculation
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

        /*
         * প্রথম 14 candle
         */
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

        /*
         * পরের candleগুলো দিয়ে Wilder RSI
         */
        for (i in period + 1 until prices.size) {

            val change =
                prices[i] - prices[i - 1]

            val currentGain =
                if (change > 0) change else 0.0

            val currentLoss =
                if (change < 0) -change else 0.0

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

        val relativeStrength =
            averageGain / averageLoss

        return 100.0 -
                (100.0 /
                        (1.0 + relativeStrength))
    }

    /*
     * EMA + RSI signal logic
     *
     * BUY:
     * EMA9 > EMA21
     * RSI 50 থেকে 70
     *
     * SELL:
     * EMA9 < EMA21
     * RSI 30 থেকে 50
     *
     * অন্য অবস্থায় WAIT
     */
    private fun calculateSignal(
        ema9: Double,
        ema21: Double,
        rsi: Double
    ): String {

        return when {

            ema9 > ema21 &&
                    rsi >= 50 &&
                    rsi < 70 ->

                "BUY"

            ema9 < ema21 &&
                    rsi > 30 &&
                    rsi < 50 ->

                "SELL"

            else ->
                "WAIT"
        }
    }

    /*
     * Price কত decimal দেখাবে
     */
    private fun formatPrice(
        price: Double,
        symbol: String
    ): String {

        return if (symbol == "BTC/USD") {

            String.format(
                "%.2f",
                price
            )

        } else {

            String.format(
                "%.2f",
                price
            )
        }
    }

    /*
     * Error হলে
     */
    private fun showError(
        market: Market
    ) {

        runOnUiThread {

            findViewById<TextView>(
                market.priceId
            ).text =
                "Price: --"

            findViewById<TextView>(
                market.signalId
            ).text =
                "WAIT"
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
