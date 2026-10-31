package com.wargame.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** 限流与审计共用来源地址；不解析主机名，也不信任客户端自行填写的转发头。 */
@Component
public class ClientIpResolver {
    private final List<IpAddressMatcher> trustedProxies;

    public ClientIpResolver(@Value("${game.security.trusted-proxies:}") String configuredProxies) {
        List<IpAddressMatcher> proxies = new ArrayList<>();
        for (String entry : configuredProxies.split(",", -1)) {
            if (entry.isBlank()) continue;
            String[] parts = entry.trim().split("/", -1);
            String address = normalize(parts[0]);
            if (parts.length > 2 || address == null) throw invalidProxy();
            int bits = address.contains(":") ? 128 : 32;
            if (parts.length == 2) {
                if (!parts[1].matches("[0-9]{1,3}")) throw invalidProxy();
                bits = Integer.parseInt(parts[1]);
                // 禁止误配成全网可信，代理网段必须由部署者明确收敛。
                if (bits < 1 || bits > (address.contains(":") ? 128 : 32)) throw invalidProxy();
            }
            proxies.add(new IpAddressMatcher(address + "/" + bits));
        }
        trustedProxies = List.copyOf(proxies);
    }

    /** 只越过受信任的相邻代理；遇到第一跳不可信地址即停止，忽略它左侧可伪造的地址。 */
    public String resolve(HttpServletRequest request) {
        if (request == null) return "unknown";
        String remote = normalize(request.getRemoteAddr());
        if (remote == null) return "unknown";
        if (!trusted(remote)) return remote;
        String forwarded = singleHeader(request, "X-Forwarded-For");
        if (forwarded == null) {
            // 兼容仅覆盖 X-Real-IP 的既有网关；畸形或重复的 XFF 不降级为另一种口径。
            if (request.getHeader("X-Forwarded-For") != null) return remote;
            String realIp = normalize(singleHeader(request, "X-Real-IP"));
            return realIp == null ? remote : realIp;
        }
        if (forwarded.length() > 2048) return remote;
        String[] hops = forwarded.split(",", -1);
        if (hops.length > 16) return remote;
        List<String> addresses = new ArrayList<>();
        for (String hop : hops) {
            String address = normalize(hop.trim());
            if (address == null) return remote;
            addresses.add(address);
        }
        String client = remote;
        for (int i = addresses.size() - 1; i >= 0 && trusted(client); i--) client = addresses.get(i);
        return client;
    }

    private boolean trusted(String address) {
        return trustedProxies.stream().anyMatch(proxy -> proxy.matches(address));
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        return values.hasMoreElements() ? null : value;
    }

    /** 先限定数字字面量再调用 InetAddress，避免 DNS 查询和同一 IP 的不同写法绕过配额。 */
    private static String normalize(String input) {
        if (input == null || input.isBlank() || input.length() > 45) return null;
        if (input.contains(":")) {
            if (!input.matches("[0-9a-fA-F:.]+")) return null;
        } else {
            String[] octets = input.split("\\.", -1);
            if (octets.length != 4) return null;
            for (String octet : octets) {
                if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) return null;
            }
        }
        try { return InetAddress.getByName(input).getHostAddress(); }
        catch (UnknownHostException e) { return null; }
    }

    private static IllegalArgumentException invalidProxy() {
        return new IllegalArgumentException("game.security.trusted-proxies 必须是受控代理的 IP/CIDR，不能使用主机名或 /0");
    }
}
