package com.jjrpc;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.net.Socket;

public final class JRPC {
    /**Util*/
    public static class ComException extends RuntimeException {
        private final int errorCode;
        public ComException(int errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
        public int getErrorCode() {
            return errorCode;
        }
    }

    public interface IXboxDebugTarget {
        void GetMemory(long address, long length, byte[] outBuf, long[] outRead);
        void InvalidateMemoryCache(boolean unused, long address, long length);
        void SetMemory(long address, long length, byte[] data, long[] outWritten);
    }

    public interface IXboxConsole {
        long getIPAddress();
        void setConnectTimeout(int ms);
        void setConversationTimeout(int ms);
        int getConnectTimeout();
        int getConversationTimeout();
        IXboxDebugTarget getDebugTarget();
        long OpenConnection(String flagsOrNull);
        void SendTextCommand(long connectionId, String command, String[] outResponse);

        default IXboxDebugTarget DebugTarget() {
            return getDebugTarget();
        }

        default long IPAddress() {
            return getIPAddress();
        }

        default int ConnectTimeout() {
            return getConnectTimeout();
        }

        default int ConversationTimeout() {
            return getConversationTimeout();
        }

        default void ConnectTimeout(int v) {
            setConnectTimeout(v);
        }

        default void ConversationTimeout(int v) {
            setConversationTimeout(v);
        }
    }

    public static class XboxManager {
        public String DefaultConsole = "127.0.0.1";

        public String getDefaultConsole() {
            return DefaultConsole;
        }

        public IXboxConsole OpenConsole(String nameOrIp) {
            String host = (nameOrIp == null || nameOrIp.isBlank()) ? "127.0.0.1" : nameOrIp;
            return new XbdmXboxConsole(host, 730);
        }
    }

    public static final class XbdmXboxConsole implements IXboxConsole {
        private final String host;
        private final int port;
        private Socket sock;
        private BufferedInputStream bin;
        private BufferedOutputStream bout;
        private int connectTimeout = 5000;
        private int conversationTimeout = 2000;
        private long connectionId = 1L;
        private final XbdmDebugTarget debugTarget = new XbdmDebugTarget();

        public XbdmXboxConsole(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public long getIPAddress() {
            try {
                String[] p = host.split("\\.");
                if (p.length != 4) return 0x7F000001L;
                long a = (Long.parseLong(p[0]) & 0xFFL) << 24;
                long b = (Long.parseLong(p[1]) & 0xFFL) << 16;
                long c = (Long.parseLong(p[2]) & 0xFFL) << 8;
                long d = (Long.parseLong(p[3]) & 0xFFL);
                return a | b | c | d;
            } catch (Throwable t) {
                return 0x7F000001L;
            }
        }

        @Override
        public void setConnectTimeout(int ms){
            this.connectTimeout = ms;
        }

        @Override
        public void setConversationTimeout(int ms) {
            this.conversationTimeout = ms;
            if (sock != null) {
                try { sock.setSoTimeout(ms);
                } catch (IOException ignored) {}
            }
        }

        @Override
        public int getConnectTimeout() {
            return connectTimeout;
        }

        @Override
        public int getConversationTimeout() {
            return conversationTimeout;
        }

        @Override
        public IXboxDebugTarget getDebugTarget() {
            return debugTarget;
        }

        @Override
        public long OpenConnection(String flagsOrNull) {
            ensureConnected();
            return connectionId;
        }

        @Override
        public void SendTextCommand(long connectionId, String command, String[] outResponse) {
            ensureConnected();
            if (connectionId != this.connectionId)
                throw new ComException(UIntToInt(0x82DA0007L), "Bad connection id");
            try {
                writeLine(command);
                String first = readAsciiLine();
                String lower = first.toLowerCase(Locale.ROOT);
                if (lower.contains("response follows")) {
                    StringBuilder sb = new StringBuilder(first);
                    while (true) {
                        String line = readAsciiLine();
                        if (line.equals(".")) break;
                        sb.append("\n").append(line);
                    }
                    outResponse[0] = sb.toString();
                    return;
                }
                if (lower.contains("binary")) {
                    String lenLine = readAsciiLine().trim();
                    int n = Integer.parseInt(lenLine);
                    byte[] data = readN(n);
                    try { readAsciiLine(); } catch (IOException ignored) {}
                    outResponse[0] = "200- data=" + toHex(data);
                    return;
                }
                outResponse[0] = first;
            } catch (IOException e) {
                closeQuietly();
                throw new ComException(UIntToInt(0x82DA0007L), "I/O: " + e.getMessage());
            }
        }

        private void ensureConnected() {
            if (sock != null && sock.isConnected() && !sock.isClosed()) return;
            try {
                sock = new Socket();
                sock.connect(new InetSocketAddress(host, port), connectTimeout);
                sock.setSoTimeout(conversationTimeout);
                bin = new BufferedInputStream(sock.getInputStream());
                bout = new BufferedOutputStream(sock.getOutputStream());
                try { readAsciiLine(); } catch (IOException ignored) {}
            } catch (IOException e) {
                closeQuietly();
                throw new ComException(UIntToInt(0x82DA0100L), "Connect failed: " + e.getMessage());
            }
        }

        private void writeLine(String s) throws IOException {
            if (!s.endsWith("\r\n")) s = s + "\r\n";
            bout.write(s.getBytes(StandardCharsets.US_ASCII));
            bout.flush();
        }

        private String readAsciiLine() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
            int b;
            while ((b = bin.read()) != -1) {
                if (b == '\n') break;
                if (b != '\r') baos.write(b);
            }
            if (b == -1 && baos.size() == 0) throw new EOFException("XBDM closed connection");
            return baos.toString(StandardCharsets.US_ASCII);
        }

        private byte[] readN(int n) throws IOException {
            byte[] buf = new byte[n];
            int off = 0;
            while (off < n) {
                int r = bin.read(buf, off, n - off);
                if (r < 0) throw new EOFException("Unexpected EOF reading binary");
                off += r;
            }
            return buf;
        }

        private static String toHex(byte[] b) {
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte value : b) sb.append(String.format("%02X", value));
            return sb.toString();
        }

        private void closeQuietly() {
            try { if (bin != null) bin.close(); } catch (IOException ignored) {}
            try { if (bout != null) bout.close(); } catch (IOException ignored) {}
            try { if (sock != null) sock.close(); } catch (IOException ignored) {}
            bin = null; bout = null; sock = null;
        }

        private final class XbdmDebugTarget implements IXboxDebugTarget {
            @Override
            public void GetMemory(long address, long length, byte[] outBuf, long[] outRead) {
                ensureConnected();
                try {
                    writeLine("getmem addr=0x" + Long.toHexString(address).toUpperCase(Locale.ROOT) + " length=" + length);
                    String first = readAsciiLine();
                    String lower = first.toLowerCase(Locale.ROOT);
                    int copied = 0;
                    if (lower.contains("binary")) {
                        String lenLine = readAsciiLine().trim();
                        int n = Integer.parseInt(lenLine);
                        byte[] data = readN(n);
                        copied = Math.min(outBuf.length, Math.min((int) length, data.length));
                        System.arraycopy(data, 0, outBuf, 0, copied);
                        try { readAsciiLine(); } catch (IOException ignored) {}
                    } else {
                        String hex = extractField(first, "data=");
                        int n = Math.min(outBuf.length, Math.min((int) length, hex.length() / 2));
                        for (int i = 0; i < n; i++) {
                            int hi = Character.digit(hex.charAt(i * 2), 16);
                            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
                            outBuf[i] = (byte) ((hi << 4) | lo);
                        }
                        copied = n;
                    }
                    if (outRead != null && outRead.length > 0) outRead[0] = copied;
                } catch (IOException e) {
                    throw new ComException(UIntToInt(0x82DA0007L), "getmem failed: " + e.getMessage());
                }
            }

            @Override public void InvalidateMemoryCache(boolean unused, long address, long length) {
                /* no-op */
            }

            @Override
            public void SetMemory(long address, long length, byte[] data, long[] outWritten) {
                ensureConnected();
                try {
                    int n = (int) Math.min(length, data.length);
                    StringBuilder hex = new StringBuilder(n * 2);
                    for (int i = 0; i < n; i++) hex.append(String.format("%02X", data[i]));
                    writeLine("setmem addr=0x" + Long.toHexString(address).toUpperCase(Locale.ROOT) + " data=" + hex);
                    try { readAsciiLine(); } catch (IOException ignored) {}
                    if (outWritten != null && outWritten.length > 0) outWritten[0] = n;
                } catch (IOException e) {
                    throw new ComException(UIntToInt(0x82DA0007L), "setmem failed: " + e.getMessage());
                }
            }

            private String extractField(String resp, String key) {
                int i = resp.indexOf(key);
                if (i < 0) return "";
                int e = resp.indexOf(' ', i + key.length());
                return resp.substring(i + key.length(), e < 0 ? resp.length() : e).trim();
            }
        }
    }

    /**Begin JRPC Port*/

    public enum TemperatureType {
        CPU, GPU, EDRAM, MotherBoard
    }

    public enum ThreadType {
        System, Title
    }

    public enum XNotiyLogo {
        XBOX_LOGO(0), NEW_MESSAGE_LOGO(1), FRIEND_REQUEST_LOGO(2), NEW_MESSAGE(3), FLASHING_XBOX_LOGO(4),
        GAMERTAG_SENT_YOU_A_MESSAGE(5), GAMERTAG_SINGED_OUT(6), GAMERTAG_SIGNEDIN(7),
        GAMERTAG_SIGNED_INTO_XBOX_LIVE(8), GAMERTAG_SIGNED_IN_OFFLINE(9), GAMERTAG_WANTS_TO_CHAT(10),
        DISCONNECTED_FROM_XBOX_LIVE(11), DOWNLOAD(12), FLASHING_MUSIC_SYMBOL(13), FLASHING_HAPPY_FACE(14),
        FLASHING_FROWNING_FACE(15), FLASHING_DOUBLE_SIDED_HAMMER(16), GAMERTAG_WANTS_TO_CHAT_2(17),
        PLEASE_REINSERT_MEMORY_UNIT(18), PLEASE_RECONNECT_CONTROLLERM(19), GAMERTAG_HAS_JOINED_CHAT(20),
        GAMERTAG_HAS_LEFT_CHAT(21), GAME_INVITE_SENT(22), FLASH_LOGO(23), PAGE_SENT_TO(24), FOUR_2(25), FOUR_3(26),
        ACHIEVEMENT_UNLOCKED(27), FOUR_9(28), GAMERTAG_WANTS_TO_TALK_IN_VIDEO_KINECT(29), VIDEO_CHAT_INVITE_SENT(30),
        READY_TO_PLAY(31), CANT_DOWNLOAD_X(32), DOWNLOAD_STOPPED_FOR_X(33), FLASHING_XBOX_CONSOLE(34),
        X_SENT_YOU_A_GAME_MESSAGE(35), DEVICE_FULL(36), FOUR_7(37), FLASHING_CHAT_ICON(38),
        ACHIEVEMENTS_UNLOCKED(39), X_HAS_SENT_YOU_A_NUDGE(40), MESSENGER_DISCONNECTED(41), BLANK(42),
        CANT_SIGN_IN_MESSENGER(43), MISSED_MESSENGER_CONVERSATION(44), FAMILY_TIMER_X_TIME_REMAINING(45),
        DISCONNECTED_XBOX_LIVE_11_MINUTES_REMAINING(46), KINECT_HEALTH_EFFECTS(47), FOUR_5(48),
        GAMERTAG_WANTS_YOU_TO_JOIN_AN_XBOX_LIVE_PARTY(49), PARTY_INVITE_SENT(50),
        GAME_INVITE_SENT_TO_XBOX_LIVE_PARTY(51), KICKED_FROM_XBOX_LIVE_PARTY(52), NULLED(53),
        DISCONNECTED_XBOX_LIVE_PARTY(54), DOWNLOADED(55), CANT_CONNECT_XBL_PARTY(56),
        GAMERTAG_HAS_JOINED_XBL_PARTY(57), GAMERTAG_HAS_LEFT_XBL_PARTY(58), GAMER_PICTURE_UNLOCKED(59),
        AVATAR_AWARD_UNLOCKED(60), JOINED_XBL_PARTY(61), PLEASE_REINSERT_USB_STORAGE_DEVICE(62),
        PLAYER_MUTED(63), PLAYER_UNMUTED(64), FLASHING_CHAT_SYMBOL(65), UPDATING(76);
        public final long value;
        XNotiyLogo(long v) { this.value = v; }
        }

    public enum XboxRebootFlags {
        Title(0), Wait(1), Cold(2), Warm(4), Stop(8);
        public final int value;
        XboxRebootFlags(int v){this.value=v;
        }
    }

    public enum LEDState {
        OFF(0x00), RED(0x08), GREEN(0x80), ORANGE(0x88);
        public final int value;
        LEDState(int v) {
            this.value=v;
        }
    }

    private static final long RET_VOID = 0;
    private static final long RET_INT = 1;
    public static final long RET_STRING = 2;
    private static final long RET_FLOAT = 3;
    private static final long RET_BYTE = 4;
    private static final long RET_INT_ARRAY = 5;
    private static final long RET_FLOAT_ARRAY = 6;
    private static final long RET_BYTE_ARRAY = 7;
    private static final long RET_UINT64 = 8;
    private static final long RET_UINT64_ARRAY = 9;
    private static long connectionId = 0;
    private static boolean connectionIdInitialized = false;
    public static final long JRPCVersion = 2;

    public static String ToHexString(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            int b = (byte) s.charAt(i);
            if (b < 0) b += 256;
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    public static byte[] Push(byte[] inArray, byte value) {
        byte[] outArray = Arrays.copyOf(inArray, inArray.length + 1);
        outArray[inArray.length] = value;
        return outArray;
    }

    public static byte[] ToByteArray(String s) {
        byte[] ret = new byte[s.length() + 1];
        for (int i = 0;
             i < s.length();
             i++) ret[i] = (byte) s.charAt(i);
        return ret;
    }

    public static int find(String s, String ptr) {
        if (ptr.length() == 0 || s.length() == 0) return -1;
        for (int i = 0;
             i <= s.length() - ptr.length();
             i++) {
            if (s.charAt(i) == ptr.charAt(0)) {
                boolean found = true;
                for (int q = 0;
                     q < ptr.length();
                     q++) {
                    if (s.charAt(i + q) != ptr.charAt(q)) {
                        found = false; break;
                    }
                }
                if (found) return i;
            }
        }
        return -1;
    }

    public static boolean Connect(IXboxConsole console, IXboxConsole[] outConsole, String xboxNameOrIP) {
        IXboxConsole con;
        String target = xboxNameOrIP;
        if (target == null || target.equals("default")) {
            XboxManager mgr = new XboxManager();
            target = (mgr.DefaultConsole != null) ? mgr.DefaultConsole : mgr.getDefaultConsole();
        }
        XboxManager mgr2 = new XboxManager();
        con = mgr2.OpenConsole(target);
        boolean connected = false;
        while (!connected) {
            try {
                connectionId = con.OpenConnection(null);
                connectionIdInitialized = true;
                connected = true;
            } catch (ComException ex) {
                if (ex.getErrorCode() == UIntToInt(0x82DA0100L)) {
                    outConsole[0] = con;
                    return false;
                } else {
                    throw ex;
                }
            }
        }
        outConsole[0] = con;
        return true;
    }

    public static String XboxIP(IXboxConsole console) {
        long ip = console.IPAddress();
        byte[] address = new byte[] {
                (byte)((ip >> 24) & 0xFF),
                (byte)((ip >> 16) & 0xFF),
                (byte)((ip >> 8) & 0xFF),
                (byte)(ip & 0xFF)
        };
        try {
            return InetAddress.getByAddress(address).getHostAddress();
        }
        catch (UnknownHostException e) {
            return "0.0.0.0";
        }
    }

    static long ConvertToUInt64(Object o) {
        if (o instanceof Boolean) return ((Boolean)o) ? 1L : 0L;
        else if (o instanceof Byte) return ((Byte)o) & 0xFFL;
        else if (o instanceof Short) return ((Short)o) & 0xFFFFL;
        else if (o instanceof Integer) return ((Integer)o) & 0xFFFFFFFFL;
        else if (o instanceof Long) return (Long)o;
        else if (o instanceof Float) return Double.doubleToLongBits(((Float)o).doubleValue());
        else if (o instanceof Double) return Double.doubleToLongBits((Double)o);
        else return 0L;
    }

    static boolean IsValidStructType(Class<?> t) {
        return !t.isPrimitive();
    }

    static boolean IsValidReturnType(Class<?> t) {
        return true;
    }

    static void ReverseBytes(byte[] buffer, int groupSize) {
        if (buffer.length % groupSize != 0) throw new IllegalArgumentException("Group size must be a multiple of the buffer length");
        for (int num1 = 0;
             num1 < buffer.length;
             num1 += groupSize) {
            int i = num1, j = num1 + groupSize - 1;
            while (i < j) {
                byte tmp = buffer[i];
                buffer[i]=buffer[j];
                buffer[j]=tmp;
                i++;
                j--;
            }
        }
    }

    public static byte[] GetMemory(IXboxConsole console, long Address, long Length) {
        long[] Out = new long[] {0};
        byte[] ret = new byte[(int) Length];
        console.DebugTarget().GetMemory(Address, Length, ret, Out);
        console.DebugTarget().InvalidateMemoryCache(true, Address, Length);
        return ret;
    }

    public static byte ReadSByte(IXboxConsole c, long a) {
        return (byte)(GetMemory(c,a,1)[0]);
    }

    public static byte ReadByte(IXboxConsole c, long a) {
        return GetMemory(c,a,1)[0];
    }

    public static boolean ReadBool(IXboxConsole c, long a) {
        return GetMemory(c,a,1)[0]!=0;
    }

    public static float ReadFloat(IXboxConsole c, long a) {
        byte[] b=GetMemory(c,a,4); ReverseBytes(b,4);
        return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getFloat(0);
    }

    public static float[] ReadFloat(IXboxConsole c, long a, long n) {
        float[] r=new float[(int)n]; byte[] b=GetMemory(c,a,n*4);
        ReverseBytes(b,4);
        for(int i=0;
            i<n;
            i++) r[i]=ByteBuffer.wrap(b,i*4,4).order(ByteOrder.BIG_ENDIAN).getFloat();
        return r;
    }

    public static short ReadInt16(IXboxConsole c, long a) {
        byte[] b=GetMemory(c,a,2);
        ReverseBytes(b,2);
        return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getShort(0);
    }

    public static short[] ReadInt16(IXboxConsole c, long a, long n) {
        short[] r=new short[(int)n];
        byte[] b=GetMemory(c,a,n*2);
        ReverseBytes(b,2);
        for(int i=0;
            i<n;
            i++) r[i]=ByteBuffer.wrap(b,i*2,2).order(ByteOrder.BIG_ENDIAN).getShort();
        return r;
    }

    public static int ReadUInt16(IXboxConsole c, long a) {
        byte[] b=GetMemory(c,a,2);
        ReverseBytes(b,2);
        return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getShort(0)&0xFFFF;
    }

    public static int[] ReadUInt16(IXboxConsole c, long a, long n) {
        int[] r=new int[(int)n];
        byte[] b=GetMemory(c,a,n*2);
        ReverseBytes(b,2);
        for(int i=0;
            i<n;
            i++) r[i]=ByteBuffer.wrap(b,i*2,2).order(ByteOrder.BIG_ENDIAN).getShort()&0xFFFF;
        return r;
    }

    public static int ReadInt32(IXboxConsole c, long a) {
        byte[] b=GetMemory(c,a,4);
        ReverseBytes(b,4);
        return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getInt(0);
    }

    public static int[] ReadInt32(IXboxConsole c, long a, long n) {
        int[] r=new int[(int)n];
        byte[] b=GetMemory(c,a,n*4);
        ReverseBytes(b,4);
        for(int i=0;
            i<n;
            i++) r[i]=ByteBuffer.wrap(b,i*4,4).order(ByteOrder.BIG_ENDIAN).getInt();
        return r;
    }

    public static long ReadUInt32(IXboxConsole c, long a) {
        byte[] b=GetMemory(c,a,4);
        ReverseBytes(b,4);
        return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getInt(0)&0xFFFFFFFFL;
    }

    public static long[] ReadUInt32(IXboxConsole c, long a, long n) {
        long[] r=new long[(int)n];
        byte[] b=GetMemory(c,a,n*4);
        ReverseBytes(b,4);
        for(int i=0;
            i<n;
            i++) r[i]=ByteBuffer.wrap(b,i*4,4).order(ByteOrder.BIG_ENDIAN).getInt()&0xFFFFFFFFL;
        return r;
    }

    public static long ReadInt64(IXboxConsole c, long a) {
        byte[] b=GetMemory(c,a,8);
        ReverseBytes(b,8);
        return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getLong(0);
    }

    public static long[] ReadInt64(IXboxConsole c, long a, long n) {
        long[] r=new long[(int)n];
        byte[] b=GetMemory(c,a,n*8);
        ReverseBytes(b,8);
        for(int i=0;
            i<n;
            i++) r[i]=ByteBuffer.wrap(b,i*8,8).order(ByteOrder.BIG_ENDIAN).getLong();
        return r;
    }

    public static long ReadUInt64(IXboxConsole c, long a) {
        byte[] b=GetMemory(c,a,8);
        ReverseBytes(b,8);
        return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getLong(0);
    }

    public static long[] ReadUInt64(IXboxConsole c, long a, long n) {
        long[] r=new long[(int)n];
        byte[] b=GetMemory(c,a,n*8);
        ReverseBytes(b,8);
        for(int i=0;
            i<n;
            i++) r[i]=ByteBuffer.wrap(b,i*8,8).order(ByteOrder.BIG_ENDIAN).getLong();
        return r;
    }

    public static String ReadString(IXboxConsole c, long a, long size) {
        return new String(GetMemory(c,a,size));
    }

    public static void SetMemory(IXboxConsole c, long a, byte[] d) {
        long[] o=new long[]{0};
        c.DebugTarget().SetMemory(a,d.length,d,o);
    }

    public static void WriteSByte(IXboxConsole c, long a, byte v) {
        SetMemory(c,a,new byte[]{v});
    }

    public static void WriteSByte(IXboxConsole c, long a, byte[] v) {
        SetMemory(c,a,Arrays.copyOf(v,v.length));
    }

    public static void WriteByte(IXboxConsole c, long a, byte v) {
        SetMemory(c,a,new byte[]{v});
    }

    public static void WriteByte(IXboxConsole c, long a, byte[] v) {
        SetMemory(c,a,v);
    }

    public static void WriteBool(IXboxConsole c, long a, boolean v) {
        SetMemory(c,a,new byte[]{(byte)(v?1:0)});
    }

    public static void WriteBool(IXboxConsole c, long a, boolean[] v) {
        byte[] b=new byte[v.length];
        for(int i=0;
            i<v.length;
            i++) b[i]=(byte)(v[i]?1:0);
        SetMemory(c,a,b);
    }

    public static void WriteFloat(IXboxConsole c, long a, float v) {
        byte[] b=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array();
        ReverseBytes(b,4);
        SetMemory(c,a,b);
    }

    public static void WriteFloat(IXboxConsole c, long a, float[] v) {
        byte[] b=new byte[v.length*4];
        for(int i=0;
            i<v.length;
            i++) {
            byte[] t=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v[i]).array();
            System.arraycopy(t,0,b,i*4,4);
        }
        ReverseBytes(b,4);
        SetMemory(c,a,b);
    }

    public static void WriteInt16(IXboxConsole c, long a, short v) {
        byte[] b=ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array();
        ReverseBytes(b,2);
        SetMemory(c,a,b);
    }

    public static void WriteInt16(IXboxConsole c, long a, short[] v) {
        byte[] b=new byte[v.length*2];
        for(int i=0;
            i<v.length;
            i++) {
            byte[] t=ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v[i]).array();
            System.arraycopy(t,0,b,i*2,2);
        }
        ReverseBytes(b,2);
        SetMemory(c,a,b);
    }

    public static void WriteUInt16(IXboxConsole c, long a, int v) {
        short s=(short)(v&0xFFFF);
        byte[] b=ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(s).array();
        ReverseBytes(b,2);
        SetMemory(c,a,b);
    }

    public static void WriteUInt16(IXboxConsole c, long a, int[] v) {
        byte[] b=new byte[v.length*2];
        for(int i=0;
            i<v.length;
            i++) {
            short s=(short)(v[i]&0xFFFF);
            byte[] t=ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(s).array();
            System.arraycopy(t,0,b,i*2,2);
        }
        ReverseBytes(b,2);
        SetMemory(c,a,b);
    }

    public static void WriteInt32(IXboxConsole c, long a, int v) {
        byte[] b=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
        ReverseBytes(b,4);
        SetMemory(c,a,b);
    }

    public static void WriteInt32(IXboxConsole c, long a, int[] v) {
        byte[] b=new byte[v.length*4];
        for(int i=0;i<v.length;i++) {
            byte[] t=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v[i]).array();
            System.arraycopy(t,0,b,i*4,4);
        }
        ReverseBytes(b,4);
        SetMemory(c,a,b);
    }

    public static void WriteUInt32(IXboxConsole c, long a, long v) {
        int i=(int)(v&0xFFFFFFFFL);
        byte[] b=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i).array();
        ReverseBytes(b,4);
        SetMemory(c,a,b);
    }

    public static void WriteUInt32(IXboxConsole c, long a, long[] v) {
        byte[] b=new byte[v.length*4];
        for(int i=0;i<v.length;i++) {
            int x=(int)(v[i]&0xFFFFFFFFL);
            byte[] t=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(x).array();
            System.arraycopy(t,0,b,i*4,4);
        }
        ReverseBytes(b,4);
        SetMemory(c,a,b);
    }

    public static void WriteInt64(IXboxConsole c, long a, long v) {
        byte[] b=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array();
        ReverseBytes(b,8);
        SetMemory(c,a,b);
    }

    public static void WriteInt64(IXboxConsole c, long a, long[] v) {
        byte[] b=new byte[v.length*8];
        for(int i=0;i<v.length;i++) {
            byte[] t=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v[i]).array();
            System.arraycopy(t,0,b,i*8,8);
        }
        ReverseBytes(b,8);
        SetMemory(c,a,b);
    }

    public static void WriteUInt64(IXboxConsole c, long a, long v) {
        byte[] b=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array();
        ReverseBytes(b,8);
        SetMemory(c,a,b);
    }

    public static void WriteUInt64(IXboxConsole c, long a, long[] v) {
        byte[] b=new byte[v.length*8];
        for(int i=0;i<v.length;i++) {
            byte[] t=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v[i]).array();
            System.arraycopy(t,0,b,i*8,8);
        }
        ReverseBytes(b,8);
        SetMemory(c,a,b);
    }

    public static void WriteString(IXboxConsole c, long a, String s) {
        byte[] v=new byte[0];
        for(int i=0;
            i<s.length();
            i++) v=Push(v,(byte)s.charAt(i));
        v=Push(v,(byte)0);
        SetMemory(c,a,v);
    }

    public static long ResolveFunction(IXboxConsole c, String module, long ordinal) {
        String cmd =
                "consolefeatures ver=" + JRPCVersion + " type=9 params=\"A\\0\\A\\2\\" + RET_STRING + "/"
                + module.length() + "\\" + ToHexString(module) + "\\" + RET_INT + "\\" + ordinal + "\\\"";
        String resp = SendCommand(c, cmd);
        return Long.parseLong(resp.substring(find(resp, " ")+1), 16);
    }

    public static String GetCPUKey(IXboxConsole c) {
        String cmd="consolefeatures ver="+JRPCVersion+" type=10 params=\"A\\0\\A\\0\\\"";
        String r=SendCommand(c,cmd);
        return r.substring(find(r," ")+1);
    }

    public static void ShutDownConsole(IXboxConsole c) {
        try { String cmd="consolefeatures ver="+JRPCVersion+" type=11 params=\"A\\0\\A\\0\\\"";
            SendCommand(c,cmd);
        } catch(Throwable t){}
    }

    public static byte[] WCHAR(String s) {
        byte[] b=new byte[s.length()*2+2];
        int i=1;
        for(int k=0;
            k<s.length();
            k++){ b[i]=(byte)s.charAt(k);
            i+=2;
        }
        return b;
    }

    public static byte[] ToWCHAR(String s) {
        return WCHAR(s);
    }

    public static long GetKernelVersion(IXboxConsole c) {
        String cmd="consolefeatures ver="+JRPCVersion+" type=13 params=\"A\\0\\A\\0\\\"";
        String r=SendCommand(c,cmd);
        return Long.parseLong(r.substring(find(r," ")+1));
    }

    public static void SetLeds(IXboxConsole c, LEDState tl, LEDState tr, LEDState bl, LEDState br) {
        String cmd=
                "consolefeatures ver="+JRPCVersion+" type=14 params=\"A\\0\\A\\4\\"+
                RET_INT+"\\"+tl.value+"\\"+RET_INT+"\\"+tr.value+"\\"+RET_INT+"\\"+bl.value+"\\"+RET_INT+"\\"+br.value+"\\\"";
        SendCommand(c,cmd);
    }

    public static long GetTemperature(IXboxConsole c, TemperatureType t){
        String cmd="consolefeatures ver="+JRPCVersion+" type=15 params=\"A\\0\\A\\1\\"+RET_INT+"\\"+t.ordinal()+"\\\"";
        String r=SendCommand(c,cmd);
        return Long.parseLong(r.substring(find(r," ")+1),16);
    }

    public static void XNotify(IXboxConsole c, String text) {
        XNotify(c,XNotiyLogo.FLASHING_XBOX_CONSOLE,text);
    }

    public static void XNotify(IXboxConsole c, XNotiyLogo type, String text) {
        String cmd=
                "consolefeatures ver="+JRPCVersion+" type=12 params=\"A\\0\\A\\2\\"+
                RET_STRING+"/"+text.length()+"\\"+ToHexString(text)+"\\"+RET_INT+"\\"+type.value+"\\\"";
        SendCommand(c,cmd);
    }

    public static long XamGetCurrentTitleId(IXboxConsole c) {
        String cmd="consolefeatures ver="+JRPCVersion+" type=16 params=\"A\\0\\A\\0\\\"";
        String r=SendCommand(c,cmd);
        return Long.parseLong(r.substring(find(r," ")+1),16);
    }

    public static String ConsoleType(IXboxConsole c) {
        String cmd="consolefeatures ver="+JRPCVersion+" type=17 params=\"A\\0\\A\\0\\\"";
        String r=SendCommand(c,cmd);
        return r.substring(find(r," ")+1);
    }

    public static void constantMemorySet(IXboxConsole c, long a, long v) {
        constantMemorySetting(c,a,v,false,0,false,0);
    }

    public static void constantMemorySet(IXboxConsole c, long a, long v, long title) {
        constantMemorySetting(c,a,v,false,0,true,title);
    }

    public static void constantMemorySet(IXboxConsole c, long a, long v, long ifVal, long title) {
        constantMemorySetting(c,a,v,true,ifVal,true,title);
    }

    public static void constantMemorySetting(IXboxConsole c, long a, long v, boolean useIf, long ifV, boolean useTitle, long title) {
        String cmd=
                "consolefeatures ver="+JRPCVersion+" type=18 params=\"A\\"+Long.toHexString(a).toUpperCase()+"\\A\\5\\"+
                RET_INT+"\\"+UIntToInt(v)+"\\"+RET_INT+"\\"+(useIf?1:0)+"\\"+RET_INT+"\\"+ifV+"\\"+RET_INT+"\\"+(useTitle?1:0)+"\\"+RET_INT+"\\"+UIntToInt(title)+"\\\"";
        SendCommand(c,cmd);
    }

    private static long TypeToType(Class<?> T, boolean array) {
        if (T==Integer.class||T==int.class||T==Short.class||T==short.class) {
            return array?RET_INT_ARRAY:RET_INT;
        }
        if (T==String.class||T==char[].class) {
            return RET_STRING;
        }
        if (T==Float.class||T==float.class||T==Double.class||T==double.class) {
            return array?RET_FLOAT_ARRAY:RET_FLOAT;
        }
        if (T==Byte.class||T==byte.class||T==Character.class||T==char.class) {
            return array?RET_BYTE_ARRAY:RET_BYTE;
        }
        if (T==Long.class||T==long.class) {
            return array?RET_UINT64_ARRAY:RET_UINT64;
        }
        return RET_UINT64;
    }

    public static <T> T Call(IXboxConsole c, long addr, Object... args) {
        @SuppressWarnings("unchecked")
        T r=(T)CallArgs(c,true,TypeToType(Object.class,false),Object.class,null,0,addr,0,false,args);
        return r;
    }

    public static <T> T Call(IXboxConsole c, String mod, int ord, Object... args) {
        @SuppressWarnings("unchecked")
        T r=(T)CallArgs(c,true,TypeToType(Object.class,false),Object.class,mod,ord,0,0,false,args);
        return r;
    }

    public static <T> T Call(IXboxConsole c, ThreadType tt, long addr, Object... args) {
        @SuppressWarnings("unchecked")
        T r=(T)CallArgs(c,tt==ThreadType.System,TypeToType(Object.class,false),Object.class,null,0,addr,0,false,args);
        return r;
    }

    public static <T> T Call(IXboxConsole c, ThreadType tt, String mod, int ord, Object... args) {
        @SuppressWarnings("unchecked")
        T r=(T)CallArgs(c,tt==ThreadType.System,TypeToType(Object.class,false),Object.class,mod,ord,0,0,false,args);
        return r;
    }

    public static void CallVoid(IXboxConsole c, long addr, Object... args) {
        CallArgs(c,true,RET_VOID,void.class,null,0,addr,0,false,args);
    }

    public static void CallVoid(IXboxConsole c, String mod, int ord, Object... args) {
        CallArgs(c,true,RET_VOID,void.class,mod,ord,0,0,false,args);
    }

    public static void CallVoid(IXboxConsole c, ThreadType tt, long addr, Object... args) {
        CallArgs(c,tt==ThreadType.System,RET_VOID,void.class,null,0,addr,0,false,args);
    }

    public static void CallVoid(IXboxConsole c, ThreadType tt, String mod, int ord, Object... args) {
        CallArgs(c,tt==ThreadType.System,RET_VOID,void.class,mod,ord,0,0,false,args);
    }

    public static <T> T[] CallArray(IXboxConsole c, long a, long n, Object... args){ if(n==0){
        @SuppressWarnings("unchecked")
        T[] e=(T[])new Object[1];
        return e;
    }
    @SuppressWarnings("unchecked")
    T[] r=(T[])CallArgs(c,true,TypeToType(Object.class,true),Object.class,null,0,a,n,false,args);
        return r;
    }

    public static <T> T[] CallArray(IXboxConsole c, String m, int o, long n, Object... args) {
        if(n==0) {
            @SuppressWarnings("unchecked")
            T[] e=(T[])new Object[1];
            return e;
        }
        @SuppressWarnings("unchecked")
        T[] r=(T[])CallArgs(c,true,TypeToType(Object.class,true),Object.class,m,o,0,n,false,args);
        return r;
    }

    public static <T> T[] CallArray(IXboxConsole c, ThreadType t, long a, long n, Object... args) {
        if(n==0) {
            @SuppressWarnings("unchecked")
            T[] e=(T[])new Object[1];
            return e;
        }
        @SuppressWarnings("unchecked")
        T[] r=(T[])CallArgs(c,t==ThreadType.System,TypeToType(Object.class,true),Object.class,null,0,a,n,false,args);
        return r;
    }

    public static <T> T[] CallArray(IXboxConsole c, ThreadType t, String m, int o, long n, Object... args) {
        if(n==0) {
            @SuppressWarnings("unchecked")
            T[] e=(T[])new Object[1];
            return e;
        }
        @SuppressWarnings("unchecked")
        T[] r=(T[])CallArgs(c,t==ThreadType.System,TypeToType(Object.class,true),Object.class,m,o,0,n,false,args);
        return r;
    }

    public static String CallString(IXboxConsole c, long a, Object... args) {
        return (String)CallArgs(c,true,RET_STRING,String.class,null,0,a,0,false,args);
    }

    public static String CallString(IXboxConsole c, String m, int o, Object... args) {
        return (String)CallArgs(c,true,RET_STRING,String.class,m,o,0,0,false,args);
    }

    public static String CallString(IXboxConsole c, ThreadType t, long a, Object... args) {
        return (String)CallArgs(c,t==ThreadType.System,RET_STRING,String.class,null,0,a,0,false,args);
    }

    public static String CallString(IXboxConsole c, ThreadType t, String m, int o, Object... args) {
        return (String)CallArgs(c,t==ThreadType.System,RET_STRING,String.class,m,o,0,0,false,args);
    }

    private static int UIntToInt(long v) {
        return (int)(v&0xFFFFFFFFL);
    }

    private static byte[] IntArrayToByte(int[] arr) {
        byte[] b=new byte[arr.length*4];
        int q=0;
        for(int i=0;
            i<arr.length;
            i++,q+=4) {
            byte[] t=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(arr[i]).array();
            b[q]=t[0];
            b[q+1]=t[1];
            b[q+2]=t[2];
            b[q+3]=t[3];
        }
        return b;
    }

    public static <T> T CallVM(IXboxConsole c, long a, Object... args) {
        @SuppressWarnings("unchecked")
        T r=(T)CallArgs(c,true,TypeToType(Object.class,false),Object.class,null,0,a,0,true,args);
        return r;
    }

    public static <T> T CallVM(IXboxConsole c, String m, int o, Object... args) {
        @SuppressWarnings("unchecked")
        T r=(T)CallArgs(c,true,TypeToType(Object.class,false),Object.class,m,o,0,0,true,args);
        return r;
    }

    public static <T> T CallVM(IXboxConsole c, ThreadType t, long a, Object... args) {
        @SuppressWarnings("unchecked")
        T r=(T)CallArgs(c,t==ThreadType.System,TypeToType(Object.class,false),Object.class,null,0,a,0,true,args);
        return r;
    }

    public static <T> T CallVM(IXboxConsole c, ThreadType t, String m, int o, Object... args) {
        @SuppressWarnings("unchecked")
        T r=(T)CallArgs(c,t==ThreadType.System,TypeToType(Object.class,false),Object.class,m,o,0,0,true,args);
        return r;
    }

    public static void CallVMVoid(IXboxConsole c, long a, Object... args) {
        CallArgs(c,true,RET_VOID,void.class,null,0,a,0,true,args);
    }

    public static void CallVMVoid(IXboxConsole c, String m, int o, Object... args) {
        CallArgs(c,true,RET_VOID,void.class,m,o,0,0,true,args);
    }

    public static void CallVMVoid(IXboxConsole c, ThreadType t, long a, Object... args) {
        CallArgs(c,t==ThreadType.System,RET_VOID,void.class,null,0,a,0,true,args);
    }

    public static void CallVMVoid(IXboxConsole c, ThreadType t, String m, int o, Object... args) {
        CallArgs(c,t==ThreadType.System,RET_VOID,void.class,m,o,0,0,true,args);
    }

    public static <T> T[] CallVMArray(IXboxConsole c, long a, long n, Object... args) {
        if(n==0) {
            @SuppressWarnings("unchecked")
            T[] e=(T[])new Object[1];
            return e;
        }
        @SuppressWarnings("unchecked")
        T[] r=(T[])CallArgs(c,true,TypeToType(Object.class,true),Object.class,null,0,a,n,true,args);
        return r;
    }

    public static <T> T[] CallVMArray(IXboxConsole c, String m, int o, long n, Object... args) {
        if(n==0) {
            @SuppressWarnings("unchecked")
            T[] e=(T[])new Object[1];
            return e;
        }
        @SuppressWarnings("unchecked")
        T[] r=(T[])CallArgs(c,true,TypeToType(Object.class,true),Object.class,m,o,0,n,true,args);
        return r;
    }

    public static <T> T[] CallVMArray(IXboxConsole c, ThreadType t, long a, long n, Object... args) {
        if(n==0) {
            @SuppressWarnings("unchecked")
            T[] e=(T[])new Object[1];
            return e;
        }
        @SuppressWarnings("unchecked")
        T[] r=(T[])CallArgs(c,t==ThreadType.System,TypeToType(Object.class,true),Object.class,null,0,a,n,true,args);
        return r;
    }

    public static <T> T[] CallVMArray(IXboxConsole c, ThreadType t, String m, int o, long n, Object... args) {
        if(n==0) {
            @SuppressWarnings("unchecked")
            T[] e=(T[])new Object[1];
            return e;
        }
        @SuppressWarnings("unchecked")
        T[] r=(T[])CallArgs(c,t==ThreadType.System,TypeToType(Object.class,true),Object.class,m,o,0,n,true,args);
        return r;
    }

    public static String CallVMString(IXboxConsole c, long a, Object... args) {
        return (String)CallArgs(c,true,RET_STRING,String.class,null,0,a,0,true,args);
    }

    public static String CallVMString(IXboxConsole c, String m, int o, Object... args) {
        return (String)CallArgs(c,true,RET_STRING,String.class,m,o,0,0,true,args);
    }

    public static String CallVMString(IXboxConsole c, ThreadType t, long a, Object... args) {
        return (String)CallArgs(c,t==ThreadType.System,RET_STRING,String.class,null,0,a,0,true,args);
    }

    public static String CallVMString(IXboxConsole c, ThreadType t, String m, int o, Object... args) {
        return (String)CallArgs(c,t==ThreadType.System,RET_STRING,String.class,m,o,0,0,true,args);
    }

    private static String SendCommand(IXboxConsole c, String cmd){
        if (!connectionIdInitialized) throw new RuntimeException("IXboxConsole argument did not connect using JRPC's connect function.");
        String[] out = new String[1];
        try {
            c.SendTextCommand(connectionId, cmd, out);
            String r = out[0]==null ? "" : out[0];
            if (r.contains("error=")) throw new RuntimeException(r.substring(11));
            if (r.contains("DEBUG")) throw new RuntimeException("JRPC is not installed on the current console");
            return r;
        } catch (ComException ex) {
            if (ex.getErrorCode() == UIntToInt(0x82DA0007L)) throw new RuntimeException("JRPC is not installed on the current console");
            else throw ex;
        }
    }

    private static Object CallArgs(
            IXboxConsole c, boolean systemThread, long type, Class<?> t, String module, int ordinal, long addr, long arraySize, boolean vm, Object... args) {
        if (!IsValidReturnType(t)) throw new RuntimeException(
                "Invalid type "+t.getName()+System.lineSeparator()
                +"JRPC only supports: bool, byte, short, int, long, ushort, uint, ulong, float, double");
        c.setConnectTimeout(4000000); c.setConversationTimeout(4000000);

        StringBuilder sb = new StringBuilder();
        long nArgs = 0;

        for (Object obj : args) {
            boolean done = false;
            if (obj instanceof Long) {
                sb.append(RET_INT).append("\\").append(UIntToInt((Long)obj)).append("\\");
                nArgs++;
                done=true;
            }
            if (obj instanceof Integer || obj instanceof Boolean || obj instanceof Byte) {
                if (obj instanceof Boolean) sb.append(RET_INT).append("\\").append(((Boolean)obj)?1:0).append("\\");
                else if (obj instanceof Byte) sb.append(RET_INT).append("\\").append(((Byte)obj)&0xFF).append("\\");
                else sb.append(RET_INT).append("\\").append((Integer)obj).append("\\");
                nArgs++;
                done=true;
            } else if (obj instanceof int[] || obj instanceof long[]) {
                if (!vm) {
                    if (obj instanceof int[]) {
                        byte[] arr = IntArrayToByte((int[])obj);
                        sb.append(RET_BYTE_ARRAY).append("/").append(arr.length).append("\\");
                        for (byte b : arr) sb.append(String.format("%02X", b));
                        sb.append("\\");
                        nArgs++;
                    } else {
                        long[] u = (long[])obj;
                        int[] asInt = new int[u.length];
                        for (int i = 0;
                             i < u.length;
                             i++) asInt[i] = (int) (u[i] & 0xFFFFFFFFL);
                        byte[] arr = IntArrayToByte(asInt);
                        sb.append(RET_BYTE_ARRAY).append("/").append(arr.length).append("\\");
                        for (byte b : arr) sb.append(String.format("%02X", b));
                        sb.append("\\");
                        nArgs++;
                    }
                } else {
                    boolean isInt = obj instanceof int[];
                    int len = isInt ? ((int[])obj).length : ((long[])obj).length;
                    for (int i=0;
                         i<len;
                         i++) {
                        int val = isInt ? ((int[])obj)[i] : (int)(((long[])obj)[i] & 0xFFFFFFFFL);
                        sb.append(RET_INT).append("\\").append(val).append("\\");
                        nArgs++;
                    }
                }
                done=true;
            } else if (obj instanceof String) {
                String s = (String)obj;
                sb.append(RET_BYTE_ARRAY).append("/").append(s.length()).append("\\").append(ToHexString(s)).append("\\");
                nArgs++;
                done=true;
            } else if (obj instanceof Double) {
                sb.append(RET_FLOAT).append("\\").append(Double.toString((Double)obj)).append("\\");
                nArgs++;
                done=true;
            } else if (obj instanceof Float) {
                sb.append(RET_FLOAT).append("\\").append(Float.toString((Float)obj)).append("\\");
                nArgs++;
                done=true;
            } else if (obj instanceof float[]) {
                float[] fa = (float[])obj;
                if (!vm) {
                    sb.append(RET_BYTE_ARRAY).append("/").append(fa.length*4).append("\\");
                    for (float v : fa) {
                        byte[] bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array();
                        ReverseBytes(bytes,4);
                        for (int q=0;
                             q<4;
                             q++) sb.append(String.format("%02X", bytes[q]));
                    }
                    sb.append("\\");
                    nArgs++;
                } else {
                    for (float v : fa) {
                        sb.append(RET_FLOAT).append("\\").append(Float.toString(v)).append("\\");
                        nArgs++;
                    }
                }
                done=true;
            } else if (obj instanceof byte[]) {
                byte[] ba = (byte[])obj;
                sb.append(RET_BYTE_ARRAY).append("/").append(ba.length).append("\\");
                for (byte b : ba) sb.append(Byte.toString(b));
                sb.append("\\");
                nArgs++;
                done=true;
            }
            if (!done) {
                sb.append(RET_UINT64).append("\\").append(Long.toString(ConvertToUInt64(obj))).append("\\");
                nArgs++;
            }
        }
        sb.append("\"");

        String startCmd = "consolefeatures ver=" + JRPCVersion + " type=" + type + (systemThread ? " system" : "") +
                (module != null ? " module=\"" + module + "\" ord=" + ordinal : "") + (vm ? " VM" : "") +
                " as=" + arraySize + " params=\"A\\" + Long.toHexString(addr).toUpperCase() + "\\A\\" + nArgs + "\\" + sb;

        if (nArgs > 37) throw new RuntimeException("Cannot use more than 37 parameters in a call");

        String resp = SendCommand(c, startCmd);
        String findStr = "buf_addr=";
        while (resp.contains(findStr)) {
            try {
                Thread.sleep(250);
            }
            catch (InterruptedException ignored) {}
            int idx = find(resp, findStr);
            String sub = resp.substring(idx + findStr.length());
            long address = Long.parseLong(sub, 16);
            resp = SendCommand(c, "consolefeatures " + findStr + "0x" + Long.toHexString(address).toUpperCase());
        }
        c.setConversationTimeout(2000);
        c.setConnectTimeout(5000);

        if (type == RET_INT) {
            long uVal = Long.parseLong(resp.substring(find(resp, " ")+1), 16);
            return uVal;
        } else if (type == RET_STRING) {
            return resp.substring(find(resp, " ")+1);
        } else if (type == RET_FLOAT) {
            String v = resp.substring(find(resp, " ")+1);
            try {
                if (v.contains(".") || v.contains("e") || v.contains("E")) return Double.parseDouble(v);
                else return Float.parseFloat(v);
            }
            catch (NumberFormatException nfe) {
                return 0.0;
            }
        } else if (type == RET_BYTE) {
            int bVal = Integer.parseInt(resp.substring(find(resp, " ")+1), 16);
            return (byte)(bVal & 0xFF);
        } else if (type == RET_UINT64) {
            String v = resp.substring(find(resp, " ")+1);
            return Long.parseLong(v, 16);
        }

        if (type == RET_INT_ARRAY) {
            String s = resp.substring(find(resp, " ")+1);
            int tmp = 0;
            String temp="";
            long[] arr = new long[8];
            for (int i=0;
                 i<s.length();
                 i++) {
                char ch=s.charAt(i);
                if (ch!=',' && ch!=';') temp+=ch;
                else {
                    arr[tmp]=Long.parseLong(temp,16);
                    tmp++;
                    temp="";
                }
                if (ch==';')
                    break;
            }
            return arr;
        }

        if (type == RET_FLOAT_ARRAY) {
            String s = resp.substring(find(resp, " ")+1);
            int tmp=0;
            String temp="";
            float[] arr = new float[(int)arraySize];
            for (int i=0;
                 i<s.length();
                 i++) {
                char ch=s.charAt(i);
                if (ch!=',' && ch!=';') temp+=ch;
                else {
                    arr[tmp]=Float.parseFloat(temp);
                    tmp++;
                    temp="";
                }
                if (ch==';')
                    break;
            }
            return arr;
        }

        if (type == RET_BYTE_ARRAY) {
            String s = resp.substring(find(resp, " ")+1);
            int tmp=0;
            String temp="";
            byte[] arr = new byte[(int)arraySize];
            for (int i=0;
                 i<s.length();
                 i++) {
                char ch=s.charAt(i);
                if (ch!=',' && ch!=';') temp+=ch;
                else {
                    arr[tmp]=(byte)Integer.parseInt(temp);
                    tmp++;
                    temp="";
                }
                if (ch==';')
                    break;
            }
            return arr;
        }

        if (type == RET_UINT64_ARRAY) {
            String s = resp.substring(find(resp, " ")+1);
            int tmp=0;
            String temp="";
            long[] arr = new long[(int)arraySize];
            for (int i=0;
                 i<s.length();
                 i++) {
                char ch=s.charAt(i);
                if (ch!=',' && ch!=';') temp+=ch;
                else {
                    arr[tmp]=Long.parseLong(temp);
                    tmp++;
                    temp="";
                }
                if (ch==';') break;
            }
            return arr;
        }

        if (type == RET_VOID) return 0;
        return Long.parseLong(resp.substring(find(resp," ")+1), 16);
    }

    public static String ExecRaw(IXboxConsole c, String cmd) {
        String[] out = new String[1];
        c.SendTextCommand(connectionId, cmd, out);
        return out[0] == null ? "" : out[0];
    }
}
