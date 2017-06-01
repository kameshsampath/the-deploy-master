/**
 * JBoss, Home of Professional Open Source
 * Copyright 2016, Red Hat, Inc. and/or its affiliates, and individual
 * contributors by the @authors tag. See the copyright.txt in the
 * distribution for a full listing of individual contributors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.redhat.developers.helloworld;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.core.spi.cluster.ClusterManager;
import io.vertx.ext.cluster.infinispan.InfinispanClusterManager;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.CorsHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.text.StrSubstitutor;
import org.infinispan.Cache;
import org.infinispan.manager.DefaultCacheManager;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class HelloworldVerticle extends AbstractVerticle {

    private static final String version = "1.0";
    private static final String CACHE_NAME = "helloWorld";

    private final String hostname = System.getenv().getOrDefault("HOSTNAME", "unknown");

    @Override
    public void start(Future<Void> fut) throws Exception {

        //Create Cache Manager - this needs to be blocking as there no way now to execute it async
        vertx.executeBlocking(future -> {
            try {
                DefaultCacheManager defaultCacheManager = new DefaultCacheManager("infinispan-vertx.xml");
                future.complete(defaultCacheManager);
            } catch (IOException e) {
                log.error("Error configuring Infinispan Cache Manager", e);
                future.fail(e);
            }
        }, asyncResult -> {

            if (asyncResult.succeeded()) {

                log.info("Configured Infinispan as vert.x Cluster Manager");

                final DefaultCacheManager defaultCacheManager = (DefaultCacheManager) asyncResult.result();

                //Setting up cluster manager with infinispan
                ClusterManager clusterManager = new InfinispanClusterManager(defaultCacheManager);

                VertxOptions vertxOptions = new VertxOptions()
                    .setClusterManager(clusterManager);


                Vertx.clusteredVertx(vertxOptions, vertxAsyncResult -> {
                    if (vertxAsyncResult.succeeded()) {

                        Router router = Router.router(vertx);

                        // Config CORS
                        router.route().handler(CorsHandler.create("*").allowedMethod(HttpMethod.GET).allowedHeader("Content-Type"));

                        // hello endpoint
                        router.get("/api/hello/:name").handler(ctx -> {
                            String helloMsg = hello(ctx.request().getParam("name"));
                            log.info("New request from " + ctx.request().getHeader("User-Agent") + "\nSaying...: " + helloMsg);
                            ctx.response().end(helloMsg);
                        });

                        // Add Stuff
                        router.get("/api/addstuff").handler(ctx -> {
                            String name = ctx.request().getParam("name");
                            if (name == null) {
                                ctx.response().end("Missing name parameter");
                            } else {
                                Cache<String, String> cache = defaultCacheManager.getCache(CACHE_NAME);
                                int currentSize = cache.keySet().size();
                                // using the size as a way to provide uniqueness to keys
                                //TODO - can we not use System.currentime millis or UUID for this ??
                                String key = currentSize + "_" + hostname;
                                cache.put(key, name);
                                ctx.response().end("Added " + name + " to " + key);
                            }
                        });

                        // Clear
                        router.get("/api/clearstuff").handler(ctx -> {
                            Cache<String, String> cache = defaultCacheManager.getCache(CACHE_NAME);
                            cache.clear();
                            ctx.response().end("Cleared");
                        });

                        // Get Stuff
                        router.get("/api/getstuff").handler(ctx -> {
                            Cache<String, String> cache = defaultCacheManager.getCache(CACHE_NAME);
                            Map<String, Object> helloWorldCacheValues = cache.entrySet().stream()
                                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                            JsonObject jsonObject = new JsonObject(helloWorldCacheValues);
                            ctx.response()
                                .setStatusCode(200)
                                .putHeader("content-type", "application/json")
                                .end(jsonObject.encodePrettily());
                        });

                        // health check endpoint
                        router.get("/healthz").handler(ctx -> ctx.response()
                            .setStatusCode(200)
                            .end());

                        vertx.createHttpServer()
                            .requestHandler(router::accept)
                            .listen(8080, result -> {
                                if (result.succeeded()) {
                                    fut.complete();
                                } else {
                                    log.info("Unable to start HTTP Server", result.cause());
                                    fut.fail(result.cause());
                                }
                            });

                    } else {
                        log.info("Error starting verticle ", vertxAsyncResult.cause());
                        fut.fail(vertxAsyncResult.cause());
                    }
                });
            } else {
                log.info("Error starting verticle ", asyncResult.cause());
                fut.fail(asyncResult.cause());
            }
        });

    }

    //TODO - SK can this not be made a neat JSON response using JSONObject from vertx ??
    private String hello(String name) {
        String greeting = "Hello {name} from {hostname} with {version}";
        Map<String, String> values = new HashMap<>();
        values.put("name", name);
        values.put("hostname", System.getenv().getOrDefault("HOSTNAME", "unknown"));
        values.put("version", version);
        return new StrSubstitutor(values, "{", "}").replace(greeting);
    }

}
