package com.moshnpink420.tradingsignalapp

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this)
        textView.text = "Trading Signal App\n\nApp is working!"
        textView.textSize = 24f
        textView.setPadding(40, 80, 40, 40)

        setContentView(textView)
    }
}
