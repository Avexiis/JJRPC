package com.jjrpc.xdevkit;

import com.jjrpc.JRPC;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class XboxFeatures {

    private XboxFeatures() {}

    public enum XboxColor {
        BLACK, BLUE, BLUEGRAY, NOSIDECAR, WHITE;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum SystemInfo {
        HDD, // size/serial info
        Type,
        Platform,
        System,
        BaseKrnlVersion,
        KrnlVersion,
        XDKVersion
    }

    public enum XboxReboot {
        Cold, Warm
    }

    public static String sendRaw(JRPC.IXboxConsole c, String cmd) {
        String[] out = new String[1];
        c.SendTextCommand(1L, cmd, out);
        return out[0] == null ? "" : out[0];
    }

    public static String getBoxID(JRPC.IXboxConsole c) {
        return strip200(sendRaw(c, "BOXID"));
    }

    public static String getXUID(JRPC.IXboxConsole c) {
        return strip200(sendRaw(c, "xuid"));
    }

    public static String getConsoleID(JRPC.IXboxConsole c) {
        String s = sendRaw(c, "getconsoleid");
        s = s.replace("200- ", "").trim();
        s = s.replace("consoleid=", "").trim();
        return s;
    }

    public static String getDMVersion(JRPC.IXboxConsole c) {
        return strip200(sendRaw(c, "dmversion"));
    }

    public static String getConsoleType(JRPC.IXboxConsole c) {
        return JRPC.ConsoleType(c);
    }

    public static long getTitleID(JRPC.IXboxConsole c) {
        return JRPC.XamGetCurrentTitleId(c);
    }

    public static long getKernelVersion(JRPC.IXboxConsole c) {
        return JRPC.GetKernelVersion(c);
    }

    public static String getCPUKey(JRPC.IXboxConsole c) {
        return JRPC.GetCPUKey(c);
    }

    public static long getTemperature(JRPC.IXboxConsole c, JRPC.TemperatureType t) {
        return JRPC.GetTemperature(c, t);
    }

    public static void setLeds(JRPC.IXboxConsole c, JRPC.LEDState tl, JRPC.LEDState tr, JRPC.LEDState bl, JRPC.LEDState br) {
        JRPC.SetLeds(c, tl, tr, bl, br);
    }

    public static void setConsoleColor(JRPC.IXboxConsole c, XboxColor color) {
        sendExpectOk(c, "setcolor name=" + color.wireName());
    }

    public static void reboot(JRPC.IXboxConsole c, XboxReboot type) {
        switch (type) {
            case Cold -> sendExpectOk(c, "magicboot cold");
            case Warm -> sendExpectOk(c, "magicboot warm");
        }
    }

    public static void freezeConsole(JRPC.IXboxConsole c, boolean freeze) {
        sendExpectOk(c, freeze ? "stop" : "go");
    }

    public static String getSystemInfo(JRPC.IXboxConsole c, SystemInfo info) {
        switch (info) {
            case Type:
                return strip200(sendRaw(c, "consoletype"));
            default: {
                String body = sendRaw(c, "systeminfo");
                Map<String, String> map = parseKeyValueBlock(body);
                return switch (info) {
                    case HDD -> firstNonEmpty(map, "hdd", "hddinfo", "harddisk", "hard_drive");
                    case Platform -> firstNonEmpty(map, "platform", "platform_id", "board", "console");
                    case System -> firstNonEmpty(map, "system", "os", "name");
                    case BaseKrnlVersion -> firstNonEmpty(map, "baseversion", "basekrnlversion", "base_kernel");
                    case KrnlVersion -> firstNonEmpty(map, "krnlversion", "kernel", "kernelversion");
                    case XDKVersion -> firstNonEmpty(map, "xdkversion", "xdks", "xdk");
                    default -> body;
                };
            }
        }
    }

    private static Map<String, String> parseKeyValueBlock(String response) {
        Map<String, String> map = new LinkedHashMap<>();
        Pattern p = Pattern.compile("(?i)(?:^|\\s)([a-z0-9_\\-]+)\\s*[:=]\\s*([^\\r\\n]+)");
        for (String line : response.split("\\r?\\n")) {
            String cleaned = line.trim().replaceFirst("(?i)^200-\\s*", "");
            Matcher m = p.matcher(cleaned);
            if (m.find()) {
                map.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2).trim());
            }
        }
        return map;
    }

    private static String firstNonEmpty(Map<String, String> map, String... keys) {
        for (String k : keys) {
            String v = map.get(k);
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    public static void xNotify(JRPC.IXboxConsole c, String message, JRPC.XNotiyLogo logo) {
        JRPC.XNotify(c, logo, message);
    }

    public static String getSMCVersion(JRPC.IXboxConsole c) {
        byte[] b = JRPC.GetMemory(c, 0x81AC7C50L, 4);
        int x = (b.length > 2 ? b[2] & 0xFF : 0);
        int y = (b.length > 3 ? b[3] & 0xFF : 0);
        return x + "." + y;
    }

    public static void callXamOrdinalVoid(JRPC.IXboxConsole c, String module, int ordinal, Object... args) {
        JRPC.CallVoid(c, module, ordinal, args);
    }

    private static void sendExpectOk(JRPC.IXboxConsole c, String cmd) {
        String r = sendRaw(c, cmd);
        if (!r.startsWith("200")) {}
    }

    private static String strip200(String line) {
        if (line == null) return "";
        String s = line.trim();
        if (s.startsWith("200-")) s = s.substring(4).trim();
        return s;
    }
}
