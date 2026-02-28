package io.valkey;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Mptcp {

    private static final Logger LOG = LoggerFactory.getLogger(Mptcp.class);
    private static volatile boolean nativeLoaded = false;

    static {
        try {
            System.loadLibrary("mptcpenabler");
            nativeLoaded = true;
            LOG.debug("MPTCP native library loaded from java.library.path");
        } catch (UnsatisfiedLinkError e1) {
            try {
                nativeLoaded = loadFromJar();
                if (nativeLoaded) {
                    LOG.debug("MPTCP native library loaded from JAR resource");
                } else {
                    LOG.warn("MPTCP native library not found in JAR for this platform");
                }
            } catch (Exception e2) {
                LOG.warn("MPTCP native library not available: {}", e2.getMessage());
            }
        }
    }

    /** Non-instantiable utility class. */
    private Mptcp() {
    }
    
    public static boolean isAvailable() {
        if (!nativeLoaded) {
            return false;
        }
        try {
            return isMptcpEnabled0();
        } catch (SocketException e) {
            return false;
        }
    }

    public static void enable(Socket s) throws SocketException {
        if (s == null) {
            throw new NullPointerException("socket");
        }
        if (!nativeLoaded) {
            throw new SocketException("MPTCP native library not loaded");
        }
        enable0(s);
    }

    private static native boolean isMptcpEnabled0() throws SocketException;

    private static native void enable0(Socket s) throws SocketException;

    private static String normaliseArch() {
        String arch = System.getProperty("os.arch", "");
        switch (arch) {
            case "amd64":
            case "x86_64":
                return "x86_64";
            case "aarch64":
            case "arm64":
                return "aarch64";
            default:
                return null;
        }
    }

    private static boolean loadFromJar() throws IOException {
        String osName = System.getProperty("os.name", "");
        if (!osName.toLowerCase().startsWith("linux")) {
            return false;
        }

        String arch = normaliseArch();
        if (arch == null) {
            return false;
        }

        String resourcePath = "/native/linux/" + arch + "/libmptcpenabler.so";
        InputStream in = Mptcp.class.getResourceAsStream(resourcePath);
        if (in == null) {
            return false;
        }

        try {
            File tmpDir = new File(System.getProperty("java.io.tmpdir"),
                    "valkey-native-" + arch);
            if (!tmpDir.exists() && !tmpDir.mkdirs()) {
                throw new IOException("Failed to create temp directory: " + tmpDir);
            }
            File tmpLib = new File(tmpDir, "libmptcpenabler.so");

            // Write the library to the temp file (overwrite if size differs)
            if (!tmpLib.exists() || tmpLib.length() == 0) {
                OutputStream out = new FileOutputStream(tmpLib);
                try {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                } finally {
                    out.close();
                }
            }

            System.load(tmpLib.getAbsolutePath());
            return true;
        } finally {
            in.close();
        }
    }
}

