package ca.yorku.eecs3311.huntassist.evidence;

import ca.yorku.eecs3311.huntassist.application.AppException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

@Component
final class BurpXmlTrafficImporter implements TrafficImporter {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_HEADER_CHARS = 32_768;
    private static final int MAX_BASE64_PREFIX = 4 * ((MAX_HEADER_CHARS + 2) / 3);
    private static final Pattern UNSAFE_DTD = Pattern.compile("\\b(?:SYSTEM|PUBLIC|ENTITY)\\b");

    @Override
    public boolean supports(int firstByte) {
        return firstByte == '<';
    }

    @Override
    public List<CapturedExchange> parse(byte[] source) {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        // Burp may emit an internal element grammar. Disable all DTD processing and external resolution.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External XML resources are disabled.");
        });

        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(source));
            try {
                return readDocument(reader);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw AppException.invalid("Invalid or unsupported Burp XML capture.");
        }
    }

    private List<CapturedExchange> readDocument(XMLStreamReader reader) throws XMLStreamException {
        List<CapturedExchange> entries = new ArrayList<>();
        boolean seenRoot = false;
        int depth = 0;
        while (reader.hasNext()) {
            int event = reader.next();
            rejectEntity(event);
            if (event == XMLStreamConstants.DTD && UNSAFE_DTD.matcher(reader.getText()).find()) {
                throw AppException.invalid("External and custom XML entities are disabled.");
            }
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                if (depth == 1) {
                    if (seenRoot || !reader.getLocalName().equals("items")) {
                        throw AppException.invalid("Expected a Burp XML items document.");
                    }
                    seenRoot = true;
                } else if (depth == 2 && reader.getLocalName().equals("item")) {
                    if (entries.size() == MAX_ENTRIES) {
                        throw AppException.invalid("Capture exceeds the 20,000-exchange limit.");
                    }
                    entries.add(readItem(reader));
                    depth--;
                } else if (depth > MAX_DEPTH) {
                    throw AppException.invalid("Burp XML exceeds the nesting limit.");
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        if (!seenRoot) {
            throw AppException.invalid("Expected a Burp XML items document.");
        }
        return entries;
    }

    private CapturedExchange readItem(XMLStreamReader reader) throws XMLStreamException {
        String url = null;
        String method = null;
        String mimeType = null;
        int status = -1;
        HeaderPresence headers = new HeaderPresence();
        while (reader.hasNext()) {
            int event = reader.next();
            rejectEntity(event);
            if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            }
            if (event != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            switch (reader.getLocalName()) {
                case "url" -> url = reader.getElementText();
                case "method" -> method = reader.getElementText();
                case "status" -> status = parseStatus(reader.getElementText());
                case "mimetype" -> mimeType = reader.getElementText();
                case "response" -> {
                    boolean base64 = "true".equalsIgnoreCase(reader.getAttributeValue(null, "base64"));
                    headers = readHeaders(reader.getElementText(), base64);
                }
                default -> skipElement(reader);
            }
        }
        return new CapturedExchange(url, method, status, headers.mediaTypeOr(mimeType), headers);
    }

    private void skipElement(XMLStreamReader reader) throws XMLStreamException {
        int depth = 1;
        while (depth > 0 && reader.hasNext()) {
            int event = reader.next();
            rejectEntity(event);
            if (event == XMLStreamConstants.START_ELEMENT && ++depth > MAX_DEPTH) {
                throw AppException.invalid("Burp XML exceeds the nesting limit.");
            }
            if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    private static void rejectEntity(int event) {
        if (event == XMLStreamConstants.ENTITY_REFERENCE) {
            throw AppException.invalid("Custom XML entities are disabled.");
        }
    }

    private static int parseStatus(String text) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private HeaderPresence readHeaders(String response, boolean base64) {
        HeaderPresence result = new HeaderPresence();
        String prefix = base64 ? decodedPrefix(response) : response.substring(0, Math.min(MAX_HEADER_CHARS, response.length()));
        String normalized = prefix.replace("\r\n", "\n");
        int headerEnd = normalized.indexOf("\n\n");
        int firstLine = normalized.indexOf('\n');
        if (headerEnd < 0 || firstLine < 0 || !normalized.startsWith("HTTP/")) {
            return result;
        }
        if (firstLine >= headerEnd) {
            return result;
        }
        for (String header : normalized.substring(firstLine + 1, headerEnd).split("\n")) {
            int separator = header.indexOf(':');
            if (separator > 0) {
                result.accept(header.substring(0, separator), header.substring(separator + 1));
            }
        }
        return result;
    }

    private String decodedPrefix(String encoded) {
        StringBuilder prefix = new StringBuilder(Math.min(encoded.length(), MAX_BASE64_PREFIX));
        for (int i = 0; i < encoded.length() && prefix.length() < MAX_BASE64_PREFIX; i++) {
            char c = encoded.charAt(i);
            if (c != '\n' && c != '\r' && c != ' ' && c != '\t') {
                prefix.append(c);
            }
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(prefix.toString());
            return new String(decoded, 0, Math.min(decoded.length, MAX_HEADER_CHARS), StandardCharsets.ISO_8859_1);
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }
}
