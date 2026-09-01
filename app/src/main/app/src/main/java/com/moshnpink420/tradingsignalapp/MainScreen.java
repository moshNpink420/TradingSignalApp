package com.moshnpink420.tradingsignalapp;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainScreen extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView text = new TextView(this);
        text.setText("Trading Signal App\n\nApp is working!");
        text.setTextSize(24);
        text.setPadding(40, 80, 40, 40);

        setContentView(text);
    }
}
