package com.vrcdevicecheck.osc.net;

import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.PortUnreachableException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 局域网扫描：找出同一网段里 9000/UDP 端口没被拒绝的机器（大概率是开着 OSC 的 VRChat）。
 *
 * 逻辑（为什么这么写）：
 *   UDP 是无连接的，"连接"一个 UDP 端口并不能直接知道它开没开。
 *   这里利用 ICMP 端口不可达：向 ip:9000 发一个 4 字节的探测包，
 *     - 如果对方主机在线但 9000 没有程序监听，内核会回 ICMP port unreachable，
 *       Android/Linux 上表现为 connected socket 的 receive() 抛 PortUnreachableException；
 *     - 如果 9000 有程序监听（或防火墙静默丢弃），就收不到任何回应，直到读超时。
 *   所以：抛 PortUnreachableException = 关闭；超时 = 可能开放。
 *   再把“可能开放”的地址重新探测一次，减少偶发丢包造成的误报。
 *
 * 说明：这里只能找到“9000 端口对外开放”的机器。VRChat 默认只绑定 127.0.0.1，
 * 必须用 --osc=9000:<PC 局域网 IP>:9001 启动才会在局域网可见。
 */
public final class LanScanner {

    public static final int OSC_PORT = 9000;

    private static final int PROBE_TIMEOUT_MS = 260;
    private static final int THREADS = 32;

    public interface Listener {
        void onProgress(int done, int total);
    }

    private LanScanner() {
    }

    /** 同步扫描，必须在子线程调用；返回按 IP 末段排序的候选地址 */
    public static List<String> scan(Listener listener) {
        InetAddress local = findLocalIpv4();
        if (local == null) return new ArrayList<>();

        byte[] raw = local.getAddress();
        String prefix = (raw[0] & 0xFF) + "." + (raw[1] & 0xFF) + "." + (raw[2] & 0xFF) + ".";
        String self = local.getHostAddress();

        List<String> targets = new ArrayList<>(254);
        for (int i = 1; i <= 254; i++) {
            String ip = prefix + i;
            if (ip.equals(self)) continue;
            targets.add(ip);
        }

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
                            if (probe(ip)) open.add(ip);
                        } finally {
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

        // 第二遍：只对候选地址再确认一次
        List<String> confirmed = new ArrayList<>();
        for (String ip : open) {
            if (probe(ip)) confirmed.add(ip);
        }
        Collections.sort(confirmed, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return lastOctet(a) - lastOctet(b);
            }
        });
        return confirmed;
    }

    /** 返回 true 表示 9000 端口“没有拒绝连接”，可能是 VRChat */
    private static boolean probe(String ip) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.connect(InetAddress.getByName(ip), OSC_PORT);
            socket.setSoTimeout(PROBE_TIMEOUT_MS);

            // 一个最小的 OSC 报文（地址 "/" + 3 个 0），对方不回应也没关系
            byte[] payload = new byte[]{'/', 0, 0, 0};
            socket.send(new DatagramPacket(payload, payload.length));

            byte[] buffer = new byte[64];
            DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
            socket.receive(reply);
            return true;   // 居然有回应，肯定有东西在听
        } catch (PortUnreachableException e) {
            return false;  // 收到 ICMP 端口不可达：明确关闭
        } catch (ConnectException e) {
            return false;
        } catch (SocketTimeoutException e) {
            return true;   // 没有拒绝：可能开放，也可能被防火墙静默丢弃
        } catch (SocketException e) {
            return false;
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
