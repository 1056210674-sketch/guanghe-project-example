package com.guanghe.backend.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Resolves the client IP used by the login rate limiter.
 *
 * <p>Forwarding headers are trusted only when the direct TCP peer is loopback. The local Nginx
 * configuration must overwrite, rather than append, these headers. A direct client therefore
 * cannot choose its rate-limit bucket by supplying a forged forwarding header.</p>
 */
@Component
public class TrustedProxyClientIpResolver {

    private static final String FORWARDED = "Forwarded";
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String UNKNOWN = "unknown";
    private static final int MAX_HEADER_LENGTH = 4096;
    private static final int MAX_HEADER_PARTS = 32;
    private static final Pattern PARAMETER_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]*$");

    public String resolve(HttpServletRequest request) {
        Optional<String> peerAddress = normalizeIpLiteral(request.getRemoteAddr());
        if (peerAddress.isEmpty()) {
            return UNKNOWN;
        }

        String peerIp = peerAddress.get();
        if (!isLoopback(peerIp)) {
            return peerIp;
        }

        return forwardedClientIp(request, FORWARDED, true)
                .or(() -> forwardedClientIp(request, X_FORWARDED_FOR, false))
                .orElse(peerIp);
    }

    private Optional<String> forwardedClientIp(
            HttpServletRequest request,
            String headerName,
            boolean standardForwarded
    ) {
        Optional<String> combined = combineHeaderValues(request.getHeaders(headerName));
        if (combined.isEmpty()) {
            return Optional.empty();
        }
        return standardForwarded
                ? parseForwarded(combined.get())
                : parseXForwardedFor(combined.get());
    }

    private Optional<String> combineHeaderValues(Enumeration<String> values) {
        if (values == null || !values.hasMoreElements()) {
            return Optional.empty();
        }

        StringBuilder combined = new StringBuilder();
        int count = 0;
        while (values.hasMoreElements()) {
            String value = values.nextElement();
            if (++count > MAX_HEADER_PARTS || unsafeHeaderValue(value)) {
                return Optional.empty();
            }
            if (!combined.isEmpty()) {
                combined.append(',');
            }
            combined.append(value);
            if (combined.length() > MAX_HEADER_LENGTH) {
                return Optional.empty();
            }
        }
        return Optional.of(combined.toString());
    }

    private boolean unsafeHeaderValue(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_HEADER_LENGTH) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private Optional<String> parseForwarded(String value) {
        Optional<List<String>> elements = splitOutsideQuotes(value, ',');
        if (elements.isEmpty()) {
            return Optional.empty();
        }

        Optional<List<String>> parameters = splitOutsideQuotes(elements.get().getLast(), ';');
        if (parameters.isEmpty()) {
            return Optional.empty();
        }
        String nearestFor = null;
        for (String parameter : parameters.get()) {
            int separator = parameter.indexOf('=');
            if (separator <= 0 || separator != parameter.lastIndexOf('=')) {
                return Optional.empty();
            }
            String name = parameter.substring(0, separator).trim();
            String parameterValue = parameter.substring(separator + 1).trim();
            if (!PARAMETER_NAME.matcher(name).matches() || parameterValue.isEmpty()) {
                return Optional.empty();
            }
            if ("for".equalsIgnoreCase(name)) {
                if (nearestFor != null) {
                    return Optional.empty();
                }
                nearestFor = parameterValue;
            }
        }
        return nearestFor == null ? Optional.empty() : parseNodeIdentifier(nearestFor);
    }

    private Optional<String> parseXForwardedFor(String value) {
        Optional<List<String>> entries = splitOutsideQuotes(value, ',');
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        return parseNodeIdentifier(entries.get().getLast());
    }

    private Optional<List<String>> splitOutsideQuotes(String value, char delimiter) {
        List<String> parts = new ArrayList<>();
        boolean quoted = false;
        int start = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\') {
                // Nginx emits literal IPs; rejecting quoted escapes avoids ambiguous decoding.
                return Optional.empty();
            }
            if (character == '"') {
                quoted = !quoted;
            } else if (character == delimiter && !quoted) {
                if (!addPart(parts, value.substring(start, index))) {
                    return Optional.empty();
                }
                start = index + 1;
            }
        }
        if (quoted || !addPart(parts, value.substring(start))) {
            return Optional.empty();
        }
        return Optional.of(parts);
    }

    private boolean addPart(List<String> parts, String candidate) {
        if (parts.size() >= MAX_HEADER_PARTS) {
            return false;
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        parts.add(trimmed);
        return true;
    }

    private Optional<String> parseNodeIdentifier(String rawValue) {
        String value = unwrapQuotes(rawValue.trim());
        if (value == null || value.isBlank()
                || "unknown".equalsIgnoreCase(value)
                || value.startsWith("_")) {
            return Optional.empty();
        }

        if (value.startsWith("[")) {
            int closingBracket = value.indexOf(']');
            if (closingBracket <= 1 || !validOptionalPort(value.substring(closingBracket + 1))) {
                return Optional.empty();
            }
            return normalizeIpLiteral(value.substring(1, closingBracket));
        }

        Optional<String> literal = normalizeIpLiteral(value);
        if (literal.isPresent()) {
            return literal;
        }

        int firstColon = value.indexOf(':');
        if (firstColon > 0 && firstColon == value.lastIndexOf(':')
                && validOptionalPort(value.substring(firstColon))) {
            return normalizeIpv4(value.substring(0, firstColon));
        }
        return Optional.empty();
    }

    private String unwrapQuotes(String value) {
        if (!value.startsWith("\"") && !value.endsWith("\"")) {
            return value;
        }
        if (value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"")) {
            return null;
        }
        String unwrapped = value.substring(1, value.length() - 1);
        return unwrapped.indexOf('"') >= 0 ? null : unwrapped;
    }

    private boolean validOptionalPort(String suffix) {
        if (suffix.isEmpty()) {
            return true;
        }
        if (suffix.charAt(0) != ':' || suffix.length() == 1 || suffix.length() > 6) {
            return false;
        }
        for (int index = 1; index < suffix.length(); index++) {
            char character = suffix.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        try {
            int port = Integer.parseInt(suffix.substring(1));
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private Optional<String> normalizeIpLiteral(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }
        String value = candidate.trim();
        Optional<String> ipv4 = normalizeIpv4(value);
        if (ipv4.isPresent()) {
            return ipv4;
        }
        if (!value.contains(":") || value.contains("%")
                || !value.matches("^[0-9A-Fa-f:.]+$")) {
            return Optional.empty();
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            if (!(address instanceof Inet6Address)) {
                // IPv4-mapped IPv6 literals are canonicalized by the JDK to IPv4.
                return normalizeIpv4(address.getHostAddress());
            }
            return Optional.of(address.getHostAddress().toLowerCase(Locale.ROOT));
        } catch (UnknownHostException exception) {
            return Optional.empty();
        }
    }

    private Optional<String> normalizeIpv4(String candidate) {
        String[] octets = candidate.split("\\.", -1);
        if (octets.length != 4) {
            return Optional.empty();
        }
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < octets.length; index++) {
            String octet = octets[index];
            if (octet.isEmpty() || octet.length() > 3
                    || (octet.length() > 1 && octet.charAt(0) == '0')) {
                return Optional.empty();
            }
            for (int charIndex = 0; charIndex < octet.length(); charIndex++) {
                char character = octet.charAt(charIndex);
                if (character < '0' || character > '9') {
                    return Optional.empty();
                }
            }
            int numeric = Integer.parseInt(octet);
            if (numeric > 255) {
                return Optional.empty();
            }
            if (index > 0) {
                normalized.append('.');
            }
            normalized.append(numeric);
        }
        return Optional.of(normalized.toString());
    }

    private boolean isLoopback(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
