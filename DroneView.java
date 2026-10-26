package com.nexa.dronex;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Vibrator;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * NEXA-X: interfaz estilo terminal (gris y blanco), joysticks táctiles, mando Bluetooth
 * con botones configurables y maniobras automáticas.
 */
public class DroneView extends View {
    static final int WHITE = 0xFFF2F2F2, G1 = 0xFFB4B4B4, G2 = 0xFF707070, G3 = 0x40FFFFFF, G4 = 0x14FFFFFF;

    private final DroneLink link;
    private final SharedPreferences prefs;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);
    private final Typeface monoB = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    private final float dp;

    private final Stick left = new Stick(), right = new Stick();
    private boolean flying, paused, showConfig;
    private long flightStart, stopPressStart = -1, lastFrame;
    private float scan, heading;
    private int speedIdx;
    private static final float[] SPEEDS = {0.3f, 0.6f, 1.0f};
    private boolean invPitch, invRoll, invYaw, invThr, mode1;

    // Mando Bluetooth
    private float gpLx, gpLy, gpRx, gpRy;
    private String gpName;
    private long gpScan, stopComboStart = -1, stopKeyStart = -1;
    private boolean gpL1, gpR1;
    private int hatX, hatY;

    // Mapeo de botones: acción -> keycode
    static final String[] ACTIONS = {"DESPEGAR", "ATERRIZAR", "VELOCIDAD", "CANCELAR IA", "PARADA (1s)",
            "GIRO 360", "CUADRADO", "CÍRCULO", "SUBE/BAJA"};
    static final int[] DEFAULT_KEYS = {KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y, -1, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP};
    static final int A_TAKEOFF = 0, A_LAND = 1, A_SPEED = 2, A_CANCEL = 3, A_STOP = 4, A_MANEUVER0 = 5;
    private final int[] keys = new int[ACTIONS.length];
    private int capturing = -1;  // acción esperando un botón

    // Maniobras automáticas y consola
    private Maneuver auto;
    private long autoStart;
    private final ArrayList<String> log = new ArrayList<String>();
    private final SimpleDateFormat hhmmss = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private final RectF bTakeoff = new RectF(), bLand = new RectF(), bStop = new RectF(), bSpeed = new RectF(),
            bConfig = new RectF(), panel = new RectF(), bClose = new RectF(), bReset = new RectF();
    private final RectF[] bAuto = {new RectF(), new RectF(), new RectF(), new RectF()};
    private final RectF[] bAxis = {new RectF(), new RectF(), new RectF(), new RectF(), new RectF()};
    private final RectF[] bMap = new RectF[ACTIONS.length];

    static class Stick {
        float cx, cy, r, x, y;
        int pointer = -1;
    }

    /** Secuencia temporizada: {segundos, roll, pitch, throttle, yaw}. */
    static class Maneuver {
        final String name;
        final float[][] steps;

        Maneuver(String name, float[][] steps) {
            this.name = name;
            this.steps = steps;
        }

        float total() {
            float t = 0;
            for (float[] s : steps) t += s[0];
            return t;
        }

        float[] at(float t) {
            for (float[] s : steps) {
                if (t < s[0]) return s;
                t -= s[0];
            }
            return null;
        }
    }

    static final Maneuver[] MANEUVERS = {
            new Maneuver("GIRO 360", new float[][]{{4.0f, 0, 0, 0, 0.7f}, {1f, 0, 0, 0, 0}}),
            new Maneuver("CUADRADO", square()),
            new Maneuver("CÍRCULO", new float[][]{{8f, 0, 0.45f, 0, 0.45f}, {1f, 0, 0, 0, 0}}),
            new Maneuver("SUBE/BAJA", new float[][]{{1.2f, 0, 0, 0.6f, 0}, {1f, 0, 0, 0, 0}, {1.2f, 0, 0, -0.5f, 0}, {1f, 0, 0, 0, 0}}),
    };

    static float[][] square() {
        float[][] s = new float[16][];
        for (int i = 0; i < 4; i++) {
            s[i * 4] = new float[]{1.5f, 0, 0.5f, 0, 0};
            s[i * 4 + 1] = new float[]{0.6f, 0, 0, 0, 0};
            s[i * 4 + 2] = new float[]{1.1f, 0, 0, 0, 0.7f};
            s[i * 4 + 3] = new float[]{0.6f, 0, 0, 0, 0};
        }
        return s;
    }

    public DroneView(Context c, DroneLink link) {
        super(c);
        this.link = link;
        dp = c.getResources().getDisplayMetrics().density;
        prefs = c.getSharedPreferences("nexax", Context.MODE_PRIVATE);
        invPitch = prefs.getBoolean("invPitch", false);
        invRoll = prefs.getBoolean("invRoll", false);
        invYaw = prefs.getBoolean("invYaw", false);
        invThr = prefs.getBoolean("invThr", false);
        mode1 = prefs.getBoolean("mode1", false);
        speedIdx = prefs.getInt("speed", 1);
        link.speedScale = SPEEDS[speedIdx];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = prefs.getInt("key" + i, DEFAULT_KEYS[i]);
            bMap[i] = new RectF();
        }
        setFocusable(true);
        say("nexa-x iniciado");
        say("esperando enlace con el dron...");
    }

    public void resume() {
        paused = false;
        invalidate();
    }

    public void pause() {
        paused = true;
    }

    public void landIfFlying() {
        if (flying) {
            auto = null;
            link.takeoffOrLand();
            flying = false;
        }
    }

    private void buzz(long ms) {
        try {
            Vibrator v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(ms);
        } catch (Exception ignored) {
        }
    }

    private void say(String s) {
        log.add(hhmmss.format(new Date()) + " > " + s.toLowerCase(Locale.ROOT));
        while (log.size() > 6) log.remove(0);
    }

    // ------------------------------------------------------------------ layout
    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float r = Math.min(h * 0.23f, w * 0.13f);
        left.r = right.r = r;
        left.cx = r + 40 * dp;
        right.cx = w - r - 40 * dp;
        left.cy = right.cy = h - r - 30 * dp;

        float bw = Math.min(140 * dp, w * 0.15f), bh = 44 * dp, gap = 12 * dp, by = h - bh - 24 * dp;
        float cx = w / 2f;
        bTakeoff.set(cx - bw * 1.5f - gap, by, cx - bw * 0.5f - gap, by + bh);
        bStop.set(cx - bw / 2, by, cx + bw / 2, by + bh);
        bLand.set(cx + bw * 0.5f + gap, by, cx + bw * 1.5f + gap, by + bh);

        float top = 70 * dp, ch = 30 * dp, cw = 112 * dp;
        for (int i = 0; i < 4; i++) {
            float x = w - 16 * dp - cw;
            bAuto[i].set(x, top + 14 * dp + i * (ch + 7 * dp), x + cw, top + 14 * dp + i * (ch + 7 * dp) + ch);
        }
        bSpeed.set(16 * dp, top, 16 * dp + 72 * dp, top + ch);
        bConfig.set(16 * dp + 80 * dp, top, 16 * dp + 80 * dp + 72 * dp, top + ch);

        // Pantalla de configuración
        panel.set(20 * dp, 16 * dp, w - 20 * dp, h - 16 * dp);
        float colW = (panel.width() - 72 * dp) / 2, lx = panel.left + 24 * dp, rx = lx + colW + 24 * dp;
        float rowH = Math.min(38 * dp, (panel.height() - 120 * dp) / ACTIONS.length);
        for (int i = 0; i < bAxis.length; i++)
            bAxis[i].set(lx, panel.top + 74 * dp + i * 42 * dp, lx + colW, panel.top + 108 * dp + i * 42 * dp);
        for (int i = 0; i < ACTIONS.length; i++)
            bMap[i].set(rx, panel.top + 74 * dp + i * rowH, rx + colW, panel.top + 74 * dp + i * rowH + rowH - 4 * dp);
        bClose.set(panel.right - 24 * dp - 110 * dp, panel.bottom - 46 * dp, panel.right - 24 * dp, panel.bottom - 14 * dp);
        bReset.set(lx, panel.bottom - 46 * dp, lx + 150 * dp, panel.bottom - 14 * dp);
    }

    // ------------------------------------------------------------------ dibujo
    @Override
    protected void onDraw(Canvas c) {
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        updateControls(now, dt);
        int w = getWidth(), h = getHeight();

        // Fondo: grafito + rejilla de puntos + línea de escaneo
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF0A0A0B, 0xFF19191C, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        p.setColor(G4);
        float g = 24 * dp;
        for (float x = (w / 2f) % g; x < w; x += g)
            for (float y = (h / 2f) % g; y < h; y += g) c.drawCircle(x, y, 0.9f * dp, p);
        scan = (scan + dt * 0.18f) % 1f;
        p.setShader(new LinearGradient(0, scan * h - 30 * dp, 0, scan * h, 0x00FFFFFF, 0x0EFFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, scan * h - 30 * dp, w, scan * h, p);
        p.setShader(null);

        drawTopBar(c, w, now);
        drawConsole(c);
        drawHud(c, w, h);
        String[] lab = mode1 ? new String[]{"AVANCE·GIRO", "ALTURA·LATERAL"} : new String[]{"ALTURA·GIRO", "AVANCE·LATERAL"};
        drawStick(c, left, lab[0]);
        drawStick(c, right, lab[1]);
        drawButton(c, bTakeoff, "DESPEGAR", !flying, false);
        drawButton(c, bLand, "ATERRIZAR", flying, false);
        drawStop(c, now);
        drawButton(c, bSpeed, "VEL " + Math.round(SPEEDS[speedIdx] * 100) + "%", true, false);
        drawButton(c, bConfig, "CONFIG", true, false);
        text(c, "// PILOTO_IA", bAuto[0].left, bAuto[0].top - 8 * dp, 10, G2, Paint.Align.LEFT, false);
        for (int i = 0; i < 4; i++) drawButton(c, bAuto[i], MANEUVERS[i].name, flying || auto == MANEUVERS[i], auto == MANEUVERS[i]);
        if (auto != null) {
            float prog = (now - autoStart) / 1000f / auto.total();
            text(c, "[ PILOTO_IA :: " + auto.name + " :: " + Math.round(prog * 100) + "% ]  joystick = cancelar",
                    w / 2f, bTakeoff.top - 14 * dp, 11, WHITE, Paint.Align.CENTER, true);
        }
        long stopStart = stopComboStart >= 0 ? stopComboStart : stopKeyStart;
        if (stopStart >= 0) {
            float prog = Math.min(1f, (now - stopStart) / 1000f);
            text(c, "[ PARADA DE MOTORES " + Math.round(prog * 100) + "% ]", w / 2f, h * 0.3f, 18, WHITE, Paint.Align.CENTER, true);
            if (prog >= 1f) {
                stopComboStart = stopKeyStart = -1;
                doEmergencyStop();
            }
        }
        if (showConfig) drawConfig(c, w, h, now);
        if (!paused) postInvalidateOnAnimation();
    }

    private void drawTopBar(Canvas c, int w, long now) {
        float y = 30 * dp;
        text(c, "NEXA-X", 18 * dp, y, 20, WHITE, Paint.Align.LEFT, true);
        p.setTextSize(20 * dp);
        float tw = p.measureText("NEXA-X");
        if ((now / 530) % 2 == 0) text(c, "_", 18 * dp + tw + 2 * dp, y, 20, WHITE, Paint.Align.LEFT, true);
        text(c, "root@dron:~ v2.0", 18 * dp, y + 16 * dp, 9, G2, Paint.Align.LEFT, false);
        text(c, "pad: " + (gpName != null ? shorten(gpName).toLowerCase(Locale.ROOT) : "-- sin mando --"),
                18 * dp, y + 30 * dp, 9, gpName != null ? G1 : G2, Paint.Align.LEFT, false);

        String st;
        int col = WHITE;
        if (link.droneReplying()) st = "[ LINK :: OK ]";
        else if (DroneLink.onDroneWifi()) st = "[ WIFI DRON :: OK ]";
        else {
            st = "[ SIN RED :: CONECTA A FLOW_ ]";
            col = (now / 500) % 2 == 0 ? WHITE : G2;
        }
        text(c, st, w / 2f, y, 13, col, Paint.Align.CENTER, true);
        String t = "T+00:00";
        if (flying) {
            long s = (now - flightStart) / 1000;
            t = String.format(Locale.US, "T+%02d:%02d", s / 60, s % 60);
        }
        text(c, t, w - 18 * dp, y, 16, flying ? WHITE : G2, Paint.Align.RIGHT, true);
        text(c, flying ? "estado: en vuelo" : "estado: en tierra", w - 18 * dp, y + 16 * dp, 9, G1, Paint.Align.RIGHT, false);
        p.setColor(G3);
        p.setStrokeWidth(1);
        c.drawLine(16 * dp, y + 36 * dp, w - 16 * dp, y + 36 * dp, p);
    }

    private void drawConsole(Canvas c) {
        float x = 16 * dp, y = bSpeed.bottom + 20 * dp;
        float maxY = left.cy - left.r - 24 * dp;
        for (int i = 0; i < log.size(); i++) {
            float yy = y + i * 13 * dp;
            if (yy > maxY) break;
            text(c, log.get(i), x, yy, 9, i == log.size() - 1 ? WHITE : G2, Paint.Align.LEFT, false);
        }
    }

    private void drawHud(Canvas c, int w, int h) {
        float cx = w / 2f, cy = h * 0.43f, r = Math.min(h * 0.21f, w * 0.12f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1 * dp);
        p.setColor(G3);
        c.drawCircle(cx, cy, r, p);
        p.setPathEffect(new DashPathEffect(new float[]{4 * dp, 6 * dp}, 0));
        c.drawCircle(cx, cy, r * 0.6f, p);
        p.setPathEffect(null);
        for (int i = 0; i < 72; i++) {
            double a = Math.toRadians(i * 5 + heading);
            boolean big = i % 18 == 0;
            float r1 = r * (big ? 0.85f : (i % 2 == 0 ? 0.93f : 0.96f));
            p.setStrokeWidth(big ? 2 * dp : 1 * dp);
            p.setColor(big ? WHITE : G2);
            c.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1,
                    cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, p);
        }
        // cruz central y vector de movimiento
        p.setColor(G2);
        p.setStrokeWidth(1);
        c.drawLine(cx - r * 1.25f, cy, cx - r * 1.05f, cy, p);
        c.drawLine(cx + r * 1.05f, cy, cx + r * 1.25f, cy, p);
        float vx = link.roll * r * 0.55f, vy = -link.pitch * r * 0.55f;
        p.setColor(WHITE);
        p.setStrokeWidth(2 * dp);
        c.drawLine(cx, cy, cx + vx, cy + vy, p);
        p.setStyle(Paint.Style.FILL);
        c.drawRect(cx + vx - 3 * dp, cy + vy - 3 * dp, cx + vx + 3 * dp, cy + vy + 3 * dp, p);
        p.setStyle(Paint.Style.STROKE);
        float d = r * 0.15f;
        c.drawLine(cx - d, cy - d, cx + d, cy + d, p);
        c.drawLine(cx - d, cy + d, cx + d, cy - d, p);
        p.setStrokeWidth(1.2f * dp);
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sy = -1; sy <= 1; sy += 2) c.drawCircle(cx + sx * d, cy + sy * d, d * 0.45f, p);
        p.setStyle(Paint.Style.FILL);
        text(c, String.format(Locale.US, "HDG %03d", (int) ((heading % 360 + 360) % 360)), cx, cy - r - 10 * dp, 10, G1,
                Paint.Align.CENTER, false);

        // Lecturas de mando estilo terminal
        String[] names = {"THR", "YAW", "PIT", "ROL"};
        float[] vals = {link.throttle, link.yaw, link.pitch, link.roll};
        for (int i = 0; i < 4; i++) {
            boolean lft = i < 2;
            float x = lft ? cx - r * 1.45f - 110 * dp : cx + r * 1.45f;
            float yy = cy - 10 * dp + (i % 2) * 22 * dp;
            int n = Math.round(Math.abs(vals[i]) * 6);
            StringBuilder bar = new StringBuilder();
            for (int k = 0; k < 6; k++) bar.append(k < n ? '█' : '·');
            text(c, names[i] + (vals[i] < 0 ? " -" : " +") + bar, x, yy, 11, n > 0 ? WHITE : G2, Paint.Align.LEFT, false);
        }
    }

    private void drawStick(Canvas c, Stick s, String label) {
        if (s.r == 0) return;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x0CFFFFFF);
        c.drawCircle(s.cx, s.cy, s.r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f * dp);
        p.setColor(G1);
        c.drawCircle(s.cx, s.cy, s.r, p);
        p.setColor(G3);
        p.setPathEffect(new DashPathEffect(new float[]{3 * dp, 5 * dp}, 0));
        c.drawCircle(s.cx, s.cy, s.r * 0.55f, p);
        p.setPathEffect(null);
        c.drawLine(s.cx - s.r, s.cy, s.cx + s.r, s.cy, p);
        c.drawLine(s.cx, s.cy - s.r, s.cx, s.cy + s.r, p);
        float kx = s.cx + s.x * s.r * 0.75f, ky = s.cy - s.y * s.r * 0.75f, kr = s.r * 0.27f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(s.pointer != -1 ? WHITE : 0xFFD8D8D8);
        c.drawCircle(kx, ky, kr, p);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(0xFF0A0A0B);
        p.setStrokeWidth(1.5f * dp);
        c.drawCircle(kx, ky, kr * 0.6f, p);
        p.setStyle(Paint.Style.FILL);
        text(c, "[ " + label + " ]", s.cx, s.cy - s.r - 10 * dp, 10, G1, Paint.Align.CENTER, false);
    }

    /** Rectángulo con esquinas tipo visor. */
    private void frame(Canvas c, RectF r, int col, boolean filled) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(filled ? 0xFFF2F2F2 : 0x0FFFFFFF);
        c.drawRect(r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor((col & 0x00FFFFFF) | 0x50000000);
        c.drawRect(r, p);
        p.setStrokeWidth(2 * dp);
        p.setColor(col);
        float k = Math.min(9 * dp, r.height() / 3);
        c.drawLine(r.left, r.top, r.left + k, r.top, p);
        c.drawLine(r.left, r.top, r.left, r.top + k, p);
        c.drawLine(r.right, r.top, r.right - k, r.top, p);
        c.drawLine(r.right, r.top, r.right, r.top + k, p);
        c.drawLine(r.left, r.bottom, r.left + k, r.bottom, p);
        c.drawLine(r.left, r.bottom, r.left, r.bottom - k, p);
        c.drawLine(r.right, r.bottom, r.right - k, r.bottom, p);
        c.drawLine(r.right, r.bottom, r.right, r.bottom - k, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawButton(Canvas c, RectF r, String label, boolean enabled, boolean active) {
        frame(c, r, enabled ? WHITE : G2, active);
        float size = r.height() > 38 * dp ? 13 : 10;
        text(c, label, r.centerX(), r.centerY() + size * 0.36f * dp, size, active ? 0xFF0A0A0B : (enabled ? WHITE : G2),
                Paint.Align.CENTER, true);
    }

    private void drawStop(Canvas c, long now) {
        drawButton(c, bStop, "PARADA", true, false);
        text(c, "mantener 1s", bStop.centerX(), bStop.bottom + 13 * dp, 9, G2, Paint.Align.CENTER, false);
        if (stopPressStart >= 0) {
            float prog = Math.min(1f, (now - stopPressStart) / 1000f);
            p.setColor(0xCCF2F2F2);
            c.drawRect(bStop.left, bStop.top, bStop.left + bStop.width() * prog, bStop.bottom, p);
            if (prog >= 1f) {
                stopPressStart = -1;
                doEmergencyStop();
            }
        }
    }

    private void drawConfig(Canvas c, int w, int h, long now) {
        p.setColor(0xF00A0A0B);
        c.drawRect(0, 0, w, h, p);
        frame(c, panel, WHITE, false);
        text(c, "> CONFIG" + ((now / 530) % 2 == 0 ? "_" : ""), panel.left + 24 * dp, panel.top + 34 * dp, 16, WHITE,
                Paint.Align.LEFT, true);
        text(c, "// PALANCAS", bAxis[0].left, bAxis[0].top - 12 * dp, 10, G2, Paint.Align.LEFT, false);
        text(c, "// BOTONES DEL MANDO  (toca una acción y pulsa el botón)", bMap[0].left, bMap[0].top - 12 * dp, 10, G2,
                Paint.Align.LEFT, false);

        String[] axisNames = {"Modo de palancas", "Invertir avance", "Invertir lateral", "Invertir giro", "Invertir altura"};
        String[] axisVals = {mode1 ? "MODO 1" : "MODO 2", onOff(invPitch), onOff(invRoll), onOff(invYaw), onOff(invThr)};
        for (int i = 0; i < bAxis.length; i++) {
            RectF r = bAxis[i];
            frame(c, r, G1, false);
            text(c, axisNames[i], r.left + 10 * dp, r.centerY() + 4 * dp, 11, WHITE, Paint.Align.LEFT, false);
            text(c, "[" + axisVals[i] + "]", r.right - 10 * dp, r.centerY() + 4 * dp, 11, WHITE, Paint.Align.RIGHT, true);
        }
        text(c, mode1 ? "modo 1: izq = avance/giro · der = altura/lateral" : "modo 2: izq = altura/giro · der = avance/lateral",
                bAxis[0].left, bAxis[bAxis.length - 1].bottom + 20 * dp, 9, G2, Paint.Align.LEFT, false);

        for (int i = 0; i < ACTIONS.length; i++) {
            RectF r = bMap[i];
            boolean cap = capturing == i;
            frame(c, r, cap ? WHITE : G1, cap);
            int tc = cap ? 0xFF0A0A0B : WHITE;
            text(c, ACTIONS[i], r.left + 10 * dp, r.centerY() + 4 * dp, 11, tc, Paint.Align.LEFT, false);
            String k = cap ? ((now / 400) % 2 == 0 ? "PULSA UN BOTÓN..." : "") : keyLabel(i);
            text(c, k, r.right - 10 * dp, r.centerY() + 4 * dp, 11, tc, Paint.Align.RIGHT, true);
        }
        if (gpName == null)
            text(c, "! no hay mando conectado: emparéjalo en el bluetooth del teléfono", bMap[0].left,
                    panel.bottom - 56 * dp, 9, G1, Paint.Align.LEFT, false);
        drawButton(c, bReset, "RESTABLECER", true, false);
        drawButton(c, bClose, "CERRAR", true, false);
    }

    private static String onOff(boolean b) {
        return b ? "SÍ" : "NO";
    }

    private String keyLabel(int action) {
        int k = keys[action];
        if (action == A_STOP) return k < 0 ? "LB+RB" : keyName(k) + " / LB+RB";
        return k < 0 ? "--" : keyName(k);
    }

    static String keyName(int code) {
        String s = KeyEvent.keyCodeToString(code);
        s = s.replace("KEYCODE_BUTTON_", "").replace("KEYCODE_DPAD_", "CRUCETA ").replace("KEYCODE_", "");
        if (s.equals("L1")) return "LB";
        if (s.equals("R1")) return "RB";
        if (s.equals("L2")) return "LT";
        if (s.equals("R2")) return "RT";
        if (s.equals("THUMBL")) return "L3";
        if (s.equals("THUMBR")) return "R3";
        if (s.equals("CRUCETA UP")) return "CRUCETA ↑";
        if (s.equals("CRUCETA DOWN")) return "CRUCETA ↓";
        if (s.equals("CRUCETA LEFT")) return "CRUCETA ←";
        if (s.equals("CRUCETA RIGHT")) return "CRUCETA →";
        return s;
    }

    private void text(Canvas c, String s, float x, float y, float sizeDp, int col, Paint.Align al, boolean bold) {
        p.setTypeface(bold ? monoB : mono);
        p.setTextSize(sizeDp * dp);
        p.setColor(col);
        p.setTextAlign(al);
        p.setStyle(Paint.Style.FILL);
        c.drawText(s, x, y, p);
    }

    // ------------------------------------------------------------------ mandos
    private void updateControls(long now, float dt) {
        if (now - gpScan > 1000) {
            gpScan = now;
            String n = findGamepad();
            if (n != null && gpName == null) say("mando conectado: " + shorten(n));
            if (n == null && gpName != null) say("mando desconectado");
            gpName = n;
            if (gpName == null) gpLx = gpLy = gpRx = gpRy = 0;
        }
        float roll, pitch, thr, yaw;
        if (auto != null) {
            float[] s = auto.at((now - autoStart) / 1000f);
            if (s == null) {
                say(auto.name + " completado");
                auto = null;
                roll = pitch = thr = yaw = 0;
            } else {
                roll = s[1];
                pitch = s[2];
                thr = s[3];
                yaw = s[4];
            }
        } else {
            float lx = left.pointer != -1 ? left.x : gpLx, ly = left.pointer != -1 ? left.y : gpLy;
            float rx = right.pointer != -1 ? right.x : gpRx, ry = right.pointer != -1 ? right.y : gpRy;
            yaw = lx;
            roll = rx;
            if (mode1) {
                pitch = ly;
                thr = ry;
            } else {
                thr = ly;
                pitch = ry;
            }
        }
        heading = (heading + yaw * 90 * dt * SPEEDS[speedIdx]) % 360;
        link.roll = invRoll ? -roll : roll;
        link.pitch = invPitch ? -pitch : pitch;
        link.throttle = invThr ? -thr : thr;
        link.yaw = invYaw ? -yaw : yaw;
    }

    // ------------------------------------------------------------------ acciones
    private void doTakeoff() {
        if (flying) return;
        link.takeoffOrLand();
        flying = true;
        flightStart = System.currentTimeMillis();
        buzz(60);
        say("despegando");
    }

    private void doLand() {
        if (!flying) return;
        auto = null;
        link.takeoffOrLand();
        flying = false;
        buzz(60);
        say("aterrizando");
    }

    private void doEmergencyStop() {
        auto = null;
        left.x = left.y = right.x = right.y = 0;
        link.emergencyStop();
        flying = false;
        buzz(400);
        say("!! motores parados");
    }

    private void cycleSpeed() {
        speedIdx = (speedIdx + 1) % SPEEDS.length;
        link.speedScale = SPEEDS[speedIdx];
        prefs.edit().putInt("speed", speedIdx).apply();
        buzz(20);
        say("velocidad " + Math.round(SPEEDS[speedIdx] * 100) + "%");
    }

    private void cancelAuto() {
        if (auto != null) {
            auto = null;
            say("piloto_ia cancelado");
        }
    }

    private void startManeuver(int i) {
        if (!flying) {
            say("primero despega");
            return;
        }
        auto = MANEUVERS[i];
        autoStart = System.currentTimeMillis();
        buzz(40);
        say("piloto_ia: " + MANEUVERS[i].name);
    }

    private void runAction(int a) {
        if (a == A_TAKEOFF) doTakeoff();
        else if (a == A_LAND) doLand();
        else if (a == A_SPEED) cycleSpeed();
        else if (a == A_CANCEL) cancelAuto();
        else if (a >= A_MANEUVER0) startManeuver(a - A_MANEUVER0);
    }

    private void toggleAxis(int i) {
        if (i == 0) mode1 = !mode1;
        if (i == 1) invPitch = !invPitch;
        if (i == 2) invRoll = !invRoll;
        if (i == 3) invYaw = !invYaw;
        if (i == 4) invThr = !invThr;
        saveConfig();
        buzz(20);
    }

    private void saveConfig() {
        SharedPreferences.Editor e = prefs.edit();
        e.putBoolean("mode1", mode1).putBoolean("invPitch", invPitch).putBoolean("invRoll", invRoll)
                .putBoolean("invYaw", invYaw).putBoolean("invThr", invThr);
        for (int i = 0; i < keys.length; i++) e.putInt("key" + i, keys[i]);
        e.apply();
    }

    private void assignKey(int action, int code) {
        for (int i = 0; i < keys.length; i++)
            if (i != action && keys[i] == code) keys[i] = keys[action];  // si ya estaba usado, se intercambian
        keys[action] = code;
        saveConfig();
        say(ACTIONS[action] + " -> " + keyName(code));
        buzz(30);
    }

    // ------------------------------------------------------------------ táctil
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        int idx = e.getActionIndex();
        int id = e.getPointerId(idx);
        float x = e.getX(idx), y = e.getY(idx);
        switch (a) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (showConfig) {
                    for (int i = 0; i < bAxis.length; i++) if (bAxis[i].contains(x, y)) toggleAxis(i);
                    for (int i = 0; i < bMap.length; i++)
                        if (bMap[i].contains(x, y)) capturing = capturing == i ? -1 : i;
                    if (bReset.contains(x, y)) {
                        System.arraycopy(DEFAULT_KEYS, 0, keys, 0, keys.length);
                        mode1 = invPitch = invRoll = invYaw = invThr = false;
                        saveConfig();
                        capturing = -1;
                        say("configuración restablecida");
                    }
                    if (bClose.contains(x, y)) {
                        showConfig = false;
                        capturing = -1;
                    }
                    return true;
                }
                if (grab(left, id, x, y) || grab(right, id, x, y)) {
                    cancelAuto();
                    return true;
                }
                if (bStop.contains(x, y)) {
                    stopPressStart = System.currentTimeMillis();
                    buzz(30);
                } else if (bTakeoff.contains(x, y)) {
                    doTakeoff();
                } else if (bLand.contains(x, y)) {
                    doLand();
                } else if (bSpeed.contains(x, y)) {
                    cycleSpeed();
                } else if (bConfig.contains(x, y)) {
                    showConfig = true;
                } else {
                    for (int i = 0; i < 4; i++) if (bAuto[i].contains(x, y)) startManeuver(i);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int pid = e.getPointerId(i);
                    move(left, pid, e.getX(i), e.getY(i));
                    move(right, pid, e.getX(i), e.getY(i));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    release(left, -2);
                    release(right, -2);
                    stopPressStart = -1;
                } else {
                    release(left, id);
                    release(right, id);
                    if (bStop.contains(x, y)) stopPressStart = -1;
                }
                return true;
        }
        return super.onTouchEvent(e);
    }

    private boolean grab(Stick s, int id, float x, float y) {
        if (s.pointer == -1 && Math.hypot(x - s.cx, y - s.cy) < s.r * 1.25f) {
            s.pointer = id;
            move(s, id, x, y);
            return true;
        }
        return false;
    }

    private void move(Stick s, int id, float x, float y) {
        if (s.pointer != id) return;
        float dx = (x - s.cx) / (s.r * 0.75f), dy = (s.cy - y) / (s.r * 0.75f);
        float m = (float) Math.hypot(dx, dy);
        if (m > 1) {
            dx /= m;
            dy /= m;
        }
        s.x = Math.abs(dx) < 0.06f ? 0 : dx;
        s.y = Math.abs(dy) < 0.06f ? 0 : dy;
    }

    private void release(Stick s, int id) {
        if (id == -2 || s.pointer == id) {
            s.pointer = -1;
            s.x = s.y = 0;
        }
    }

    // ------------------------------------------------------------------ mando Bluetooth
    private static boolean isPad(int src) {
        return (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (src & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private static String findGamepad() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return d.getName();
        }
        return null;
    }

    private static String shorten(String n) {
        return n.length() > 24 ? n.substring(0, 24) + "…" : n;
    }

    private static float dead(float v) {
        float dz = 0.12f;
        if (Math.abs(v) < dz) return 0;
        return Math.signum(v) * (Math.abs(v) - dz) / (1 - dz);
    }

    /** ¿Es este eje un joystick (-1 a 1) y no un gatillo (0 a 1)? */
    private static boolean isStickAxis(InputDevice d, int axis, int src) {
        if (d == null) return false;
        InputDevice.MotionRange r = d.getMotionRange(axis, src);
        return r != null && r.getMin() < -0.5f;
    }

    public boolean onGamepadMotion(MotionEvent e) {
        if (!isPad(e.getSource()) || e.getActionMasked() != MotionEvent.ACTION_MOVE) return false;
        InputDevice d = e.getDevice();
        int src = e.getSource();
        gpLx = dead(e.getAxisValue(MotionEvent.AXIS_X));
        gpLy = dead(-e.getAxisValue(MotionEvent.AXIS_Y));
        // Xbox: palanca derecha en Z/RZ. Muchos genéricos: RX/RY (y Z/RZ son gatillos).
        int ax = MotionEvent.AXIS_Z, ay = MotionEvent.AXIS_RZ;
        if (!(isStickAxis(d, ax, src) && isStickAxis(d, ay, src))
                && isStickAxis(d, MotionEvent.AXIS_RX, src) && isStickAxis(d, MotionEvent.AXIS_RY, src)) {
            ax = MotionEvent.AXIS_RX;
            ay = MotionEvent.AXIS_RY;
        }
        gpRx = dead(e.getAxisValue(ax));
        gpRy = dead(-e.getAxisValue(ay));
        if (auto != null && Math.max(Math.max(Math.abs(gpLx), Math.abs(gpLy)), Math.max(Math.abs(gpRx), Math.abs(gpRy))) > 0.3f)
            cancelAuto();
        // Cruceta como eje (muchos mandos genéricos): se trata como botones
        int hx = Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_X)), hy = Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_Y));
        if (hx != hatX || hy != hatY) {
            if (hy < 0 && hatY >= 0) handleKey(KeyEvent.KEYCODE_DPAD_UP, true, false);
            if (hy > 0 && hatY <= 0) handleKey(KeyEvent.KEYCODE_DPAD_DOWN, true, false);
            if (hx < 0 && hatX >= 0) handleKey(KeyEvent.KEYCODE_DPAD_LEFT, true, false);
            if (hx > 0 && hatX <= 0) handleKey(KeyEvent.KEYCODE_DPAD_RIGHT, true, false);
            if (hatY < 0 && hy >= 0) handleKey(KeyEvent.KEYCODE_DPAD_UP, false, false);
            if (hatY > 0 && hy <= 0) handleKey(KeyEvent.KEYCODE_DPAD_DOWN, false, false);
            if (hatX < 0 && hx >= 0) handleKey(KeyEvent.KEYCODE_DPAD_LEFT, false, false);
            if (hatX > 0 && hx <= 0) handleKey(KeyEvent.KEYCODE_DPAD_RIGHT, false, false);
            hatX = hx;
            hatY = hy;
        }
        return true;
    }

    public boolean onGamepadKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean fromPad = isPad(e.getSource()) || KeyEvent.isGamepadButton(code);
        if (!fromPad) return false;
        if (code == KeyEvent.KEYCODE_BACK) code = KeyEvent.KEYCODE_BUTTON_B;  // algunos mandos mandan B como "atrás"
        handleKey(code, e.getAction() == KeyEvent.ACTION_DOWN, e.getRepeatCount() > 0);
        return true;
    }

    private void handleKey(int code, boolean down, boolean repeat) {
        if (capturing >= 0) {
            if (down && !repeat) {
                assignKey(capturing, code);
                capturing = -1;
            }
            return;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_L1) gpL1 = down;
        if (code == KeyEvent.KEYCODE_BUTTON_R1) gpR1 = down;
        if (gpL1 && gpR1) {
            if (stopComboStart < 0) stopComboStart = System.currentTimeMillis();
        } else {
            stopComboStart = -1;
        }
        if (code == keys[A_STOP]) {
            if (down && stopKeyStart < 0) stopKeyStart = System.currentTimeMillis();
            if (!down) stopKeyStart = -1;
            return;
        }
        if (!down || repeat || showConfig) return;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == code && i != A_STOP) {
                runAction(i);
                return;
            }
        }
    }
}
