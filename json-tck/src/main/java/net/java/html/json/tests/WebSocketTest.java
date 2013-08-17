/**
 * HTML via Java(tm) Language Bindings
 * Copyright (C) 2013 Jaroslav Tulach <jaroslav.tulach@apidesign.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details. apidesign.org
 * designates this particular file as subject to the
 * "Classpath" exception as provided by apidesign.org
 * in the License file that accompanied this code.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://wiki.apidesign.org/wiki/GPLwithClassPathException
 */
package net.java.html.json.tests;

import net.java.html.BrwsrCtx;
import net.java.html.json.Model;
import net.java.html.json.Models;
import net.java.html.json.OnReceive;
import net.java.html.json.Property;
import org.apidesign.html.json.tck.KOTest;

/** Testing support of WebSocket communication.
 *
 * @author Jaroslav Tulach <jtulach@netbeans.org>
 */
@Model(className = "WebSocketik", properties = {
    @Property(name = "fetched", type = Person.class),
    @Property(name = "fetchedCount", type = int.class),
    @Property(name = "fetchedResponse", type = String.class),
    @Property(name = "fetchedSex", type = Sex.class, array = true)
})
public final class WebSocketTest {
    private WebSocketik js;
    private String url;
    
    @OnReceive(url = "{url}", data = Sex.class, method = "WebSocket", onError = "error")
    static void querySex(WebSocketik model, Person data) {
        model.setFetched(data);
    }
    
    @KOTest public void connectUsingWebSocket() throws Throwable {
        if (js == null) {
            url = Utils.prepareURL(
                JSONTest.class, "{'firstName': 'Mitar', 'sex': $0}", 
                "application/javascript",
                "protocol:ws"
            );
            
            js = Models.bind(new WebSocketik(), newContext());
            js.applyBindings();

            js.setFetched(null);
            js.querySex(url, Sex.FEMALE);
        }
        
        if (bailOutIfNotSupported(js)) {
            return;
        }
    
        Person p = js.getFetched();
        if (p == null) {
            throw new InterruptedException();
        }
        
        assert "Mitar".equals(p.getFirstName()) : "Unexpected: " + p.getFirstName();
        assert Sex.FEMALE.equals(p.getSex()) : "Expecting FEMALE: " + p.getSex();
    }
    
    @KOTest public void errorUsingWebSocket() throws Throwable {
        js = Models.bind(new WebSocketik(), newContext());
        js.applyBindings();

        js.setFetched(null);
        js.querySex("http://wrong.protocol", Sex.MALE);

        assert js.getFetchedResponse() != null : "Error reported";
    }
    
    static void error(WebSocketik model, Exception ex) {
        model.setFetchedResponse(ex.getClass() + ":" + ex.getMessage());
    }
    
    private static BrwsrCtx newContext() {
        return Utils.newContext(WebSocketTest.class);
    }

    private boolean bailOutIfNotSupported(WebSocketik js) {
        if (js.getFetchedResponse() == null) {
            return false;
        }
        return js.getFetchedResponse().contains("UnsupportedOperationException");
    }
    
}
