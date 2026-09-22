/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.util.internal;


import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;


public final class WrapperCredentials {
    
    private final String token;

    
    private final String basicUserInfo;

    private WrapperCredentials(String token, String basicUserInfo) {
        this.token = token;
        this.basicUserInfo = basicUserInfo;
    }

    public static WrapperCredentials fromToken(String token) {
        return new WrapperCredentials(Objects.requireNonNull(token, "token"), null);
    }

    public static WrapperCredentials fromBasicUserInfo(String basicUserInfo) {
        return new WrapperCredentials(null, Objects.requireNonNull(basicUserInfo, "basicUserInfo"));
    }

    public static WrapperCredentials fromUsernamePassword(String username, String password) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        return fromBasicUserInfo(username + ':' + password);
    }

    
    public static WrapperCredentials findCredentials(
        URI distributionUrl,
        Function<? super String, ? extends String> propertyProvider
    ) {
        Objects.requireNonNull(distributionUrl, "distributionUrl");
        Objects.requireNonNull(propertyProvider, "propertyProvider");

        String token = tryGetProperty(distributionUrl.getHost(), "wrapperToken", propertyProvider);
        if (token != null) {
            return fromToken(token);
        }
        return findBasicCredentials(distributionUrl, propertyProvider);
    }

    
    private static WrapperCredentials findBasicCredentials(
        URI distributionUrl,
        Function<? super String, ? extends String> propertyProvider
    ) {
        String host = distributionUrl.getHost();
        String username = tryGetProperty(host, "wrapperUser", propertyProvider);
        String password = tryGetProperty(host, "wrapperPassword", propertyProvider);
        if (username != null && password != null) {
            return fromUsernamePassword(username, password);
        }

        String userInfo = distributionUrl.getUserInfo();
        return userInfo != null ? fromBasicUserInfo(userInfo) : null;
    }

    
    private static String tryGetProperty(
        String host,
        String key,
        Function<? super String, ? extends String> propertyProvider
    ) {
        if (host != null) {
            String hostEscaped = host.replace('.', '_').toLowerCase(Locale.ROOT);
            String hostProperty = propertyProvider.apply("gradle." + hostEscaped + '.' + key);
            if (hostProperty != null) {
                return hostProperty;
            }
        }
        return propertyProvider.apply("gradle." + key);
    }

    
    public String token() {
        return token;
    }

    private static Map.Entry<String, String> mapEntry(String key, String value) {
        return new AbstractMap.SimpleImmutableEntry<>(key, value);
    }

    public Map.Entry<String, String> usernameAndPassword() {
        if (basicUserInfo == null) {
            return null;
        }

        int usernameEnd = basicUserInfo.indexOf(':');
        return usernameEnd >= 0
            ? mapEntry(basicUserInfo.substring(0, usernameEnd), basicUserInfo.substring(usernameEnd + 1))
            : null;
    }

    
    public String username() {
        Map.Entry<String, String> combined = usernameAndPassword();
        return combined != null ? combined.getKey() : null;
    }

    public String authorizationTypeDisplayName() {
        return token != null
            ? "Bearer Token"
            : "Basic";
    }

    public Map.Entry<String, String> authorizationHeader() {
        return mapEntry("Authorization", authorizationHeaderValue());
    }

    private String authorizationHeaderValue() {
        if (token != null) {
            return "Bearer " + token;
        } else if (basicUserInfo != null) {
            return "Basic " + Base64.getEncoder().encodeToString(basicUserInfo.getBytes(StandardCharsets.UTF_8));
        } else {
            throw new AssertionError("Internal error: Unexpected credentials state.");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        WrapperCredentials that = (WrapperCredentials) o;
        return Objects.equals(token, that.token)
            && Objects.equals(basicUserInfo, that.basicUserInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(token, basicUserInfo);
    }

    @Override
    public String toString() {
        return "WrapperCredentials{"
            + (token != null ? "<TOKEN>" : "password for " + username())
            + '}';
    }
}
