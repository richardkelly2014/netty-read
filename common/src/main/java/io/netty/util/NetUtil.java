package io.netty.util;

import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.SystemPropertyUtil;

import java.io.*;
import java.net.*;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * 网络工具类
 */
public final class NetUtil {

    public static final Inet4Address LOCALHOST4;
    public static final Inet6Address LOCALHOST6;
    public static final InetAddress LOCALHOST;

    public static final NetworkInterface LOOPBACK_IF;

    /**
     * 系统最大连接数
     */
    public static final int SOMAXCONN;

    private static final int IPV6_WORD_COUNT = 8;
    private static final int IPV6_MAX_CHAR_COUNT = 39;
    private static final int IPV6_BYTE_COUNT = 16;
    private static final int IPV6_MAX_CHAR_BETWEEN_SEPARATOR = 4;
    private static final int IPV6_MIN_SEPARATORS = 2;
    private static final int IPV6_MAX_SEPARATORS = 8;

    private static final int IPV4_MAX_CHAR_BETWEEN_SEPARATOR = 3;
    private static final int IPV4_SEPARATORS = 3;

    private static final boolean IPV4_PREFERRED =
            SystemPropertyUtil.getBoolean("java.net.preferIPv4Stack", false);
    private static final boolean IPV6_ADDRESSES_PREFERRED =
            SystemPropertyUtil.getBoolean("java.net.preferIPv6Addresses", false);


    static {
        byte[] LOCALHOST4_BYTES = {127, 0, 0, 1};
        byte[] LOCALHOST6_BYTES = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};

        // Create IPv4 loopback address.
        Inet4Address localhost4 = null;
        try {
            localhost4 = (Inet4Address) InetAddress.getByAddress("localhost", LOCALHOST4_BYTES);
        } catch (Exception e) {
            PlatformDependent.throwException(e);
        }
        LOCALHOST4 = localhost4;

        // Create IPv6 loopback address.
        Inet6Address localhost6 = null;
        try {
            localhost6 = (Inet6Address) InetAddress.getByAddress("localhost", LOCALHOST6_BYTES);
        } catch (Exception e) {
            PlatformDependent.throwException(e);
        }
        LOCALHOST6 = localhost6;

        // Retrieve the list of available network interfaces.
        List<NetworkInterface> ifaces = new ArrayList<NetworkInterface>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface iface = interfaces.nextElement();
                    // Use the interface with proper INET addresses only.
                    if (SocketUtils.addressesFromNetworkInterface(iface).hasMoreElements()) {
                        ifaces.add(iface);
                    }
                }
            }
        } catch (SocketException e) {

        }

        // Find the first loopback interface available from its INET address (127.0.0.1 or ::1)
        // Note that we do not use NetworkInterface.isLoopback() in the first place because it takes long time
        // on a certain environment. (e.g. Windows with -Djava.net.preferIPv4Stack=true)
        NetworkInterface loopbackIface = null;
        InetAddress loopbackAddr = null;
        loop:
        for (NetworkInterface iface : ifaces) {
            for (Enumeration<InetAddress> i = SocketUtils.addressesFromNetworkInterface(iface); i.hasMoreElements(); ) {
                InetAddress addr = i.nextElement();
                if (addr.isLoopbackAddress()) {
                    // Found
                    loopbackIface = iface;
                    loopbackAddr = addr;
                    break loop;
                }
            }
        }

        // If failed to find the loopback interface from its INET address, fall back to isLoopback().
        if (loopbackIface == null) {
            try {
                for (NetworkInterface iface : ifaces) {
                    if (iface.isLoopback()) {
                        Enumeration<InetAddress> i = SocketUtils.addressesFromNetworkInterface(iface);
                        if (i.hasMoreElements()) {
                            // Found the one with INET address.
                            loopbackIface = iface;
                            loopbackAddr = i.nextElement();
                            break;
                        }
                    }
                }

                if (loopbackIface == null) {
                    //logger.warn("Failed to find the loopback interface");
                }
            } catch (SocketException e) {
                //logger.warn("Failed to find the loopback interface", e);
            }
        }

        if (loopbackIface != null) {
            // Found the loopback interface with an INET address.
//            logger.debug(
//                    "Loopback interface: {} ({}, {})",
//                    loopbackIface.getName(), loopbackIface.getDisplayName(), loopbackAddr.getHostAddress());
        } else {
            // Could not find the loopback interface, but we can't leave LOCALHOST as null.
            // Use LOCALHOST6 or LOCALHOST4, preferably the IPv6 one.
            if (loopbackAddr == null) {
                try {
                    if (NetworkInterface.getByInetAddress(LOCALHOST6) != null) {
                        //logger.debug("Using hard-coded IPv6 localhost address: {}", localhost6);
                        loopbackAddr = localhost6;
                    }
                } catch (Exception e) {
                    // Ignore
                } finally {
                    if (loopbackAddr == null) {
                        //logger.debug("Using hard-coded IPv4 localhost address: {}", localhost4);
                        loopbackAddr = localhost4;
                    }
                }
            }
        }

        LOOPBACK_IF = loopbackIface;
        LOCALHOST = loopbackAddr;

        // As a SecurityManager may prevent reading the somaxconn file we wrap this in a privileged block.
        //
        // See https://github.com/netty/netty/issues/3680
        SOMAXCONN = AccessController.doPrivileged(new PrivilegedAction<Integer>() {
            @Override
            public Integer run() {
                int somaxconn = PlatformDependent.isWindows() ? 200 : 128;
                File file = new File("/proc/sys/net/core/somaxconn");
                BufferedReader in = null;
                try {
                    // file.exists() may throw a SecurityException if a SecurityManager is used, so execute it in the
                    // try / catch block.
                    // See https://github.com/netty/netty/issues/4936
                    if (file.exists()) {
                        in = new BufferedReader(new FileReader(file));
                        somaxconn = Integer.parseInt(in.readLine());
                    } else {
                        // Try to get from sysctl
                        Integer tmp = null;
                        if (SystemPropertyUtil.getBoolean("io.netty.net.somaxconn.trySysctl", false)) {
                            tmp = sysctlGetInt("kern.ipc.somaxconn");
                            if (tmp == null) {
                                tmp = sysctlGetInt("kern.ipc.soacceptqueue");
                                if (tmp != null) {
                                    somaxconn = tmp;
                                }
                            } else {
                                somaxconn = tmp;
                            }
                        }

                        if (tmp == null) {

                        }
                    }
                } catch (Exception e) {

                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (Exception e) {
                            // Ignored.
                        }
                    }
                }
                return somaxconn;
            }
        });
    }

    private static Integer sysctlGetInt(String sysctlKey) throws IOException {
        Process process = new ProcessBuilder("sysctl", sysctlKey).start();
        try {
            InputStream is = process.getInputStream();
            InputStreamReader isr = new InputStreamReader(is);
            BufferedReader br = new BufferedReader(isr);
            try {
                String line = br.readLine();
                if (line != null && line.startsWith(sysctlKey)) {
                    for (int i = line.length() - 1; i > sysctlKey.length(); --i) {
                        if (!Character.isDigit(line.charAt(i))) {
                            return Integer.valueOf(line.substring(i + 1));
                        }
                    }
                }
                return null;
            } finally {
                br.close();
            }
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
