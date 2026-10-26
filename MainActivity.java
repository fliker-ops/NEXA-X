package com.nexa.dronex;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

public class MainActivity extends Activity {
    private DroneLink link;
    private DroneView view;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        link = new DroneLink(this);
        view = new DroneView(this, link);
        setContentView(view);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        return view.onGamepadMotion(e) || super.dispatchGenericMotionEvent(e);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        return view.onGamepadKey(e) || super.dispatchKeyEvent(e);
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        link.start();
        view.resume();
    }

    @Override
    protected void onPause() {
        // Si sales de la app con el dron en el aire, aterriza por seguridad.
        view.landIfFlying();
        view.pause();
        new android.os.Handler().postDelayed(new Runnable() {
            public void run() {
                if (!view.isShown()) link.stop();
            }
        }, 1500);
        super.onPause();
    }
}
