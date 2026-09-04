package com.moshnpink420.tradingsignalapp

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        Toast.makeText(
            this,
            "Trading Signal App Ready",
            Toast.LENGTH_SHORT
        ).show()
    }
}
