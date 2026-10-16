package com.netline.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import java.io.InputStream;

// Separate test APK/UID, with no storage permission or dependency on the target APK runtime.
public class ReportReceiverActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String message;
        try {
            Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                Bitmap bitmap = BitmapFactory.decodeStream(stream);
                message = "PNG OK " + bitmap.getWidth() + " x " + bitmap.getHeight();
                bitmap.recycle();
            }
        } catch (Exception error) { message = "PNG FAILED " + error.getMessage(); }
        TextView label = new TextView(this);
        label.setText(message);
        label.setTextSize(24);
        label.setPadding(40, 120, 40, 40);
        setContentView(label);
    }
}
