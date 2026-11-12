package ca.yorku.eecs3311.huntassist.evidence;

import java.util.List;

/** Strategy for translating a capture format into transient, passive metadata. */
interface TrafficImporter {
    int MAX_ENTRIES = 20_000;

    boolean supports(int firstByte);

    List<CapturedExchange> parse(byte[] source);

    record CapturedExchange(String url, String method, int status, String contentType, HeaderPresence headers) {}
}
