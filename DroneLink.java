package com.nexa.dronex;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Enlace UDP con drones de la app "WiFi UAV" (192.168.169.1:8800).
 * Protocolo tomado de turbodrone (github.com/marshallrichards/turbodrone, Apache-2.0).
 */
public class DroneLink {
    public static final String DRONE_IP = "192.168.169.1";
    public static final int DRONE_PORT = 8800;

    static final int FLAG_TAKEOFF_OR_LAND = 0x01;
    static final int FLAG_STOP = 0x02;

    private static final byte[] HEADER = {(byte) 0xef, 0x02, 0x7c, 0x00, 0x02, 0x02, 0x00, 0x01, 0x02, 0x00, 0x00, 0x00};
    private static final byte[] C1_SUFFIX = {0x00, 0x00, 0x14, 0x00, 0x66, 0x14};
    private static final byte[] C2_SUFFIX = {0, 0, 0, 0, 0, 0, 0x01, 0, 0, 0, 0x14, 0, 0, 0, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
    private static final byte[] C3_SUFFIX = {0, 0, 0, 0, 0, 0, 0x03, 0, 0, 0, 0x10, 0, 0, 0};
    private static final byte[] START_STREAM = {(byte) 0xef, 0x00, 0x04, 0x00};

    // Ejes -1..1 (los escribe la interfaz, los lee el hilo de envío)
    public volatile float roll, pitch, throttle, yaw;
    public volatile float speedScale = 0.6f;
    private volatile int flags;
    private volatile long flagUntil;
    private volatile long lastRx;
    private volatile boolean running;
    private DatagramSocket sock;
    private int ctr;
    private final Context ctx;

    public DroneLink(Context ctx) {
        this.ctx = ctx;
    }

    public static byte[] buildPacket(int ctr, int roll, int pitch, int throttle, int yaw, int flags) {
        byte[] p = new byte[124];
        int i = 0;
        for (byte b : HEADER) p[i++] = b;
        p[i++] = (byte) (ctr & 0xff);
        p[i++] = (byte) ((ctr >> 8) & 0xff);
        for (byte b : C1_SUFFIX) p[i++] = b;
        int[] controls = {roll, pitch, throttle, yaw, flags & 0xff, 0x02};
        int ck = 0;
        for (int c : controls) {
            p[i++] = (byte) c;
            ck ^= c;
        }
        i += 10;
        p[i++] = (byte) ck;
        p[i++] = (byte) 0x99;
        i += 44;
        p[i++] = 0x32;
        p[i++] = 0x4b;
        p[i++] = 0x14;
        p[i++] = 0x2d;
        i += 2;
        int c2 = ctr + 1, c3 = ctr + 2;
        p[i++] = (byte) (c2 & 0xff);
        p[i++] = (byte) ((c2 >> 8) & 0xff);
        for (byte b : C2_SUFFIX) p[i++] = b;
        p[i++] = (byte) (c3 & 0xff);
        p[i++] = (byte) ((c3 >> 8) & 0xff);
        for (byte b : C3_SUFFIX) p[i++] = b;
        return p;
    }

    static int stick(float v) {
        if (v > 1) v = 1;
        if (v < -1) v = -1;
        int r = Math.round(128 + v * (v >= 0 ? 127 : 128));
        return Math.max(0, Math.min(255, r));
    }

    public void pulse(int flag, long ms) {
        roll = pitch = throttle = yaw = 0;
        flags = flag;
        flagUntil = System.currentTimeMillis() + ms;
    }

    public void takeoffOrLand() {
        pulse(FLAG_TAKEOFF_OR_LAND, 1000);
    }

    public void emergencyStop() {
        pulse(FLAG_STOP, 800);
    }

    public boolean droneReplying() {
        return System.currentTimeMillis() - lastRx < 3000;
    }

    /** ¿Tiene el teléfono una IP de la red del dron (192.168.169.x)? */
    public static boolean onDroneWifi() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InterfaceAddress a : ni.getInterfaceAddresses()) {
                    InetAddress ad = a.getAddress();
                    if (ad instanceof Inet4Address && ad.getHostAddress().startsWith("192.168.169.")) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** El WiFi del dron no tiene internet: forzamos el socket a salir por el WiFi, no por datos móviles. */
    private void bindToWifi(DatagramSocket s) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    n.bindSocket(s);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        new Thread(new Runnable() {
            public void run() {
                txLoop();
            }
        }, "tx").start();
    }

    public synchronized void stop() {
        running = false;
        if (sock != null) sock.close();
    }

    private void txLoop() {
        long lastBind = 0, lastStart = 0;
        InetAddress addr;
        try {
            addr = InetAddress.getByName(DRONE_IP);
        } catch (Exception e) {
            return;
        }
        while (running) {
            long now = System.currentTimeMillis();
            try {
                if (sock == null || sock.isClosed() || now - lastBind > 5000) {
                    if (sock != null) sock.close();
                    sock = new DatagramSocket();
                    bindToWifi(sock);
                    lastBind = now;
                    final DatagramSocket rx = sock;
                    new Thread(new Runnable() {
                        public void run() {
                            rxLoop(rx);
                        }
                    }, "rx").start();
                }
                if (now - lastStart > 1000) {  // pedir vídeo provoca respuestas: así sabemos si hay enlace
                    sock.send(new DatagramPacket(START_STREAM, START_STREAM.length, addr, DRONE_PORT));
                    lastStart = now;
                }
                float s = speedScale;
                int f = now < flagUntil ? flags : 0;
                byte[] p = buildPacket(ctr, stick(roll * s), stick(pitch * s), stick(throttle), stick(yaw * s), f);
                ctr = (ctr + 1) & 0xffff;
                sock.send(new DatagramPacket(p, p.length, addr, DRONE_PORT));
            } catch (Exception e) {
                lastBind = 0;
            }
            try {
                Thread.sleep(12);  // ~80 paquetes por segundo
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void rxLoop(DatagramSocket s) {
        byte[] buf = new byte[2048];
        while (running && !s.isClosed()) {
            try {
                s.receive(new DatagramPacket(buf, buf.length));
                lastRx = System.currentTimeMillis();
            } catch (Exception e) {
                return;
            }
        }
    }
}
