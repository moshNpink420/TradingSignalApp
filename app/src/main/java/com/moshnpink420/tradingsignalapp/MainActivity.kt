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
import kotlin.math.abs

class MainActivity : Activity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())

    /*
     * আগের price সংরক্ষণ করা হবে।
     * পরের price-এর সঙ্গে তুলনা করে BUY / SELL / WAIT নির্ধারণ হবে।
     */
    private val previousPrices = mutableMapOf<String, Double>()

    /*
     * শুধু ৪টি গুরুত্বপূর্ণ market
     */
    private val markets = listOf(
        Market(
            "EUR/USD",
            "EUR/USD",
            R.id.eurPrice,
            R.id.eurSignal
        ),

        Market(
            "GBP/USD",
            "GBP/USD",
            R.id.gbpPrice,
            R.id.gbpSignal
        ),

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

        loadAllPrices()
    }

    /*
     * সব market-এর price load করা
     */
    private fun loadAllPrices() {

        for (market in markets) {
            loadPrice(market)
        }

        /*
         * প্রতি ১ মিনিটে আবার update
         */
        handler.postDelayed(object : Runnable {

            override fun run() {

                loadAllPrices()

                handler.postDelayed(this, 60_000)
            }

        }, 60_000)
    }

    /*
     * Twelve Data থেকে price নেওয়া
     */
    private fun loadPrice(market: Market) {

        Thread {

            try {

                val apiKey = BuildConfig.TWELVE_DATA_API_KEY

                if (apiKey.isBlank()) {

                    showError(
                        market,
                        "API Key missing"
                    )

                    return@Thread
                }

                val url =
                    "https://api.twelvedata.com/price" +
                            "?symbol=${market.symbol}" +
                            "&apikey=$apiKey"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->

                    val body = response.body?.string() ?: ""

                    val json = try {

                        JSONObject(body)

                    } catch (e: Exception) {

                        showError(
                            market,
                            "Invalid response"
                        )

                        return@use
                    }

                    if (json.has("price")) {

                        val priceText =
                            json.getString("price")

                        val currentPrice =
                            priceText.toDoubleOrNull()

                        if (currentPrice == null) {

                            showError(
                                market,
                                "Invalid price"
                            )

                            return@use
                        }

                        /*
                         * আগের price বের করা
                         */
                        val previousPrice =
                            previousPrices[market.symbol]

                        /*
                         * বর্তমান price save করা
                         */
                        previousPrices[market.symbol] =
                            currentPrice

                        /*
                         * Signal calculate
                         */
                        val signal =
                            calculateSignal(
                                market.symbol,
                                currentPrice,
                                previousPrice
                            )

                        /*
                         * UI update
                         */
                        runOnUiThread {

                            findViewById<TextView>(
                                market.priceId
                            ).text =
                                "Price: $priceText"

                            findViewById<TextView>(
                                market.signalId
                            ).text =
                                signal
                        }

                    } else {

                        val message =
                            json.optString(
                                "message",
                                "Symbol unavailable"
                            )

                        showError(
                            market,
                            message
                        )
                    }
                }

            } catch (e: Exception) {

                showError(
                    market,
                    "Connection error"
                )
            }

        }.start()
    }

    /*
     * BUY / SELL / WAIT calculation
     */
    private fun calculateSignal(
        symbol: String,
        currentPrice: Double,
        previousPrice: Double?
    ): String {

        /*
         * প্রথমবার price এলে
         * signal দেওয়া হবে না
         */
        if (previousPrice == null) {
            return "WAIT"
        }

        if (previousPrice == 0.0) {
            return "WAIT"
        }

        /*
         * Price কত শতাংশ পরিবর্তন হয়েছে
         */
        val changePercent =
            ((currentPrice - previousPrice)
                    / previousPrice) * 100.0

        /*
         * Market অনুযায়ী sensitivity
         */
        val threshold = when (symbol) {

            "BTC/USD" -> 0.015

            "XAU/USD" -> 0.008

            "GBP/USD" -> 0.003

            "EUR/USD" -> 0.002

            else -> 0.003
        }

        return when {

            changePercent >= threshold ->
                "BUY"

            changePercent <= -threshold ->
                "SELL"

            else ->
                "WAIT"
        }
    }

    /*
     * Error হলে UI
     */
    private fun showError(
        market: Market,
        message: String
    ) {

        runOnUiThread {

            findViewById<TextView>(
                market.priceId
            ).text = "Price: --"

            findViewById<TextView>(
                market.signalId
            ).text = "WAIT"
        }
    }

    override fun onDestroy() {

        super.onDestroy()

        handler.removeCallbacksAndMessages(null)

        client.dispatcher.executorService.shutdown()
    }

    data class Market(
        val name: String,
        val symbol: String,
        val priceId: Int,
        val signalId: Int
    )
}
