package com.jjrpc.xdevkit;

import com.jjrpc.JRPC;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class XbdmXboxConsole implements JRPC.IXboxConsole {

    private final String host;
    private final int port;
    private volatile Socket socket;
    private volatile BufferedReader in;
    private volatile BufferedWriter out;
    private int connectTimeoutMs = 5000;
    private int conversationTimeoutMs = 2000;
    private long connectionId = 1L; //arbitrary
    private final DebugTarget debugTarget = new DebugTarget();

    public XbdmXboxConsole(String ip) {
        this(ip, 730);
    }

    public XbdmXboxConsole(String ip, int port) {
        this.host = ip;
        this.port = port;
    }

    @Override
    public long getIPAddress() {
        String[] p = host.split("\\.");
        if (p.length != 4) return 0x7F000001L;
        long a = (Long.parseLong(p[0]) & 0xFFL) << 24;
        long b = (Long.parseLong(p[1]) & 0xFFL) << 16;
        long c = (Long.parseLong(p[2]) & 0xFFL) << 8;
        long d = (Long.parseLong(p[3]) & 0xFFL);
        return a | b | c | d;
    }

    @Override public void setConnectTimeout(int ms) {
        this.connectTimeoutMs = ms;
    }

    @Override public void setConversationTimeout(int ms) {
        this.conversationTimeoutMs = ms;
    }

    @Override public int getConnectTimeout() {
        return connectTimeoutMs;
    }

    @Override public int getConversationTimeout() {
        return conversationTimeoutMs;
    }

    @Override public JRPC.IXboxDebugTarget getDebugTarget() {
        return debugTarget;
    }

    @Override
    public synchronized long OpenConnection(String flagsOrNull) {
        ensureConnected();
        return connectionId;
    }

    @Override
    public synchronized void SendTextCommand(long connectionId, String command, String[] outResponse) {
        ensureConnected();
        if (connectionId != this.connectionId)
            throw new JRPC.ComException(0x82DA0007, "Bad connection id");

        try {
            String line = command.endsWith("\r\n") ? command : command.replaceAll("\n$", "") + "\r\n";
            out.write(line);
            out.flush();
            String first = readLineOrThrow();
            StringBuilder sb = new StringBuilder(first);
            String lower = first.toLowerCase(Locale.ROOT);
            if (lower.contains("response follows") || lower.contains("send binary data")) {
                String l;
                while ((l = in.readLine()) != null) {
                    if (l.equals(".")) break;
                    sb.append("\n").append(l);
                }
            }
            outResponse[0] = sb.toString();
        } catch (IOException e) {
            closeQuietly();
            throw new JRPC.ComException(0x82DA0007, "I/O: " + e.getMessage());
        }
    }

    private void ensureConnected() {
        if (socket != null && socket.isConnected() && !socket.isClosed()) return;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(conversationTimeoutMs);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
            try {
                in.readLine();
            } catch (IOException ignored) {}
        } catch (IOException e) {
            closeQuietly();
            throw new JRPC.ComException(0x82DA0100, "Connect failed: " + e.getMessage());
        }
    }

    private String readLineOrThrow() throws IOException {
        String s = in.readLine();
        if (s == null) throw new EOFException("XBDM closed the connection");
        return s;
    }

    private void closeQuietly() {
        try {
            if (in != null) in.close();
        }
        catch (IOException ignored) {
        }
        try {
            if (out != null) out.close();
        }
        catch (IOException ignored) {
        }
        try {
            if (socket != null) socket.close();
        }
        catch (IOException ignored) {
        }
        in = null;
        out = null;
        socket = null;
    }

    private final class DebugTarget implements JRPC.IXboxDebugTarget {
        @Override
        public void GetMemory(long address, long length, byte[] outBuf, long[] outRead) {
            final String cmd = "getmem addr=0x" + Long.toHexString(address) + " length=" + length;
            String[] holder = new String[1];
            SendTextCommand(connectionId, cmd, holder);
            String resp = holder[0] == null ? "" : holder[0];
            String hex = extractField(resp, "data=");
            int n = Math.min(outBuf.length, hex.length() / 2);
            for (int i = 0; i < n; i++) {
                int hi = Character.digit(hex.charAt(i * 2), 16);
                int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
                outBuf[i] = (byte) ((hi << 4) | lo);
            }
            if (outRead != null && outRead.length > 0) outRead[0] = n;
        }

        @Override
        public void InvalidateMemoryCache(boolean unused, long address, long length) {
            //XBDM has no explicit cache invalidation for these calls, no-op.
        }

        @Override
        public void SetMemory(long address, long length, byte[] data, long[] outWritten) {
            StringBuilder hex = new StringBuilder(data.length * 2);
            for (int i = 0;
                 i < length && i < data.length;
                 i++) {
                hex.append(String.format("%02X", data[i]));
            }
            final String cmd = "setmem addr=0x" + Long.toHexString(address) + " data=" + hex;
            String[] holder = new String[1];
            SendTextCommand(connectionId, cmd, holder);
            if (outWritten != null && outWritten.length > 0) outWritten[0] = Math.min(length, data.length);
        }

        private String extractField(String resp, String key) {
            int i = resp.indexOf(key);
            if (i < 0) return "";
            int e = resp.indexOf(' ', i + key.length());
            return resp.substring(i + key.length(), e < 0 ? resp.length() : e).trim();
        }
    }
}
