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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var container: LinearLayout

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())

    private val API_KEY = BuildConfig.TWELVE_DATA_API_KEY

    private var lastBtcSignal = ""
    private var lastGoldSignal = ""

    private val updateRunnable = object : Runnable {
        override fun run() {
            loadSymbol("BTC/USD")
            loadSymbol("XAU/USD")

            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()
        requestNotificationPermission()

        container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(25, 20, 25, 30)

        val scrollView = android.widget.ScrollView(this)
        scrollView.addView(container)

        setContentView(scrollView)

        addTitle()

        handler.post(updateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
    }

    private fun addTitle() {
        val title = TextView(this)

        title.text = "TRADING SIGNAL APP"
        title.textSize = 24f
        title.setPadding(0, 10, 0, 25)

        container.addView(title)
    }

    private fun loadSymbol(symbol: String) {

        if (API_KEY.isBlank()) {
            showError(symbol, "API key missing")
            return
        }

        val url =
            "https://api.twelvedata.com/time_series" +
                    "?symbol=${symbol.replace("/", "%2F")}" +
                    "&interval=5min" +
                    "&outputsize=50" +
                    "&apikey=$API_KEY"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    showError(
                        symbol,
                        "Network error: ${e.message ?: "Unknown error"}"
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {

                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    runOnUiThread {
                        showError(
                            symbol,
                            "Data error Http ${response.code}"
                        )
                    }
                    return
                }

                try {
                    val json = JSONObject(body)

                    if (json.has("code") && json.optInt("code") != 200) {
                        val message =
                            json.optString("message", "Market data error")

                        runOnUiThread {
                            showError(
                                symbol,
                                "Data error: $message"
                            )
                        }
                        return
                    }

                    if (!json.has("values")) {
                        runOnUiThread {
                            showError(
                                symbol,
                                "Market data error"
                            )
                        }
                        return
                    }

                    val values = json.getJSONArray("values")

                    if (values.length() < 25) {
                        runOnUiThread {
                            showError(
                                symbol,
                                "Not enough candle data"
                            )
                        }
                        return
                    }

                    val candles = ArrayList<Candle>()

                    /*
                     * Twelve Data returns newest candle first.
                     * We reverse the order so calculations use
                     * oldest -> newest.
                     */
                    for (i in values.length() - 1 downTo 0) {

                        val item = values.getJSONObject(i)

                        val time = item.optString("datetime")

                        val open = item.optDouble("open")
                        val high = item.optDouble("high")
                        val low = item.optDouble("low")
                        val close = item.optDouble("close")

                        if (
                            open.isFinite() &&
                            high.isFinite() &&
                            low.isFinite() &&
                            close.isFinite()
                        ) {
                            candles.add(
                                Candle(
                                    time,
                                    open,
                                    high,
                                    low,
                                    close
                                )
                            )
                        }
                    }

                    if (candles.size < 25) {
                        runOnUiThread {
                            showError(
                                symbol,
                                "Not enough valid candles"
                            )
                        }
                        return
                    }

                    val analysis = analyze(candles)

                    runOnUiThread {
                        updateUI(symbol, analysis)
                    }

                } catch (e: Exception) {

                    runOnUiThread {
                        showError(
                            symbol,
                            "Parse error: ${e.message ?: "Unknown error"}"
                        )
                    }
                }
            }
        })
    }

    private fun analyze(candles: List<Candle>): Analysis {

        val closes = candles.map { it.close }

        val current = candles.last()
        val previous = candles[candles.size - 2]

        val ema9 = calculateEMA(closes, 9)
        val ema21 = calculateEMA(closes, 21)

        val rsi = calculateRSI(closes, 14)

        val momentum =
            current.close - candles[candles.size - 4].close

        val bullishMomentum = momentum > 0
        val bearishMomentum = momentum < 0

        val bullishTrend = ema9 > ema21
        val bearishTrend = ema9 < ema21

        val priceAboveEMA9 = current.close > ema9
        val priceBelowEMA9 = current.close < ema9

        /*
         * Pullback:
         * Check recent candles against EMA9.
         */
        val recentPullbackCandles =
            candles.takeLast(5)

        val bullishPullback =
            recentPullbackCandles.any {
                it.low <= ema9
            } &&
                    current.close > ema9

        val bearishPullback =
            recentPullbackCandles.any {
                it.high >= ema9
            } &&
                    current.close < ema9

        /*
         * Candle confirmation
         */
        val candleRange =
            current.high - current.low

        val candleBody =
            abs(current.close - current.open)

        val bullishCandle =
            current.close > current.open

        val bearishCandle =
            current.close < current.open

        val strongBullishCandle =
            bullishCandle &&
                    candleRange > 0 &&
                    candleBody / candleRange >= 0.60

        val strongBearishCandle =
            bearishCandle &&
                    candleRange > 0 &&
                    candleBody / candleRange >= 0.60

        /*
         * Support / Resistance
         *
         * Current candle is excluded.
         */
        val srCandles =
            candles.dropLast(1).takeLast(20)

        val support =
            srCandles.minOf { it.low }

        val resistance =
            srCandles.maxOf { it.high }

        /*
         * Liquidity sweep
         */
        val liquidityCandles =
            candles.dropLast(1).takeLast(20)

        val previousLow =
            liquidityCandles.minOf { it.low }

        val previousHigh =
            liquidityCandles.maxOf { it.high }

        val sellSideSweep =
            current.low < previousLow &&
                    current.close > previousLow

        val buySideSweep =
            current.high > previousHigh &&
                    current.close < previousHigh

        /*
         * BUY SCORE
         */
        var buyScore = 0

        if (bullishTrend) buyScore += 15

        if (ema9 > ema21) buyScore += 10

        if (priceAboveEMA9) buyScore += 10

        if (rsi in 52.0..68.0) buyScore += 12

        if (rsi < 28) buyScore -= 5

        if (bullishMomentum) buyScore += 10

        if (bullishPullback) buyScore += 12

        if (strongBullishCandle) buyScore += 10
        else if (bullishCandle) buyScore += 5

        if (sellSideSweep) buyScore += 8

        /*
         * Resistance proximity reduces BUY confidence.
         */
        val distanceToResistance =
            if (resistance != 0.0)
                abs(resistance - current.close) /
                        current.close * 100.0
            else 999.0

        if (distanceToResistance < 0.10) {
            buyScore -= 8
        }

        /*
         * SELL SCORE
         */
        var sellScore = 0

        if (bearishTrend) sellScore += 15

        if (ema9 < ema21) sellScore += 10

        if (priceBelowEMA9) sellScore += 10

        if (rsi in 32.0..48.0) sellScore += 12

        if (rsi > 72) sellScore -= 5

        if (bearishMomentum) sellScore += 10

        if (bearishPullback) sellScore += 12

        if (strongBearishCandle) sellScore += 10
        else if (bearishCandle) sellScore += 5

        if (buySideSweep) sellScore += 8

        /*
         * Support proximity reduces SELL confidence.
         */
        val distanceToSupport =
            if (support != 0.0)
                abs(current.close - support) /
                        current.close * 100.0
            else 999.0

        if (distanceToSupport < 0.10) {
            sellScore -= 8
        }

        /*
         * Keep scores inside 0-100.
         */
        buyScore = buyScore.coerceIn(0, 100)
        sellScore = sellScore.coerceIn(0, 100)

        /*
         * Signal filter
         *
         * We don't want BUY/SELL just because one score
         * is slightly higher.
         */
        val difference =
            abs(buyScore - sellScore)

        val signal = when {

            buyScore >= 70 &&
                    buyScore > sellScore &&
                    difference >= 15 &&
                    bullishTrend &&
                    priceAboveEMA9 ->
                "BUY"

            sellScore >= 70 &&
                    sellScore > buyScore &&
                    difference >= 15 &&
                    bearishTrend &&
                    priceBelowEMA9 ->
                "SELL"

            else ->
                "WAIT"
        }

        val signalScore =
            maxOf(buyScore, sellScore)

        val strength =
            when {
                signal == "WAIT" && signalScore < 40 ->
                    "WEAK"

                signal == "WAIT" ->
                    "MODERATE"

                signalScore >= 85 ->
                    "VERY STRONG"

                signalScore >= 70 ->
                    "STRONG"

                signalScore >= 55 ->
                    "GOOD"

                else ->
                    "WEAK"
            }

        /*
         * Price action description
         */
        val pattern = when {

            strongBullishCandle ->
                "Strong Bullish Body"

            strongBearishCandle ->
                "Strong Bearish Body"

            bullishCandle ->
                "Bullish Candle"

            bearishCandle ->
                "Bearish Candle"

            else ->
                "Doji / Neutral"
        }

        val structure = when {

            current.close > resistance ->
                "Bullish Breakout"

            current.close < support ->
                "Bearish Breakdown"

            current.close > ema21 &&
                    ema9 > ema21 ->
                "Bullish Trend"

            current.close < ema21 &&
                    ema9 < ema21 ->
                "Bearish Trend"

            else ->
                "Mixed/Range"
        }

        return Analysis(
            currentPrice = current.close,
            candleTime = current.time,
            ema9 = ema9,
            ema21 = ema21,
            rsi = rsi,
            momentumPositive = bullishMomentum,
            momentumNegative = bearishMomentum,
            pullbackBullish = bullishPullback,
            pullbackBearish = bearishPullback,
            bullishCandle = bullishCandle,
            bearishCandle = bearishCandle,
            strongBullishCandle = strongBullishCandle,
            strongBearishCandle = strongBearishCandle,
            support = support,
            resistance = resistance,
            sellSideSweep = sellSideSweep,
            buySideSweep = buySideSweep,
            pattern = pattern,
            structure = structure,
            buyScore = buyScore,
            sellScore = sellScore,
            signal = signal,
            signalScore = signalScore,
            strength = strength
        )
    }

    private fun updateUI(
        symbol: String,
        a: Analysis
    ) {

        val header = TextView(this)

        header.text =
            if (symbol == "BTC/USD")
                "BTC/USD (BITCOIN)"
            else
                "XAU/USD (GOLD)"

        header.textSize = 24f
        header.setPadding(0, 30, 0, 15)

        container.addView(header)

        val price = TextView(this)

        price.text =
            String.format(
                Locale.US,
                "5m Close: %.2f",
                a.currentPrice
            )

        price.textSize = 19f
        price.setPadding(0, 5, 0, 15)

        container.addView(price)

        val signalText = TextView(this)

        signalText.text =
            "Signal: ${a.signal} | Score: ${a.signalScore}/100"

        signalText.textSize = 22f
        signalText.setPadding(0, 5, 0, 20)

        container.addView(signalText)

        val details = TextView(this)

        val rsiZone =
            when {
                a.rsi >= 52 ->
                    "BUY zone"

                a.rsi <= 48 ->
                    "SELL zone"

                else ->
                    "Neutral zone"
            }

        val emaDirection =
            when {
                a.ema9 > a.ema21 ->
                    "Bullish ↑"

                a.ema9 < a.ema21 ->
                    "Bearish ↓"

                else ->
                    "Neutral"
            }

        val pricePosition =
            when {
                a.currentPrice > a.ema9 ->
                    "Above EMA9"

                a.currentPrice < a.ema9 ->
                    "Below EMA9"

                else ->
                    "At EMA9"
            }

        val momentumText =
            when {
                a.momentumPositive ->
                    "Positive"

                a.momentumNegative ->
                    "Negative"

                else ->
                    "Neutral"
            }

        val pullbackText =
            when {
                a.pullbackBullish ->
                    "Bullish pullback confirmed"

                a.pullbackBearish ->
                    "Bearish pullback confirmed"

                else ->
                    "No fresh pullback"
            }

        val candleText =
            when {
                a.strongBullishCandle ->
                    "Strong Bullish Body"

                a.strongBearishCandle ->
                    "Strong Bearish Body"

                a.bullishCandle ->
                    "Bullish"

                a.bearishCandle ->
                    "Bearish"

                else ->
                    "Neutral"
            }

        val liquidityText =
            when {
                a.sellSideSweep ->
                    "Sell-side liquidity swept"

                a.buySideSweep ->
                    "Buy-side liquidity swept"

                else ->
                    "No liquidity sweep"
            }

        /*
         * Correct percentage directions:
         *
         * Support is below current price.
         * Resistance is above current price.
         */
        val supportPercent =
            if (a.currentPrice != 0.0)
                abs(a.currentPrice - a.support) /
                        a.currentPrice * 100.0
            else 0.0

        val resistancePercent =
            if (a.currentPrice != 0.0)
                abs(a.resistance - a.currentPrice) /
                        a.currentPrice * 100.0
            else 0.0

        val supportDirection =
            when {
                a.support < a.currentPrice ->
                    "below"

                a.support > a.currentPrice ->
                    "above"

                else ->
                    "at price"
            }

        val resistanceDirection =
            when {
                a.resistance > a.currentPrice ->
                    "above"

                a.resistance < a.currentPrice ->
                    "below"

                else ->
                    "at price"
            }

        details.text =
            """
            
Confirmation Details:
5m Candle: ${a.candleTime}
EMA 9/21: $emaDirection
EMA Strength: $emaDirection
Price Position: $pricePosition
RSI 14: ${String.format(Locale.US, "%.1f", a.rsi)} ($rsiZone)
Momentum: $momentumText
Pullback: $pullbackText
Candle: $candleText

PRICE ACTION:
Pattern: ${a.pattern}
Structure: ${a.structure}

Support: ${
                String.format(
                    Locale.US,
                    "%.2f",
                    a.support
                )
            } (${
                String.format(
                    Locale.US,
                    "%.2f",
                    supportPercent
                )
            }% $supportDirection)

Resistance: ${
                String.format(
                    Locale.US,
                    "%.2f",
                    a.resistance
                )
            } (${
                String.format(
                    Locale.US,
                    "%.2f",
                    resistancePercent
                )
            }% $resistanceDirection)

Liquidity: $liquidityText

BUY Score: ${a.buyScore}/100
SELL Score: ${a.sellScore}/100
Signal: ${a.signal}
Signal Strength: ${a.signalScore}/100
Strength: ${a.strength}

            """.trimIndent()

        details.textSize = 16f

        details.setPadding(
            0,
            0,
            0,
            25
        )

        container.addView(details)

        /*
         * Notification only when a real BUY/SELL appears.
         */
        if (a.signal == "BUY" || a.signal == "SELL") {

            val lastSignal =
                if (symbol == "BTC/USD")
                    lastBtcSignal
                else
                    lastGoldSignal

            if (a.signal != lastSignal) {

                sendNotification(
                    symbol,
                    a.signal,
                    a.signalScore
                )

                if (symbol == "BTC/USD") {
                    lastBtcSignal = a.signal
                } else {
                    lastGoldSignal = a.signal
                }
            }
        }
    }

    private fun showError(
        symbol: String,
        message: String
    ) {

        val header = TextView(this)

        header.text =
            if (symbol == "BTC/USD")
                "BTC/USD (BITCOIN)"
            else
                "XAU/USD (GOLD)"

        header.textSize = 24f
        header.setPadding(0, 30, 0, 10)

        container.addView(header)

        val error = TextView(this)

        error.text =
            """
            Price: --
            Signal: WAIT
            
            $message
            """.trimIndent()

        error.textSize = 17f

        error.setPadding(
            0,
            0,
            0,
            25
        )

        container.addView(error)
    }

    private fun calculateEMA(
        values: List<Double>,
        period: Int
    ): Double {

        if (values.size < period) {
            return values.last()
        }

        val multiplier =
            2.0 / (period + 1)

        var ema =
            values.take(period).average()

        for (i in period until values.size) {

            ema =
                (values[i] - ema) *
                        multiplier +
                        ema
        }

        return ema
    }

    private fun calculateRSI(
        closes: List<Double>,
        period: Int
    ): Double {

        if (closes.size <= period) {
            return 50.0
        }

        var gains = 0.0
        var losses = 0.0

        for (i in 1..period) {

            val change =
                closes[i] - closes[i - 1]

            if (change > 0) {
                gains += change
            } else {
                losses += abs(change)
            }
        }

        var averageGain =
            gains / period

        var averageLoss =
            losses / period

        for (i in period + 1 until closes.size) {

            val change =
                closes[i] - closes[i - 1]

            val gain =
                if (change > 0) change else 0.0

            val loss =
                if (change < 0) abs(change) else 0.0

            averageGain =
                (averageGain * (period - 1) + gain) /
                        period

            averageLoss =
                (averageLoss * (period - 1) + loss) /
                        period
        }

        if (averageLoss == 0.0) {
            return 100.0
        }

        val rs =
            averageGain / averageLoss

        return 100.0 - (100.0 / (1.0 + rs))
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel =
                NotificationChannel(
                    "trading_signal_channel",
                    "Trading Signals",
                    NotificationManager.IMPORTANCE_HIGH
                )

            channel.description =
                "BUY and SELL trading signal notifications"

            val manager =
                getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermission() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            if (
                checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.POST_NOTIFICATIONS
                    ),
                    1001
                )
            }
        }
    }

    private fun sendNotification(
        symbol: String,
        signal: String,
        score: Int
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

        val manager =
            getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

        val notification =
            NotificationCompat.Builder(
                this,
                "trading_signal_channel"
            )
                .setSmallIcon(
                    android.R.drawable.ic_dialog_info
                )
                .setContentTitle(
                    "$symbol $signal SIGNAL"
                )
                .setContentText(
                    "Signal strength: $score/100"
                )
                .setPriority(
                    NotificationCompat.PRIORITY_HIGH
                )
                .setAutoCancel(true)
                .build()

        manager.notify(
            if (symbol == "BTC/USD") 101 else 102,
            notification
        )
    }

    data class Candle(
        val time: String,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double
    )

    data class Analysis(
        val currentPrice: Double,
        val candleTime: String,

        val ema9: Double,
        val ema21: Double,
        val rsi: Double,

        val momentumPositive: Boolean,
        val momentumNegative: Boolean,

        val pullbackBullish: Boolean,
        val pullbackBearish: Boolean,

        val bullishCandle: Boolean,
        val bearishCandle: Boolean,

        val strongBullishCandle: Boolean,
        val strongBearishCandle: Boolean,

        val support: Double,
        val resistance: Double,

        val sellSideSweep: Boolean,
        val buySideSweep: Boolean,

        val pattern: String,
        val structure: String,

        val buyScore: Int,
        val sellScore: Int,

        val signal: String,
        val signalScore: Int,
        val strength: String
    )
}
