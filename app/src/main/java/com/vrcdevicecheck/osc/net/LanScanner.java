package com.vrcdevicecheck.osc.net;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 局域网扫描：只找“能确认是 VRChat OSC”的电脑。
 *
 * 为什么不再用 UDP 超时判断：
 *   之前把“没有收到 ICMP 端口不可达”当成端口开放，但很多设备（手机、开了防火墙的 PC）
 *   会静默丢包，结果整个网段都被误报成 VRChat。
 *
 * 现在只用“正向证据”：
 *   1. 直接发 mDNS 查询 "_oscjson._tcp.local"，**用应答包的来源 IP** 作为候选——
 *      VRChat 只绑本机时广播里写的是 127.0.0.1，但应答是从电脑的局域网地址发出来的；
 *   2. NsdManager 解析到的局域网地址（再做一次 TCP 确认）；
 *   3. 向每个地址的 9000/TCP 发起连接，VRChat 的 OSCQuery HTTP 服务会接受连接。
 *   连不上（拒绝/超时）一律不算，所以不会再出现整个网段都被误报的情况。
 *
 * 注意：VRChat 必须开启 OSC，并且监听端口（默认 9000）在局域网可见；
 * 如果 PC 上 VRChat 只绑定 127.0.0.1，手机是找不到的。
 */
public final class LanScanner {

    public static final int OSC_PORT = 9000;

    private static final int TCP_TIMEOUT_MS = 320;
    private static final int THREADS = 32;
    private static final long MDNS_TIMEOUT_MS = 1800L;

    public interface Listener {
        void onProgress(int done, int total);
    }

    private LanScanner() {
    }

    /** 同步扫描，必须在子线程调用；返回去重后的候选 IP */
    public static List<String> scan(Context context, Listener listener) {
        Set<String> found = new LinkedHashSet<>();
        InetAddress local = findLocalIpv4();
        String selfIp = local == null ? null : local.getHostAddress();

        // 1) 原始 mDNS 查询：只看应答包的来源 IP。
        //    VRChat 的 OSCQuery 广播里可能把地址写成 127.0.0.1（绑定本机时），
        //    但应答包是局域网发出来的，来源 IP 才是电脑的真实地址。
        for (String ip : mdnsAskOscQuery(selfIp)) {
            if (ip != null) found.add(ip);
        }

        // 2) NsdManager 发现（会解析服务声明的地址，作为补充；仅接受局域网地址）
        if (context != null) {
            for (String ip : discoverOscQuery(context, selfIp)) {
                if (ip != null) found.add(ip);
            }
        }

        // 3) TCP 探测 9000 端口（OSCQuery HTTP 的端口）
        if (local != null) {
            byte[] raw = local.getAddress();
            String prefix = (raw[0] & 0xFF) + "." + (raw[1] & 0xFF) + "." + (raw[2] & 0xFF) + ".";
            String self = local.getHostAddress();

            List<String> targets = new ArrayList<>(254);
            for (int i = 1; i <= 254; i++) {
                String ip = prefix + i;
                if (ip.equals(self)) continue;
                targets.add(ip);
            }
            found.addAll(tcpSweep(targets, listener));
        }

        List<String> result = new ArrayList<>(found);
        Collections.sort(result, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return lastOctet(a) - lastOctet(b);
            }
        });
        return result;
    }

    /**
     * 直接发一条 mDNS PTR 查询 "_oscjson._tcp.local"，把应答包的来源地址当作候选。
     * 好处是不依赖服务里声明的地址——VRChat 只绑本机时声明的是 127.0.0.1，
     * 但应答本身是从电脑的局域网地址发出的。
     */
    private static List<String> mdnsAskOscQuery(String selfIp) {
        List<String> hosts = new ArrayList<>();
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(250);
            byte[] query = buildOscQueryPacket();
            InetAddress group = InetAddress.getByName("224.0.0.251");
            DatagramPacket request = new DatagramPacket(query, query.length, group, 5353);

            long deadline = System.currentTimeMillis() + MDNS_TIMEOUT_MS;
            byte[] buffer = new byte[1500];
            int sent = 0;
            long nextSend = 0;
            while (System.currentTimeMillis() < deadline) {
                long now = System.currentTimeMillis();
                if (sent < 3 && now >= nextSend) {
                    socket.send(request);
                    sent++;
                    nextSend = now + 700;
                }
                try {
                    DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                    socket.receive(reply);
                    String text = new String(reply.getData(), 0, reply.getLength(), "ISO-8859-1");
                    if (!text.toLowerCase(java.util.Locale.US).contains("_oscjson")) continue;
                    InetAddress source = reply.getAddress();
                    if (source == null) continue;
                    if (!(source instanceof Inet4Address)) continue;
                    if (source.isLoopbackAddress() || source.isLinkLocalAddress()) continue;
                    if (!source.isSiteLocalAddress()) continue;
                    String ip = source.getHostAddress();
                    if (selfIp != null && selfIp.equals(ip)) continue;
                    if (!hosts.contains(ip)) hosts.add(ip);
                } catch (SocketTimeoutException e) {
                    // 没收到就继续等到截止时间
                }
            }
        } catch (Throwable ignored) {
            // 某些设备不允许 multicast 发送，忽略
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
        return hosts;
    }

    /** 构造 mDNS 查询：PTR "_oscjson._tcp.local"（QU 位，要求单播应答） */
    private static byte[] buildOscQueryPacket() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(0);
        out.write(0);          // ID = 0
        out.write(0);
        out.write(0);          // flags = 0
        out.write(0);
        out.write(1);          // QDCOUNT = 1
        out.write(0);
        out.write(0);          // ANCOUNT
        out.write(0);
        out.write(0);          // NSCOUNT
        out.write(0);
        out.write(0);          // ARCOUNT
        writeDnsLabel(out, "_oscjson");
        writeDnsLabel(out, "_tcp");
        writeDnsLabel(out, "local");
        out.write(0);          // 名称结束
        out.write(0);
        out.write(12);         // QTYPE = PTR
        out.write(0x80);
        out.write(0x01);       // QCLASS = IN | 单播应答
        return out.toByteArray();
    }

    private static void writeDnsLabel(java.io.ByteArrayOutputStream out, String label) {
        out.write(label.length());
        for (int i = 0; i < label.length(); i++) {
            out.write(label.charAt(i));
        }
    }

    /** 通过 NsdManager 解析 _oscjson._tcp 服务；只接受局域网地址并做 TCP 确认 */
    private static List<String> discoverOscQuery(Context context, final String selfIp) {
        final List<String> hosts = Collections.synchronizedList(new ArrayList<String>());
        final NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return hosts;

        final NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                // 忽略
            }

            @Override
            public void onServiceFound(final NsdServiceInfo serviceInfo) {
                try {
                    nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                            // 解析失败就算了
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo info) {
                            InetAddress address = info.getHost();
                            if (address == null) return;
                            // 只接受局域网单播地址：mDNS 里可能出现 127.0.0.1 / fe80:: 等
                            // 对手机没有意义的地址（VRChat 只绑定本机时就会广播 loopback）。
                            if (address.isLoopbackAddress()) return;
                            if (address.isLinkLocalAddress()) return;
                            if (!address.isSiteLocalAddress()) return;

                            int port = info.getPort();
                            if (port <= 0) port = OSC_PORT;
                            String ip = address.getHostAddress();
                            if (selfIp != null && selfIp.equals(ip)) return;
                            // 还要从手机这边真正连得上，才算有效
                            if (tcpProbe(ip, port)) hosts.add(ip);
                        }
                    });
                } catch (Throwable ignored) {
                    // 某些设备并发 resolve 会抛异常，忽略即可
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                // 忽略
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                // 忽略
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                // 忽略
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                // 忽略
            }
        };

        try {
            nsd.discoverServices("_oscjson._tcp", NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (Throwable t) {
            return hosts;
        }

        try {
            Thread.sleep(MDNS_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            nsd.stopServiceDiscovery(listener);
        } catch (Throwable ignored) {
            // 忽略
        }
        return hosts;
    }

    /** 并发 TCP 探测每个地址的 9000 端口，只有连接成功的才算 */
    private static List<String> tcpSweep(List<String> targets, Listener listener) {
        final List<String> open = Collections.synchronizedList(new ArrayList<String>());
        final AtomicInteger done = new AtomicInteger();
        final int total = targets.size();
        final CountDownLatch latch = new CountDownLatch(total);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (final String ip : targets) {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (tcpProbe(ip)) open.add(ip);                        } finally {
                            int value = done.incrementAndGet();
                            if (listener != null && (value % 20 == 0 || value == total)) {
                                listener.onProgress(value, total);
                            }
                            latch.countDown();
                        }
                    }
                });
            }
            try {
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            pool.shutdownNow();
        }
        return open;
    }

    private static boolean tcpProbe(String ip) {
        return tcpProbe(ip, OSC_PORT);
    }

    private static boolean tcpProbe(String ip, int port) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(ip, port), TCP_TIMEOUT_MS);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
    }

    /** 找本机的局域网 IPv4：优先 wlan / eth 接口 */
    private static InetAddress findLocalIpv4() {
        InetAddress fallback = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return null;
            while (interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName() == null ? "" : nif.getName().toLowerCase(java.util.Locale.US);
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address)) continue;
                    if (!address.isSiteLocalAddress()) continue;
                    if (name.startsWith("wlan") || name.startsWith("eth")) return address;
                    if (fallback == null) fallback = address;
                }
            }
        } catch (Throwable ignored) {
            // 取不到就返回 null
        }
        return fallback;
    }

    private static int lastOctet(String ip) {
        int index = ip.lastIndexOf('.');
        if (index < 0) return 0;
        try {
            return Integer.parseInt(ip.substring(index + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
