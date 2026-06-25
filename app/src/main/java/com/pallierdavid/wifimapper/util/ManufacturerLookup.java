package com.pallierdavid.wifimapper.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves WiFi access point manufacturers from BSSID OUI prefixes.
 * Covers the most common router/AP vendors found in residential/commercial deployments.
 */
public final class ManufacturerLookup {

    private ManufacturerLookup() {}

    private static final Map<String, String> OUI = new HashMap<>();

    static {
        // Cisco / Linksys
        OUI.put("00:00:0C", "Cisco");
        OUI.put("00:1A:A9", "Cisco");
        OUI.put("58:BC:27", "Cisco");
        OUI.put("A8:9D:21", "Cisco");
        OUI.put("00:14:BF", "Linksys");
        OUI.put("00:18:39", "Linksys");
        OUI.put("00:23:69", "Linksys");
        OUI.put("C4:41:1E", "Linksys");

        // TP-Link
        OUI.put("50:C7:BF", "TP-Link");
        OUI.put("10:27:F5", "TP-Link");
        OUI.put("14:CC:20", "TP-Link");
        OUI.put("18:D6:C7", "TP-Link");
        OUI.put("54:A7:03", "TP-Link");
        OUI.put("60:32:B1", "TP-Link");
        OUI.put("6C:5A:B5", "TP-Link");
        OUI.put("8C:21:0A", "TP-Link");
        OUI.put("90:F6:52", "TP-Link");
        OUI.put("B0:BE:76", "TP-Link");
        OUI.put("D8:07:B6", "TP-Link");
        OUI.put("E8:94:F6", "TP-Link");
        OUI.put("EC:08:6B", "TP-Link");
        OUI.put("F4:F2:6D", "TP-Link");

        // Netgear
        OUI.put("00:14:6C", "Netgear");
        OUI.put("00:1E:2A", "Netgear");
        OUI.put("20:4E:7F", "Netgear");
        OUI.put("2C:B0:5D", "Netgear");
        OUI.put("30:46:9A", "Netgear");
        OUI.put("44:94:FC", "Netgear");
        OUI.put("A0:21:B7", "Netgear");
        OUI.put("C0:3F:0E", "Netgear");
        OUI.put("E0:91:F5", "Netgear");

        // ASUS
        OUI.put("00:0C:6E", "Asus");
        OUI.put("00:1D:60", "Asus");
        OUI.put("04:D9:F5", "Asus");
        OUI.put("10:BF:48", "Asus");
        OUI.put("14:DA:E9", "Asus");
        OUI.put("2C:56:DC", "Asus");
        OUI.put("30:5A:3A", "Asus");
        OUI.put("38:2C:4A", "Asus");
        OUI.put("40:16:7E", "Asus");
        OUI.put("50:46:5D", "Asus");
        OUI.put("6C:72:20", "Asus");
        OUI.put("74:D0:2B", "Asus");
        OUI.put("AC:9E:17", "Asus");
        OUI.put("BC:EE:7B", "Asus");

        // D-Link
        OUI.put("00:15:E9", "D-Link");
        OUI.put("00:1C:F0", "D-Link");
        OUI.put("1C:7E:E5", "D-Link");
        OUI.put("28:10:7B", "D-Link");
        OUI.put("34:08:04", "D-Link");
        OUI.put("84:C9:B2", "D-Link");
        OUI.put("C8:BE:19", "D-Link");
        OUI.put("F0:7D:68", "D-Link");

        // Huawei
        OUI.put("00:46:4B", "Huawei");
        OUI.put("04:BD:70", "Huawei");
        OUI.put("10:1B:54", "Huawei");
        OUI.put("28:6E:D4", "Huawei");
        OUI.put("34:6B:D3", "Huawei");
        OUI.put("40:4D:8E", "Huawei");
        OUI.put("48:00:31", "Huawei");
        OUI.put("54:51:1B", "Huawei");
        OUI.put("5C:C3:07", "Huawei");
        OUI.put("70:72:CF", "Huawei");
        OUI.put("74:A0:63", "Huawei");
        OUI.put("9C:74:1A", "Huawei");
        OUI.put("B4:CD:27", "Huawei");
        OUI.put("C8:51:95", "Huawei");
        OUI.put("D4:6E:5C", "Huawei");
        OUI.put("E0:19:54", "Huawei");
        OUI.put("F4:CB:52", "Huawei");

        // Xiaomi
        OUI.put("00:9E:C8", "Xiaomi");
        OUI.put("08:21:EF", "Xiaomi");
        OUI.put("28:6C:07", "Xiaomi");
        OUI.put("34:CE:00", "Xiaomi");
        OUI.put("50:8F:4C", "Xiaomi");
        OUI.put("58:44:98", "Xiaomi");
        OUI.put("64:09:80", "Xiaomi");
        OUI.put("78:02:F8", "Xiaomi");
        OUI.put("8C:BE:BE", "Xiaomi");
        OUI.put("AC:F7:F3", "Xiaomi");
        OUI.put("B0:E2:35", "Xiaomi");
        OUI.put("D4:97:0B", "Xiaomi");
        OUI.put("F0:B4:29", "Xiaomi");
        OUI.put("FC:64:BA", "Xiaomi");

        // Apple AirPort
        OUI.put("00:0A:27", "Apple");
        OUI.put("00:17:F2", "Apple");
        OUI.put("00:1F:F3", "Apple");
        OUI.put("10:40:F3", "Apple");
        OUI.put("18:65:90", "Apple AirPort");
        OUI.put("34:15:9E", "Apple AirPort");
        OUI.put("68:5B:35", "Apple AirPort");
        OUI.put("A8:66:7F", "Apple AirPort");
        OUI.put("D8:D1:CB", "Apple");

        // Ubiquiti
        OUI.put("00:15:6D", "Ubiquiti");
        OUI.put("00:27:22", "Ubiquiti");
        OUI.put("04:18:D6", "Ubiquiti");
        OUI.put("24:A4:3C", "Ubiquiti");
        OUI.put("44:D9:E7", "Ubiquiti");
        OUI.put("68:72:51", "Ubiquiti");
        OUI.put("78:8A:20", "Ubiquiti");
        OUI.put("80:2A:A8", "Ubiquiti");
        OUI.put("B4:FB:E4", "Ubiquiti");
        OUI.put("DC:9F:DB", "Ubiquiti");
        OUI.put("F0:9F:C2", "Ubiquiti");
        OUI.put("FC:EC:DA", "Ubiquiti");

        // Aruba / HPE
        OUI.put("00:0B:86", "Aruba");
        OUI.put("00:1A:1E", "Aruba");
        OUI.put("20:4C:03", "Aruba");
        OUI.put("24:DE:C6", "Aruba");
        OUI.put("40:E3:D6", "Aruba");
        OUI.put("70:3A:0E", "Aruba");
        OUI.put("84:D4:7E", "Aruba");
        OUI.put("AC:A3:1E", "Aruba");
        OUI.put("D8:C7:C8", "Aruba");

        // Freebox (Free / Iliad)
        OUI.put("14:0C:76", "Freebox");
        OUI.put("68:A3:78", "Freebox");
        OUI.put("F4:CA:E5", "Freebox");
        OUI.put("78:CD:8E", "Freebox");

        // Livebox (Orange)
        OUI.put("00:0F:79", "Livebox");
        OUI.put("00:1E:8C", "Livebox");
        OUI.put("E4:2A:AC", "Livebox");
        OUI.put("A8:9D:21", "Livebox");

        // SFR Box
        OUI.put("00:12:FB", "SFR Box");
        OUI.put("00:24:D4", "SFR Box");
        OUI.put("E4:91:26", "SFR Box");
        OUI.put("00:26:5A", "SFR Box");

        // BBox (Bouygues)
        OUI.put("00:24:D4", "Bbox");
        OUI.put("0C:8D:DB", "Bbox");
        OUI.put("F0:81:75", "Bbox");

        // Sagemcom
        OUI.put("00:14:7F", "Sagemcom");
        OUI.put("00:1E:42", "Sagemcom");
        OUI.put("10:62:EB", "Sagemcom");
        OUI.put("44:FE:3B", "Sagemcom");
        OUI.put("A8:9D:21", "Sagemcom");
        OUI.put("BC:76:70", "Sagemcom");

        // Fritz!Box (AVM)
        OUI.put("AC:37:43", "Fritz!Box");
        OUI.put("C4:27:95", "Fritz!Box");
        OUI.put("DC:39:6F", "Fritz!Box");
        OUI.put("E0:28:6D", "Fritz!Box");

        // Zyxel
        OUI.put("00:13:49", "Zyxel");
        OUI.put("00:A0:C5", "Zyxel");
        OUI.put("1C:74:0D", "Zyxel");
        OUI.put("4C:9E:FF", "Zyxel");
        OUI.put("70:4F:57", "Zyxel");
        OUI.put("B4:B0:24", "Zyxel");
        OUI.put("C8:6C:87", "Zyxel");

        // Tenda
        OUI.put("00:26:5A", "Tenda");
        OUI.put("C8:3A:35", "Tenda");
        OUI.put("D4:76:EA", "Tenda");

        // Mercusys
        OUI.put("94:D9:B3", "Mercusys");

        // Google / Nest
        OUI.put("F4:F5:D8", "Google");
        OUI.put("54:60:09", "Google");
        OUI.put("3C:28:6D", "Google Nest");
        OUI.put("A4:77:33", "Google Nest");

        // Amazon Eero
        OUI.put("F0:27:65", "Amazon Eero");
        OUI.put("44:65:0D", "Amazon Eero");

        // Belkin
        OUI.put("00:17:3F", "Belkin");
        OUI.put("00:30:BD", "Belkin");
        OUI.put("08:86:3B", "Belkin");
        OUI.put("94:44:52", "Belkin");
        OUI.put("EC:1A:59", "Belkin");
    }

    /**
     * Returns the manufacturer name for the given BSSID, or null if unknown.
     * BSSID format: "AA:BB:CC:DD:EE:FF" (case-insensitive).
     */
    public static String lookup(String bssid) {
        if (bssid == null || bssid.length() < 8) return null;
        String oui = bssid.substring(0, 8).toUpperCase();
        return OUI.get(oui);
    }

    /** Returns the manufacturer or "Unknown" if not found. */
    public static String lookupOrUnknown(String bssid) {
        String result = lookup(bssid);
        return result != null ? result : "Unknown";
    }
}
