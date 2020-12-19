/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.netbeans.html.presenters.browser;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.glassfish.grizzly.PortRange;
import org.glassfish.grizzly.http.io.InputBuffer;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.netbeans.html.boot.spi.Fn;

final class GrizzlyServer extends HttpServer<Request, Response, Object, GrizzlyServer.Context> {
    private org.glassfish.grizzly.http.server.HttpServer server;

    @Override
    void init(int from, int to) throws IOException {
        server = org.glassfish.grizzly.http.server.HttpServer.createSimpleServer(null, new PortRange(from, to));
    }

    @Override
    void shutdownNow() {
        server.shutdownNow();
    }

    @Override
    void addHttpHandler(Handler r, String mapping) {
        server.getServerConfiguration().addHttpHandler(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                r.service(GrizzlyServer.this, request, response);
            }
        }, mapping);
    }

    @Override
    int getPort() {
        return server.getListeners().iterator().next().getPort();
    }

    @Override
    void start() throws IOException {
        server.start();
    }

    @Override
    String getRequestURI(Request r) {
        return r.getRequestURI();
    }

    @Override
    String getServerName(Request r) {
        return r.getServerName();
    }

    @Override
    int getServerPort(Request r) {
        return r.getServerPort();
    }

    @Override
    String getParameter(Request r, String id) {
        return r.getParameter(id);
    }

    @Override
    String getMethod(Request r) {
        return r.getMethod().getMethodString();
    }

    @Override
    String getBody(Request r) throws IOException {
        final InputBuffer buffer = r.getInputBuffer();
        buffer.processingChars();
        buffer.fillFully(-1);
        int len = buffer.availableChar();
        char[] arr = new char[len];
        int reallyRead = buffer.read(arr, 0, len);
        assert reallyRead == len;
        return new String(arr);
    }

    @Override
    String getHeader(Request r, String header) {
        return r.getHeader(header);
    }

    @Override
    Writer getWriter(Response r) {
        return r.getWriter();
    }

    @Override
    void setContentType(Response r, String type) {
        r.setContentType(type);
    }

    @Override
    void setStatus(Response r, int code) {
        r.setStatus(code);
    }

    @Override
    OutputStream getOutputStream(Response r) {
        return r.getOutputStream();
    }

    @Override
    void suspend(Response r) {
        r.suspend();
    }

    @Override
    void resume(Response r, Runnable whenReady) {
        whenReady.run();
        r.resume();
    }

    @Override
    void setCharacterEncoding(Response r, String set) {
        r.setCharacterEncoding(set);
    }

    @Override
    void addHeader(Response r, String name, String value) {
        r.addHeader(name, value);
    }

    @Override
    <WebSocket> void send(WebSocket socket, String s) {
    }

    class Context implements ThreadFactory {
        private final String id;
        Executor RUN;
        Thread RUNNER;

        Context(String id) {
            this.id = id;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "Processor for " + id);
            RUNNER = t;
            return t;
        }
    }

    @Override
    Context initializeRunner(String id) {
        Context c = new Context(id);
        c.RUN = Executors.newSingleThreadExecutor(c);
        return c;
    }

    @Override
    final void runSafe(Context c, final Runnable r, final Fn.Presenter presenter) {
        class Wrap implements Runnable {
            @Override
            public void run() {
                if (presenter != null) {
                    try (Closeable c = Fn.activate(presenter)) {
                        r.run();
                    } catch (IOException ex) {
                        // go on
                    }
                } else {
                    r.run();
                }
            }
        }
        if (c.RUNNER == Thread.currentThread()) {
            if (presenter != null) {
                Runnable w = new Wrap();
                w.run();
            } else {
                r.run();
            }
        } else {
            Runnable w = new Wrap();
            c.RUN.execute(w);
        }
    }
}
