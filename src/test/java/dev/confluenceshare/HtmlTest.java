package dev.confluenceshare;

import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlTest {
    @Test
    void concurrentFirstManagementFormsShareAValidCsrfToken() throws Exception {
        var attributes = new ConcurrentHashMap<String, Object>();
        var firstReads = new CountDownLatch(2);
        InvocationHandler sessionAttributes = (proxy, method, args) -> {
            if (method.getName().equals("getAttribute")) {
                Object value = attributes.get(args[0]);
                if (value == null) {
                    // Let concurrent initial requests overlap; serialized initialization needs no second reader.
                    firstReads.countDown();
                    firstReads.await(1, TimeUnit.SECONDS);
                }
                return value;
            }
            if (method.getName().equals("setAttribute")) {
                attributes.put((String) args[0], args[1]);
                return null;
            }
            throw new UnsupportedOperationException(method.getName());
        };
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> {
                    // Atlassian's request wrapper creates a fresh session wrapper for every getSession() call.
                    if (method.getName().equals("getSession"))
                        return Proxy.newProxyInstance(HttpSession.class.getClassLoader(),
                                new Class<?>[]{HttpSession.class}, sessionAttributes);
                    throw new UnsupportedOperationException(method.getName());
                });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> Html.csrf(request));
            var second = pool.submit(() -> Html.csrf(request));
            String firstToken = first.get(5, TimeUnit.SECONDS);
            String secondToken = second.get(5, TimeUnit.SECONDS);
            assertTrue(Html.validCsrf(request, firstToken), "The first freshly opened form must remain valid");
            assertTrue(Html.validCsrf(request, secondToken), "The second freshly opened form must remain valid");
            assertEquals(firstToken, secondToken);
        } finally {
            pool.shutdownNow();
        }
    }
}
