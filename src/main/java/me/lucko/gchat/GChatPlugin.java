/*
 * This file is part of gChat, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.gchat;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import lombok.Getter;
import lombok.NonNull;
import me.lucko.gchat.api.ChatFormat;
import me.lucko.gchat.api.GChatApi;
import me.lucko.gchat.api.Placeholder;
import me.lucko.gchat.config.GChatConfig;
import me.lucko.gchat.hooks.LuckPermsHook;
import me.lucko.gchat.placeholder.StandardPlaceholders;
import net.kyori.adventure.serializer.configurate3.ConfigurateComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.ConfigurationOptions;
import ninja.leaping.configurate.objectmapping.serialize.TypeSerializerCollection;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Plugin(
        id = "gchat-velocity",
        name = "gChat for Velocity",
        authors = {"Luck", "md678685"},
        version = "VERSION", // filled in during build
        dependencies = {
                @Dependency(id = "luckperms", optional = true),
                @Dependency(id = "neutron-n3fs", optional = true)
        }
)
public class GChatPlugin implements GChatApi {
    public static final LegacyComponentSerializer LEGACY_LINKING_SERIALIZER = LegacyComponentSerializer.builder()
            .character('&')
            .extractUrls()
            .build();
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([^{}]+)}");
    private final Set<Placeholder> placeholders = ConcurrentHashMap.newKeySet();
    @Inject
    private ProxyServer proxy;
    @Inject
    @Getter
    private Logger logger;
    @Inject
    @DataDirectory
    private Path dataDirectory;
    @Getter
    private GChatConfig config;

    @Subscribe
    public void onEnable(ProxyInitializeEvent event) {
        logger.info("Enabling gChat v" + getDescription().getVersion().get());

        // load configuration
        try {
            this.config = loadConfig();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config", e);
        }

        // init placeholder hooks
        placeholders.add(new StandardPlaceholders());

        // hook with luckperms
        if (proxy.getPluginManager().getPlugin("luckperms").isPresent()) {
            placeholders.add(new LuckPermsHook());
        }

        // register chat listener
        proxy.getEventManager().register(this, new GChatListener(this));

        // register command
        proxy.getCommandManager().register("gchat", new GChatCommand(this), "globalchat");

        // init api singleton
        GChat.setApi(this);
    }

    @Subscribe
    public void onDisable(ProxyShutdownEvent event) {
        // null the api singleton
        GChat.setApi(null);
    }

    @Override
    public boolean registerPlaceholder(@NonNull Placeholder placeholder) {
        return placeholders.add(placeholder);
    }

    @Override
    public boolean unregisterPlaceholder(@NonNull Placeholder placeholder) {
        return placeholders.remove(placeholder);
    }

    @Override
    public ImmutableSet<Placeholder> getPlaceholders() {
        return ImmutableSet.copyOf(placeholders);
    }

    @Override
    public List<ChatFormat> getFormats() {
        return config.getFormats();
    }

    @Override
    public String replacePlaceholders(Player player, String text) {
        if (text == null || text.isEmpty() || placeholders.isEmpty()) {
            return text;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) {
            String definition = matcher.group(1);
            String replacement = null;

            for (Placeholder placeholder : placeholders) {
                replacement = placeholder.getReplacement(player, definition);
                if (replacement != null) {
                    break;
                }
            }

            if (replacement != null) {
                text = text.replace("{" + definition + "}", replacement);
            }
        }

        return text;
    }

    @Override
    public Optional<ChatFormat> getFormat(Player player) {
        return config.getFormats().stream()
                .filter(f -> f.canUse(player))
                .findFirst();
    }

    @Subscribe
   public void onReload(ProxyReloadEvent event) { // The Velocity api requies this to be Void.
        reloadConfig();
    }


    public boolean reloadConfig() {
      try {
            config = loadConfig();
           return true;
        } catch (Exception e) {
           e.printStackTrace();
           return false;
       }
    }

    private GChatConfig loadConfig() throws Exception {
        TypeSerializerCollection serializerCollection = TypeSerializerCollection.create();
        ConfigurateComponentSerializer.configurate().addSerializersTo(serializerCollection);

        ConfigurationOptions options = ConfigurationOptions.defaults()
                .withSerializers(serializerCollection);

        ConfigurationNode config = YAMLConfigurationLoader.builder()
                .setDefaultOptions(options)
                .setFile(getBundledFile())
                .build()
                .load();
        return new GChatConfig(config);
    }

    private File getBundledFile() {
        File file = new File(dataDirectory.toFile(), "config.yml");

        if (!file.exists()) {
            dataDirectory.toFile().mkdir();
            try (InputStream in = GChatPlugin.class.getResourceAsStream("/" + "config.yml")) {
                assert in != null;
                Files.copy(in, file.toPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return file;
    }

    ProxyServer getProxy() {
        return proxy;
    }

    PluginDescription getDescription() {
        return proxy.getPluginManager().getPlugin("gchat-velocity").get().getDescription();
    }
}
