package com.arena.alliance.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.transport.ProxyProvider;

import java.net.URI;
import java.time.Duration;

/**
 * 游戏侧网络资源：全部成员连接共享同一组 Netty EventLoop 与 HTTP 连接池。
 * WS 长连接与指令 POST 都从 baseHttpClient 派生（Authorization 每连接单独设置，
 * HttpClient 不可变，derive 出的实例共享底层资源）。
 *
 * oauthHttpClient 独立成 bean：访问 connect.linux.do 在部分网络环境需要走代理
 * （OAUTH_PROXY），与游戏低延迟直连通道隔离；游戏侧也可用 GAME_PROXY 配置代理。
 */
@Configuration
public class NettyConfig {

    @Bean(destroyMethod = "dispose")
    public LoopResources gameLoopResources() {
        int workers = Math.max(4, Runtime.getRuntime().availableProcessors());
        return LoopResources.create("arena-game", workers, true);
    }

    @Bean(destroyMethod = "dispose")
    public ConnectionProvider gameConnectionProvider() {
        return ConnectionProvider.builder("arena-cmd")
                .maxConnections(256)
                .maxIdleTime(Duration.ofSeconds(55))
                .pendingAcquireTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Bean
    @Primary
    public HttpClient baseHttpClient(LoopResources gameLoopResources,
                                     ConnectionProvider gameConnectionProvider,
                                     AllianceProperties props) {
        HttpClient client = HttpClient.create(gameConnectionProvider)
                .runOn(gameLoopResources)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 8000)
                .responseTimeout(Duration.ofSeconds(10));
        return applyProxy(client, props.game().proxy());
    }

    @Bean
    public HttpClient oauthHttpClient(LoopResources gameLoopResources, AllianceProperties props) {
        HttpClient client = HttpClient.create()
                .runOn(gameLoopResources)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 15000)
                .responseTimeout(Duration.ofSeconds(30));
        return applyProxy(client, props.linuxdo().proxy());
    }

    /** 支持 http://host:port 与 socks5://host:port 两种代理写法 */
    static HttpClient applyProxy(HttpClient client, String proxyUrl) {
        if (proxyUrl == null || proxyUrl.isBlank()) {
            return client;
        }
        URI uri = URI.create(proxyUrl.trim());
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase();
        ProxyProvider.Proxy type = switch (scheme) {
            case "socks5" -> ProxyProvider.Proxy.SOCKS5;
            case "socks4" -> ProxyProvider.Proxy.SOCKS4;
            default -> ProxyProvider.Proxy.HTTP;
        };
        int port = uri.getPort() > 0 ? uri.getPort() : (type == ProxyProvider.Proxy.HTTP ? 80 : 1080);
        String host = uri.getHost();
        return client.proxy(spec -> spec.type(type).host(host).port(port));
    }
}
