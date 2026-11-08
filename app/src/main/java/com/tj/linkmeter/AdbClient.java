package com.tj.linkmeter;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Tablet side: a very small ADB client over the network. It lets the tablet run one shell command
 * on the phone through the phone's network adb port, for example the Shizuku start script, with no
 * Wi-Fi client connection needed on the phone. The first connection makes the phone ask
 * "Allow USB debugging?"; tapping "Always allow" there makes later connections silent.
 * Plain protocol only (version 0x01000000), which is what the legacy network adb port speaks.
 */
final class AdbClient {
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int VERSION = 0x01000000;
    private static final int MAXDATA = 256 * 1024;
    /** DigestInfo prefix for SHA-1: adb signs its 20-byte token as if it were a SHA-1 hash. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03,
        0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final InputStream in;
    private final OutputStream out;

    private AdbClient(Socket s) throws IOException {
        in = s.getInputStream();
        out = s.getOutputStream();
    }

    private static final class Msg {
        int cmd;
        int a0;
        int a1;
        byte[] data;
    }

    /** Runs one shell command on the phone and returns what it printed. */
    static String shell(Context c, String host, int port, String command, int seconds) throws IOException {
        KeyPair kp = key(c);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 4000);
            s.setSoTimeout(seconds * 1000);
            AdbClient a = new AdbClient(s);
            a.send(A_CNXN, VERSION, MAXDATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
            Msg m = a.read();
            int tokens = 0;
            while (m.cmd == A_AUTH) {
                if (m.a0 == 1) { // the phone sends a random token
                    if (tokens++ == 0) {
                        a.send(A_AUTH, 2, 0, sign(kp.getPrivate(), m.data));
                    } else {
                        // Our key is new to the phone: send it so the phone can ask the user.
                        a.send(A_AUTH, 3, 0, publicKey((RSAPublicKey) kp.getPublic()));
                    }
                }
                m = a.read();
            }
            if (m.cmd != A_CNXN) {
                throw new IOException("the phone's adb answered in a way this tablet doesn't understand");
            }
            a.send(A_OPEN, 1, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            while (true) {
                m = a.read();
                if (m.cmd == A_WRTE) {
                    result.write(m.data, 0, m.data.length);
                    a.send(A_OKAY, 1, m.a0, null);
                } else if (m.cmd == A_CLSE) {
                    break;
                } else if (m.cmd != A_OKAY) {
                    break;
                }
            }
            return result.toString("UTF-8").trim();
        }
    }

    private void send(int cmd, int a0, int a1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        for (int i = 0; i < len; i++) {
            sum += data[i] & 0xFF;
        }
        ByteBuffer b = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(sum).putInt(cmd ^ 0xFFFFFFFF);
        if (data != null) {
            b.put(data);
        }
        out.write(b.array());
        out.flush();
    }

    private void readFully(byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) {
                throw new IOException("the phone closed the connection (did you tap Don't allow?)");
            }
            off += n;
        }
    }

    private Msg read() throws IOException {
        byte[] h = new byte[24];
        readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.a0 = b.getInt();
        m.a1 = b.getInt();
        int len = b.getInt();
        b.getInt(); // checksum
        int magic = b.getInt();
        if (magic != (m.cmd ^ 0xFFFFFFFF) || len < 0 || len > MAXDATA) {
            throw new IOException("that is not an adb port");
        }
        m.data = new byte[len];
        readFully(m.data);
        return m;
    }

    // ------------------------------------------------------------ keys

    /** This tablet's adb key, made once and kept in the app's private storage. */
    private static KeyPair key(Context c) throws IOException {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences("adbkey", Context.MODE_PRIVATE);
        try {
            String priv = p.getString("priv", "");
            String pub = p.getString("pub", "");
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (!priv.isEmpty() && !pub.isEmpty()) {
                return new KeyPair(
                        kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP))));
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair(); // takes a few seconds on a slow tablet, once
            p.edit().putString("priv", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP))
                    .putString("pub", Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP))
                    .apply();
            return kp;
        } catch (GeneralSecurityException e) {
            throw new IOException("couldn't make an adb key: " + e);
        }
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws IOException {
        try {
            Signature s = Signature.getInstance("NONEwithRSA");
            s.initSign(key);
            s.update(SHA1_PREFIX);
            s.update(token);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IOException("couldn't sign for adb: " + e);
        }
    }

    /** The key in adb's own format: length, n0inv, modulus, R squared, exponent, then a name. */
    private static byte[] publicKey(RSAPublicKey k) {
        BigInteger n = k.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        int n0inv = r32.subtract(n.mod(r32).modInverse(r32)).intValue();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n0inv);
        for (int i = 0; i < 64; i++) {
            b.putInt(n.shiftRight(32 * i).intValue());
        }
        for (int i = 0; i < 64; i++) {
            b.putInt(rr.shiftRight(32 * i).intValue());
        }
        b.putInt(k.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " tablet@linkmeter\0";
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
