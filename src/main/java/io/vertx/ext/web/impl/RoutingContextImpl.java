/*
 * Copyright 2014 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.web.impl;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Cookie;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;
import io.vertx.ext.auth.User;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class RoutingContextImpl extends RoutingContextImplBase {

  private final RouterImpl router;
  private Map<String, Object> data;
  private AtomicInteger handlerSeq = new AtomicInteger();
  private Map<Integer, Handler<Future>> headersEndHandlers;
  private Map<Integer, Handler<Void>> bodyEndHandlers;
  private Throwable failure;
  private int statusCode = -1;
  private String normalisedPath;
  private String acceptableContentType;

  // We use Cookie as the key too so we can return keySet in cookies() without copying
  private Map<String, Cookie> cookies;
  private Buffer body;
  private Set<FileUpload> fileUploads;
  private Session session;
  private User user;

  public RoutingContextImpl(String mountPoint, RouterImpl router, HttpServerRequest request, Set<RouteImpl> routes) {
    super(mountPoint, request, routes);
    this.router = router;
    if (request.path().charAt(0) != '/') {
      fail(404);
    }
  }

  @Override
  public HttpServerRequest request() {
    return request;
  }

  @Override
  public HttpServerResponse response() {
    return request.response();
  }

  @Override
  public Throwable failure() {
    return failure;
  }

  @Override
  public int statusCode() {
    return statusCode;
  }

  @Override
  public boolean failed() {
    return failure != null || statusCode != -1;
  }

  @Override
  public void next() {
    if (!iterateNext()) {
      checkHandleNoMatch();
    }
  }

  private void checkHandleNoMatch() {
    // Next called but no more matching routes
    if (failed()) {
      // Send back FAILURE
      unhandledFailure(statusCode, failure, router);
    } else {
      // Send back default 404
      response().setStatusCode(404);
      if (request().method() == HttpMethod.HEAD) {
        // HEAD responses don't have a body
        response().end();
      } else {
        response().end(DEFAULT_404);
      }
    }
  }

  @Override
  public void fail(int statusCode) {
    this.statusCode = statusCode;
    doFail();
  }

  @Override
  public void fail(Throwable t) {
    this.failure = t;
    doFail();
  }

  @Override
  public RoutingContext put(String key, Object obj) {
    getData().put(key, obj);
    return this;
  }

  @Override
  public Vertx vertx() {
    return router.vertx();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T get(String key) {
    Object obj = getData().get(key);
    return (T)obj;
  }

  @Override
  public Map<String, Object> data() {
    return getData();
  }

  @Override
  public String normalisedPath() {
    if (normalisedPath == null) {
      normalisedPath = Utils.normalisePath(request.path());
    }
    return normalisedPath;
  }

  @Override
  public Cookie getCookie(String name) {
    return cookiesMap().get(name);
  }

  @Override
  public RoutingContext addCookie(Cookie cookie) {
    cookiesMap().put(cookie.getName(), cookie);
    return this;
  }

  @Override
  public Cookie removeCookie(String name) {
    return cookiesMap().remove(name);
  }

  @Override
  public int cookieCount() {
    return cookiesMap().size();
  }

  @Override
  public Set<Cookie> cookies() {
    return new HashSet<>(cookiesMap().values());
  }

  @Override
  public String getBodyAsString() {
    return body != null ? body.toString() : null;
  }

  @Override
  public String getBodyAsString(String encoding) {
    return body != null ? body.toString(encoding) : null;
  }

  @Override
  public JsonObject getBodyAsJson() {
    return body != null ? new JsonObject(body.toString()) : null;
  }

  @Override
  public Buffer getBody() {
    return body;
  }

  @Override
  public void setBody(Buffer body) {
    this.body = body;
  }

  @Override
  public Set<FileUpload> fileUploads() {
    return getFileUploads();
  }

  @Override
  public void setSession(Session session) {
    this.session = session;
  }

  @Override
  public Session session() {
    return session;
  }

  @Override
  public User user() {
    return user;
  }

  @Override
  public void setUser(User user) {
    this.user = user;
  }

  @Override
  public void clearUser() {
    this.user = null;
  }

  @Override
  public String getAcceptableContentType() {
    return acceptableContentType;
  }

  @Override
  public void setAcceptableContentType(String contentType) {
    this.acceptableContentType = contentType;
  }

  @Override
  public int addHeadersEndHandler(Handler<Future> handler) {
    int seq = nextHandlerSeq();
    getHeadersEndHandlers().put(seq, handler);
    return seq;
  }

  @Override
  public boolean removeHeadersEndHandler(int handlerID) {
    return getHeadersEndHandlers().remove(handlerID) != null;
  }

  @Override
  public int addBodyEndHandler(Handler<Void> handler) {
    int seq = nextHandlerSeq();
    getBodyEndHandlers().put(seq, handler);
    return seq;
  }

  @Override
  public boolean removeBodyEndHandler(int handlerID) {
    return getBodyEndHandlers().remove(handlerID) != null;
  }

  @Override
  public void reroute(HttpMethod method, String path) {
    ((HttpServerRequestWrapper) request).setMethod(method);
    ((HttpServerRequestWrapper) request).setPath(path);
    restart();
  }

  private Map<Integer, Handler<Future>> getHeadersEndHandlers() {
    if (headersEndHandlers == null) {
      headersEndHandlers = new LinkedHashMap<>();
      response().headersEndHandler(fut -> {
        Iterator<Handler<Future>> iter = headersEndHandlers.values().iterator();
        callNextHeadersEndHandler(fut, iter);
      });
    }
    return headersEndHandlers;
  }

  private void callNextHeadersEndHandler(Future endFut, Iterator<Handler<Future>> iter) {
    if (iter.hasNext()) {
      Handler<Future> handler = iter.next();
      Future<?> fut = Future.future();
      fut.setHandler(res -> {
        if (res.succeeded()) {
          callNextHeadersEndHandler(endFut, iter);
        } else {
          endFut.fail(res.cause());
        }
      });
      handler.handle(fut);
    } else {
      endFut.complete();
    }
  }

  private Map<Integer, Handler<Void>> getBodyEndHandlers() {
    if (bodyEndHandlers == null) {
      bodyEndHandlers = new LinkedHashMap<>();
      response().bodyEndHandler(v -> bodyEndHandlers.values().forEach(handler -> handler.handle(null)));
    }
    return bodyEndHandlers;
  }

  private Map<String, Cookie> cookiesMap() {
    if (cookies == null) {
      cookies = new HashMap<>();
    }
    return cookies;
  }

  private Set<FileUpload> getFileUploads() {
    if (fileUploads == null) {
      fileUploads = new HashSet<>();
    }
    return fileUploads;
  }

  private void doFail() {
    this.iter = router.iterator();
    next();
  }

  private Map<String, Object> getData() {
    if (data == null) {
      data = new HashMap<>();
    }
    return data;
  }

  private int nextHandlerSeq() {
    int seq = handlerSeq.incrementAndGet();
    if (seq == Integer.MAX_VALUE) {
      throw new IllegalStateException("Too many header/body end handlers!");
    }
    return seq;
  }

  private static final String DEFAULT_404 =
    "<html><body><h1>Resource not found</h1></body></html>";

}
