package org.digit.notify.app.plugin;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.digit.notify.spi.Channel;
import org.digit.notify.spi.NotificationChannelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.StructuredTaskScope;

@Component
public class ProviderPluginLoader {

    private static final Logger log = LoggerFactory.getLogger(ProviderPluginLoader.class);

    private final Map<Channel, Map<String, NotificationChannelProvider>> registry =
        new ConcurrentHashMap<>();
    private final List<URLClassLoader> classLoaders = new CopyOnWriteArrayList<>();

    private final ProviderRegistrar providerRegistrar;
    private final Path pluginsDirectory;

    public ProviderPluginLoader(
        ProviderRegistrar providerRegistrar,
        @Value("${notify.plugins.directory:/providers}") String pluginsDir
    ) {
        this.providerRegistrar = providerRegistrar;
        this.pluginsDirectory = Path.of(pluginsDir);
        for (Channel channel : Channel.values()) {
            registry.put(channel, new ConcurrentHashMap<>());
        }
    }

    @PostConstruct
    public void loadPlugins() {
        if (!Files.exists(pluginsDirectory)) {
            log.warn("Plugins directory does not exist: {}", pluginsDirectory);
            return;
        }

        List<Path> jars;
        try (var stream = Files.list(pluginsDirectory)) {
            jars = stream
                .filter(p -> p.toString().endsWith(".jar"))
                .toList();
        } catch (IOException e) {
            log.error("Failed to list plugins directory: {}", pluginsDirectory, e);
            return;
        }

        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAll())) {
            jars.forEach(jar -> scope.fork(() -> {
                loadJar(jar);
                return null;
            }));
            scope.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Plugin loading interrupted");
        }

        logStartupSummary(jars.size());
    }

    private void loadJar(Path jarPath) {
        try {
            var classLoader = new URLClassLoader(
                new URL[]{ jarPath.toUri().toURL() },
                Thread.currentThread().getContextClassLoader()
            );
            classLoaders.add(classLoader);
            var serviceLoader = ServiceLoader.load(NotificationChannelProvider.class, classLoader);

            int count = 0;
            for (NotificationChannelProvider provider : serviceLoader) {
                providerRegistrar.upsertProvider(provider);
                registry.get(provider.supportedChannel()).put(provider.providerName(), provider);
                log.info("Registered provider: {} for channel: {}",
                    provider.providerName(), provider.supportedChannel());
                count++;
            }

            if (count == 0) {
                log.warn("No providers found in jar: {}", jarPath.getFileName());
            }
        } catch (Throwable t) {
            // Throwable, not Exception: ServiceLoader reports a provider whose constructor threw
            // as a ServiceConfigurationError, which is an Error. Catching only Exception let those
            // pass silently, so a provider missing its credentials just went absent from the
            // startup summary with nothing logged.
            log.error("Failed to load jar: {} — {}", jarPath.getFileName(), t.getMessage(), t);
        }
    }

    @PreDestroy
    public void closeClassLoaders() {
        classLoaders.forEach(cl -> {
            try { cl.close(); } catch (IOException e) {
                log.warn("Failed to close classloader", e);
            }
        });
    }

    public Optional<NotificationChannelProvider> getProvider(Channel channel, String providerName) {
        return Optional.ofNullable(registry.get(channel).get(providerName));
    }

    public List<NotificationChannelProvider> getProvidersOrdered(
        Channel channel, List<String> orderedNames
    ) {
        var channelMap = registry.get(channel);
        var result = new ArrayList<NotificationChannelProvider>();
        for (String name : orderedNames) {
            var provider = channelMap.get(name);
            if (provider != null) {
                result.add(provider);
            } else {
                log.warn("Provider '{}' listed in mapping for channel {} but not found in registry — skipping",
                    name, channel);
            }
        }
        return result;
    }

    private void logStartupSummary(int jarCount) {
        int total = registry.values().stream().mapToInt(Map::size).sum();
        log.info("Plugin loader summary: {} jars scanned, {} providers registered", jarCount, total);
        for (Channel channel : Channel.values()) {
            log.info("  {}: {}", String.format("%-8s", channel.name()),
                registry.get(channel).keySet());
        }
    }
}
