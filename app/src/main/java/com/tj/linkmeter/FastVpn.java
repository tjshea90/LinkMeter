package com.tj.linkmeter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;

/**
 * Tablet only. Fast Wi-Fi's VPN (docs/FAST_WIFI.md): every app's internet traffic goes into this VPN, and {@link TunStack} carries it
 * to LinkMeter on the phone, which opens the real connections itself (so the carrier does not count it as hotspot traffic).
 *
 * The VPN is only put up while the phone's relay answers on the tablet's Wi-Fi. When it does not (no Wi-Fi, the phone's app is old or
 * stopped, the relay keeps failing), the VPN is taken down again, so the tablet simply uses its Wi-Fi as before (the normal
 * hotspot), and the service looks again when the Wi-Fi changes and every 30 seconds meanwhile. Only the internet goes through it:
 * private addresses (the phone itself, the hotspot network, Wi-Fi Direct) stay outside, so LinkMeter's own control of the phone
 * works exactly as before.
 *
 * The service runs while the user wants Fast Wi-Fi ({@link Cfg#fastWanted}): started from the Home card, the quick settings tile or
 * after a restart of the tablet; stopped by the same switches or the notification's "Turn off".
 */
public class FastVpn extends VpnService {
    static final String START = "com.tj.linkmeter.FAST_START";
    static final String STOP = "com.tj.linkmeter.FAST_STOP";
    private static final String CHANNEL = "fast";
    private static final int NOTE_ID = 7;
    private static final long RETRY_MS = 30_000L;

    enum State { OFF, WAITING, ON, PERMISSION }

    /** For the Home card, the tile and the notification (written by the service's thread). */
    static volatile State state = State.OFF;
    static volatile String detail = "";
    static volatile TunStack engine;
    static volatile long onSince;
    /** The phone's address the relay is used at (for the card). */
    static volatile String phone = "";

    private HandlerThread worker;
    private Handler h;
    private ParcelFileDescriptor pfd;
    private AndroidTun tun;
    private ConnectivityManager.NetworkCallback callback;
    private volatile Network wifi;
    private volatile InetSocketAddress relay;
    private boolean wanted;

    // ------------------------------------------------------------------------------------------- switching

    /** Starts it (the caller has made sure Android's VPN question was answered: {@link VpnService#prepare} gave null). */
    static void start(Context c) {
        Cfg.setFastWanted(c, true);
        try {
            c.startForegroundService(new Intent(c, FastVpn.class).setAction(START));
        } catch (RuntimeException e) {
            detail = "Android did not let LinkMeter start Fast Wi-Fi now: " + e.getMessage();
        }
    }

    static void stop(Context c) {
        Cfg.setFastWanted(c, false);
        if (state == State.OFF) {
            return;
        }
        try {
            c.startService(new Intent(c, FastVpn.class).setAction(STOP));
        } catch (RuntimeException e) {
            // not running
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        worker = new HandlerThread("fast-vpn");
        worker.start();
        h = new Handler(worker.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? START : intent.getAction(); // null: Android started it again after it had stopped it
        if (STOP.equals(a)) {
            Cfg.setFastWanted(this, false);
            h.post(() -> {
                wanted = false;
                down(State.OFF, "");
                unwatch();
                stopForeground(true);
                stopSelf();
            });
            return START_NOT_STICKY;
        }
        if (!Cfg.fastWanted(this) && intent == null) {
            stopSelf(); // switched off meanwhile: Android's restart is not a reason to come back
            return START_NOT_STICKY;
        }
        foreground("Fast Wi-Fi is starting", "Looking for LinkMeter on the phone.");
        h.post(() -> {
            wanted = true;
            watch();
            attempt();
        });
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        // another VPN was switched on, or Fast Wi-Fi was switched off in Android's VPN settings: then it is off
        Cfg.setFastWanted(this, false);
        h.post(() -> {
            wanted = false;
            down(State.OFF, "Android's VPN settings switched Fast Wi-Fi off (or another VPN app took over).");
            unwatch();
            stopForeground(true);
            stopSelf();
        });
    }

    @Override
    public void onDestroy() {
        wanted = false;
        Handler hh = h;
        if (hh != null) {
            hh.removeCallbacksAndMessages(null);
        }
        tearDown();
        unwatch();
        if (state != State.PERMISSION) {
            state = State.OFF;
        }
        engine = null;
        if (worker != null) {
            worker.quitSafely();
        }
        FastTile.refresh(this);
        super.onDestroy();
    }

    // ------------------------------------------------------------------------------------------- up and down (service thread)

    /** Puts the VPN up if the phone's relay answers on the Wi-Fi now; otherwise waits (the tablet uses its Wi-Fi as before). */
    private void attempt() {
        h.removeCallbacks(retry);
        if (!wanted) {
            return;
        }
        TunStack e = engine;
        if (e != null && e.running()) {
            return;
        }
        if (VpnService.prepare(this) != null) {
            down(State.PERMISSION, "Android needs your OK for LinkMeter's VPN once. Open LinkMeter and switch Fast Wi-Fi on there.");
            return;
        }
        Network n = Net.wifi(this);
        wifi = n;
        if (n == null) {
            down(State.WAITING, "Waiting for the tablet to be on the phone's Wi-Fi.");
            return; // the network callback calls again when a Wi-Fi comes
        }
        String host = Net.phoneHost(this);
        if (host.isEmpty()) {
            waitAgain("The phone's address on this Wi-Fi is not known yet.");
            return;
        }
        InetSocketAddress r = new InetSocketAddress(host, FastProto.PORT);
        String trouble = probe(n, r);
        if (trouble != null) {
            waitAgain(trouble);
            return;
        }
        try {
            up(n, r);
            retryMs = RETRY_MS;
        } catch (IOException | RuntimeException ex) {
            tearDown();
            waitAgain("The VPN could not be set up: " + ex.getMessage());
        }
    }

    private final Runnable retry = this::attempt;
    /** The wait before the next look: half a minute, doubled after each look that failed, up to five minutes (a Wi-Fi change resets it). */
    private long retryMs = RETRY_MS;

    private void waitAgain(String why) {
        down(State.WAITING, why + " The tablet uses its Wi-Fi as before meanwhile; LinkMeter looks again "
                + (retryMs < 60_000L ? "in half a minute." : "in " + retryMs / 60_000L + " minutes, and at once when the Wi-Fi changes."));
        h.postDelayed(retry, retryMs);
        retryMs = Math.min(5 * 60_000L, retryMs * 2);
    }

    /** Does the phone's relay answer, and does it know this tablet's key? null if both, else why not in the user's words. */
    private String probe(Network n, InetSocketAddress r) {
        try (Socket s = new Socket()) {
            n.bindSocket(s);
            s.connect(r, 3000);
            s.setSoTimeout(3000);
            s.getOutputStream().write(FastProto.ping(FastProto.key(Cfg.token(this))));
            int answer = s.getInputStream().read();
            return FastRules.probeWords(answer, null);
        } catch (IOException e) {
            return FastRules.probeWords(-2, e.getMessage());
        }
    }

    private void up(Network n, InetSocketAddress r) throws IOException {
        Builder b = new Builder();
        b.setSession("LinkMeter Fast Wi-Fi");
        b.setMtu(FastProto.MTU);
        b.addAddress(Ip4.str(FastProto.VPN_ADDR), FastProto.VPN_PREFIX);
        b.addRoute(Ip4.str(FastProto.VPN_ADDR & (-1 << (32 - FastProto.VPN_PREFIX))), FastProto.VPN_PREFIX);
        for (int[] route : FastProto.publicRoutes()) {
            b.addRoute(Ip4.str(route[0]), route[1]);
        }
        b.addDnsServer(Ip4.str(FastProto.VPN_DNS));
        b.setUnderlyingNetworks(new Network[] {n});
        b.setMetered(false); // as metered as the Wi-Fi under it, as it was without Fast Wi-Fi
        b.setConfigureIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        ParcelFileDescriptor fd = b.establish();
        if (fd == null) {
            down(State.PERMISSION, "Android needs your OK for LinkMeter's VPN once. Open LinkMeter and switch Fast Wi-Fi on there.");
            return;
        }
        pfd = fd;
        relay = r;
        try {
            tun = new AndroidTun(fd);
        } catch (ErrnoException e) {
            throw new IOException(e.getMessage());
        }
        TunStack e = new TunStack(tun, link, events, FastProto.key(Cfg.token(this)));
        e.blockQuic(Cfg.fastBlockQuic(this));
        engine = e;
        e.start();
        phone = r.getAddress().getHostAddress();
        onSince = SystemClock.elapsedRealtime();
        state = State.ON;
        detail = "";
        foreground("Fast Wi-Fi is on", "The tablet's internet goes through LinkMeter on the phone, without the hotspot's speed limit.");
        FastTile.refresh(this);
    }

    /** Takes the VPN down (if it is up) and says why. */
    private void down(State s, String why) {
        tearDown();
        state = s;
        detail = why;
        if (wanted) {
            foreground(s == State.PERMISSION ? "Fast Wi-Fi needs your OK" : "Fast Wi-Fi is waiting for the phone", why);
        }
        FastTile.refresh(this);
    }

    private void tearDown() {
        TunStack e = engine;
        engine = null;
        AndroidTun t = tun;
        tun = null;
        if (e != null) {
            e.stop();
        }
        if (t != null) {
            t.wake(); // the reader thread leaves its poll
        }
        if (e != null) {
            try {
                e.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (t != null) {
            t.close();
        }
        ParcelFileDescriptor fd = pfd;
        pfd = null;
        if (fd != null) {
            try {
                fd.close(); // the VPN goes away with its descriptor: the tablet's apps use the Wi-Fi directly again
            } catch (IOException ignored) {
                // closed
            }
        }
        relay = null;
    }

    // ------------------------------------------------------------------------------------------- the Wi-Fi

    private void watch() {
        if (callback != null) {
            return;
        }
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                h.post(FastVpn.this::wifiChanged);
            }

            @Override
            public void onLinkPropertiesChanged(Network n, LinkProperties lp) {
                h.post(FastVpn.this::wifiChanged);
            }

            @Override
            public void onLost(Network n) {
                h.post(FastVpn.this::wifiChanged);
            }
        };
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(), callback, h);
        } catch (RuntimeException e) {
            callback = null;
        }
    }

    private void unwatch() {
        if (callback == null) {
            return;
        }
        try {
            ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(callback);
        } catch (RuntimeException ignored) {
            // gone
        }
        callback = null;
    }

    /** The Wi-Fi came, went or changed: if the VPN no longer sits on the Wi-Fi the tablet is on, put it up again (or wait). */
    private void wifiChanged() {
        if (!wanted) {
            return;
        }
        retryMs = RETRY_MS; // a new Wi-Fi: look quickly again
        Network n = Net.wifi(this);
        TunStack e = engine;
        if (e != null && e.running()) {
            InetSocketAddress r = relay;
            String host = Net.phoneHost(this);
            if (n != null && n.equals(wifi) && r != null && host.equals(r.getAddress().getHostAddress())) {
                return; // still the same Wi-Fi and the same phone
            }
            tearDown();
        }
        attempt();
    }

    // ------------------------------------------------------------------------------------------- the engine's needs

    private final TunStack.Link link = new TunStack.Link() {
        @Override
        public InetSocketAddress relay() {
            return relay;
        }

        @Override
        public SocketChannel tcp() throws IOException {
            SocketChannel ch = SocketChannel.open();
            prepareSocket(ch.socket());
            return ch;
        }

        @Override
        public DatagramChannel udp() throws IOException {
            DatagramChannel ch = DatagramChannel.open();
            if (!protect(ch.socket())) {
                ch.close();
                throw new IOException("Android did not let the relay socket bypass the VPN");
            }
            Network n = wifi;
            if (n != null) {
                n.bindSocket(ch.socket());
            }
            return ch;
        }
    };

    private void prepareSocket(Socket s) throws IOException {
        if (!protect(s)) {
            s.close();
            throw new IOException("Android did not let the relay socket bypass the VPN");
        }
        Network n = wifi;
        if (n != null) {
            n.bindSocket(s); // over the Wi-Fi to the phone, whatever else the tablet is on
        }
    }

    private final TunStack.Events events = new TunStack.Events() {
        @Override
        public void relayTrouble(String why) {
            // the phone stopped relaying: the VPN goes down so the tablet's apps use the Wi-Fi directly again, and it is tried later
            h.post(() -> {
                if (engine != null) {
                    tearDown();
                    waitAgain(why);
                }
            });
        }

        @Override
        public void relayFine() {
            // nothing to do: the VPN is up
        }

        @Override
        public void stopped() {
            // the engine ended by itself: a VPN without it would swallow every app's traffic, so it goes down too
            h.post(() -> {
                if (pfd != null) {
                    tearDown();
                    waitAgain("Fast Wi-Fi's engine stopped.");
                }
            });
        }
    };

    // ------------------------------------------------------------------------------------------- the notification

    private void foreground(String title, String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Fast Wi-Fi", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent off = PendingIntent.getService(this, 1, new Intent(this, FastVpn.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_launcher), "Turn off", off)
                        .build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTE_ID, n);
        }
    }

    // ------------------------------------------------------------------------------------------- the interface

    /**
     * The VPN interface for {@link TunStack}: a read that waits in poll() for a packet OR for {@link #wake} (a blocked read of the
     * interface could not be ended otherwise: closing a descriptor does not wake a thread that is reading it).
     */
    static final class AndroidTun implements TunStack.Tun {
        private final FileInputStream in;
        private final FileOutputStream out;
        private final FileDescriptor stopR;
        private final FileDescriptor stopW;
        private final StructPollfd[] fds;
        private volatile boolean closed;

        AndroidTun(ParcelFileDescriptor pfd) throws ErrnoException {
            FileDescriptor fd = pfd.getFileDescriptor();
            in = new FileInputStream(fd);
            out = new FileOutputStream(fd);
            FileDescriptor[] p = Os.pipe();
            stopR = p[0];
            stopW = p[1];
            StructPollfd t = new StructPollfd();
            t.fd = fd;
            t.events = (short) OsConstants.POLLIN;
            StructPollfd s = new StructPollfd();
            s.fd = stopR;
            s.events = (short) OsConstants.POLLIN;
            fds = new StructPollfd[] {t, s};
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            while (!closed) {
                try {
                    Os.poll(fds, -1);
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EINTR) {
                        continue;
                    }
                    throw new IOException(e.getMessage());
                }
                if (fds[1].revents != 0 || closed) {
                    return -1;
                }
                int ev = fds[0].revents;
                if ((ev & (OsConstants.POLLERR | OsConstants.POLLNVAL)) != 0) {
                    return -1;
                }
                if ((ev & OsConstants.POLLIN) != 0) {
                    int n = in.read(b, off, len); // non-blocking: 0 when there was nothing after all
                    if (n != 0) {
                        return n;
                    }
                }
            }
            return -1;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        void wake() {
            closed = true;
            try {
                Os.write(stopW, new byte[] {1}, 0, 1);
            } catch (ErrnoException | IOException ignored) {
                // already closed
            }
        }

        void close() {
            closed = true;
            try {
                Os.close(stopR);
            } catch (ErrnoException ignored) {
                // closed
            }
            try {
                Os.close(stopW);
            } catch (ErrnoException ignored) {
                // closed
            }
        }
    }

    /** The address of the phone, for the card ("" when the VPN is not up). */
    static String phoneAddress() {
        return state == State.ON ? phone : "";
    }

    /** For tests of the address: "a.b.c.d" of an InetAddress. */
    static String str(InetAddress a) {
        return a == null ? "" : a.getHostAddress();
    }
}
