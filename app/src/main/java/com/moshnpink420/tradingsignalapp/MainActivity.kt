package com.moshnpink420.tradingsignalapp

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var btcPrice: TextView
    private lateinit var btcSignal: TextView
    private lateinit var goldPrice: TextView
    private lateinit var goldSignal: TextView

    private val handler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // আপনার Twelve Data API Key
    private val API_KEY = "YOUR_API_KEY"

    private val CHANNEL_ID = "trading_signal_channel"

    private var lastBtcSignal = "WAIT"
    private var lastGoldSignal = "WAIT"

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateMarket("BTC/USD")
            updateMarket("XAU/USD")

            // প্রতি ১ মিনিটে market data check
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        btcPrice = findViewById(R.id.btcPrice)
        btcSignal = findViewById(R.id.btcSignal)

        goldPrice = findViewById(R.id.goldPrice)
        goldSignal = findViewById(R.id.goldSignal)

        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            if (
                checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }

        handler.post(updateRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateRunnable)
        super.onDestroy()
    }

    private fun updateMarket(symbol: String) {

        if (API_KEY == "YOUR_API_KEY") {

            runOnUiThread {

                if (symbol == "BTC/USD") {
                    btcPrice.text = "Price: API KEY needed"
                    btcSignal.text = "Signal: WAIT"
                } else {
                    goldPrice.text = "Price: API KEY needed"
                    goldSignal.text = "Signal: WAIT"
                }
            }

            return
        }

        Thread {

            try {

                val encodedSymbol =
                    symbol.replace("/", "%2F")

                val url =
                    "https://api.twelvedata.com/time_series" +
                            "?symbol=$encodedSymbol" +
                            "&interval=5min" +
                            "&outputsize=50" +
                            "&apikey=$API_KEY"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response =
                    client.newCall(request).execute()

                val body =
                    response.body?.string()

                if (body.isNullOrEmpty()) {
                    showError(symbol)
                    return@Thread
                }

                val json = JSONObject(body)

                if (
                    json.has("code") ||
                    (
                        json.has("status") &&
                        json.optString("status") == "error"
                    )
                ) {
                    showError(symbol)
                    return@Thread
                }

                val values =
                    json.optJSONArray("values")

                if (
                    values == null ||
                    values.length() < 30
                ) {
                    showError(symbol)
                    return@Thread
                }

                val closes = ArrayList<Double>()
                val opens = ArrayList<Double>()
                val highs = ArrayList<Double>()
                val lows = ArrayList<Double>()

                /*
                 * Twelve Data সাধারণত newest candle আগে দেয়।
                 * তাই oldest -> newest করা হচ্ছে।
                 */
                for (i in values.length() - 1 downTo 0) {

                    val candle =
                        values.getJSONObject(i)

                    opens.add(
                        candle.getString("open").toDouble()
                    )

                    highs.add(
                        candle.getString("high").toDouble()
                    )

                    lows.add(
                        candle.getString("low").toDouble()
                    )

                    closes.add(
                        candle.getString("close").toDouble()
                    )
                }

                val price = closes.last()

                val ema9 =
                    calculateEMA(closes, 9)

                val ema21 =
                    calculateEMA(closes, 21)

                val rsi =
                    calculateRSI(closes, 14)

                val momentum =
                    calculateMomentum(closes, 5)

                val signal =
                    calculateStrongSignal(
                        closes,
                        opens,
                        highs,
                        lows,
                        ema9,
                        ema21,
                        rsi,
                        momentum
                    )

                runOnUiThread {

                    val formattedPrice =
                        String.format(
                            Locale.US,
                            "%.2f",
                            price
                        )

                    if (symbol == "BTC/USD") {

                        btcPrice.text =
                            "Price: $formattedPrice"

                        btcSignal.text =
                            "Signal: $signal"

                        if (
                            (signal == "BUY" ||
                             signal == "SELL") &&
                            signal != lastBtcSignal
                        ) {

                            sendNotification(
                                "BTC/USD $signal",
                                "Price: $formattedPrice\nSignal: $signal"
                            )
                        }

                        lastBtcSignal = signal

                    } else {

                        goldPrice.text =
                            "Price: $formattedPrice"

                        goldSignal.text =
                            "Signal: $signal"

                        if (
                            (signal == "BUY" ||
                             signal == "SELL") &&
                            signal != lastGoldSignal
                        ) {

                            sendNotification(
                                "XAU/USD $signal",
                                "Price: $formattedPrice\nSignal: $signal"
                            )
                        }

                        lastGoldSignal = signal
                    }
                }

            } catch (e: Exception) {

                showError(symbol)
            }

        }.start()
    }

    /*
     * STRONG SIGNAL ENGINE
     *
     * Confirmation:
     * 1. EMA trend
     * 2. Price position
     * 3. RSI
     * 4. Momentum
     * 5. Pullback
     * 6. Candle confirmation
     */
    private fun calculateStrongSignal(
        closes: List<Double>,
        opens: List<Double>,
        highs: List<Double>,
        lows: List<Double>,
        ema9: Double,
        ema21: Double,
        rsi: Double,
        momentum: Double
    ): String {

        if (closes.size < 30) {
            return "WAIT"
        }

        val price = closes.last()

        val previousClose =
            closes[closes.size - 2]

        val previousEma9 =
            calculateEMA(
                closes.dropLast(1),
                9
            )

        val previousEma21 =
            calculateEMA(
                closes.dropLast(1),
                21
            )

        var buyScore = 0
        var sellScore = 0

        // ------------------------------------------------
        // 1. EMA TREND
        // ------------------------------------------------

        if (ema9 > ema21) {
            buyScore++
        }

        if (ema9 < ema21) {
            sellScore++
        }

        // ------------------------------------------------
        // 2. EMA TREND STRENGTH
        // ------------------------------------------------

        if (
            ema9 > ema21 &&
            ema9 > previousEma9
        ) {
            buyScore++
        }

        if (
            ema9 < ema21 &&
            ema9 < previousEma9
        ) {
            sellScore++
        }

        // ------------------------------------------------
        // 3. PRICE POSITION
        // ------------------------------------------------

        if (price > ema9) {
            buyScore++
        }

        if (price < ema9) {
            sellScore++
        }

        // ------------------------------------------------
        // 4. RSI CONFIRMATION
        // ------------------------------------------------

        if (rsi >= 52.0 && rsi <= 68.0) {
            buyScore++
        }

        if (rsi <= 48.0 && rsi >= 32.0) {
            sellScore++
        }

        // Extreme RSI = avoid chasing
        if (rsi > 72.0) {
            buyScore--
        }

        if (rsi < 28.0) {
            sellScore--
        }

        // ------------------------------------------------
        // 5. MOMENTUM
        // ------------------------------------------------

        if (momentum > 0) {
            buyScore++
        }

        if (momentum < 0) {
            sellScore++
        }

        // ------------------------------------------------
        // 6. PULLBACK CONFIRMATION
        // ------------------------------------------------

        val previousDistance =
            previousClose - previousEma9

        val currentDistance =
            price - ema9

        /*
         * Bullish pullback:
         * previous candle was near/below EMA9
         * current price recovered above EMA9
         */
        if (
            ema9 > ema21 &&
            previousDistance <= 0 &&
            currentDistance > 0
        ) {
            buyScore += 2
        }

        /*
         * Bearish pullback:
         * previous candle was near/above EMA9
         * current price moved below EMA9
         */
        if (
            ema9 < ema21 &&
            previousDistance >= 0 &&
            currentDistance < 0
        ) {
            sellScore += 2
        }

        // ------------------------------------------------
        // 7. LAST CANDLE CONFIRMATION
        // ------------------------------------------------

        val lastOpen =
            opens.last()

        val lastHigh =
            highs.last()

        val lastLow =
            lows.last()

        val lastClose =
            closes.last()

        val candleBody =
            abs(lastClose - lastOpen)

        val upperWick =
            lastHigh - maxOf(lastOpen, lastClose)

        val lowerWick =
            minOf(lastOpen, lastClose) - lastLow

        // Bullish candle
        if (
            lastClose > lastOpen &&
            candleBody > 0 &&
            lowerWick <= candleBody * 1.5
        ) {
            buyScore++
        }

        // Bearish candle
        if (
            lastClose < lastOpen &&
            candleBody > 0 &&
            upperWick <= candleBody * 1.5
        ) {
            sellScore++
        }

        // ------------------------------------------------
        // FINAL DECISION
        // ------------------------------------------------

        /*
         * Minimum 4 confirmations.
         *
         * Stronger side must also be ahead
         * by at least 1 point.
         */
        return when {

            buyScore >= 4 &&
                    buyScore > sellScore ->
                "BUY"

            sellScore >= 4 &&
                    sellScore > buyScore ->
                "SELL"

            else ->
                "WAIT"
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

            if (change >= 0) {
                gain += change
            } else {
                loss += abs(change)
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
                if (change > 0) change else 0.0

            val currentLoss =
                if (change < 0) abs(change) else 0.0

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
                (100.0 / (1.0 + rs))
    }

    private fun calculateMomentum(
        prices: List<Double>,
        candles: Int
    ): Double {

        if (prices.size <= candles) {
            return 0.0
        }

        return prices.last() -
                prices[prices.size - 1 - candles]
    }

    private fun showError(symbol: String) {

        runOnUiThread {

            if (symbol == "BTC/USD") {

                btcPrice.text = "Price: --"
                btcSignal.text = "Signal: WAIT"

            } else {

                goldPrice.text = "Price: --"
                goldSignal.text = "Signal: WAIT"
            }
        }
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Trading Signals",
                    NotificationManager.IMPORTANCE_HIGH
                )

            channel.description =
                "BTC/USD and XAU/USD BUY/SELL signals"

            val manager =
                getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    private fun sendNotification(
        title: String,
        message: String
    ) {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            if (
                checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setSmallIcon(
                    android.R.drawable.ic_dialog_info
                )
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(message)
                )
                .setPriority(
                    NotificationCompat.PRIORITY_HIGH
                )
                .setAutoCancel(true)
                .build()

        NotificationManagerCompat
            .from(this)
            .notify(
                (System.currentTimeMillis() % 100000).toInt(),
                notification
            )
    }
}
