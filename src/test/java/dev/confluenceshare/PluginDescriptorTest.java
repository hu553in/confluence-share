package dev.confluenceshare;

import com.atlassian.plugin.Plugin;
import com.atlassian.plugin.module.Element;
import com.atlassian.plugin.web.Condition;
import com.atlassian.plugin.web.descriptors.ConditionElementParser;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PluginDescriptorTest {
    @Test
    void pageActionConditionsAreAcceptedByTheHostParserAndRequireBothChecks() throws Exception {
        org.jsoup.nodes.Element item;
        try (var input = getClass().getResourceAsStream("/atlassian-plugin.xml")) {
            assertNotNull(input);
            item = Jsoup.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8), "", Parser.xmlParser())
                    .selectFirst("web-item[key=share-page-link]");
        }
        assertNotNull(item);
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> {
                    throw new AssertionError("Unexpected plugin call: " + method);
                });
        // Keep condition instantiation at the host boundary; composition and XML type parsing are real.
        var parser = new ConditionElementParser((name, owner) -> {
            throw new AssertionError("Unexpected condition factory call");
        }) {
            @Override
            protected Condition makeConditionImplementation(Plugin owner, Element element) {
                String name = element.attributeValue("class");
                return context -> Boolean.TRUE.equals(context.get(name));
            }
        };
        Condition condition = parser.makeConditions(plugin, element(item), 1);
        String hasPage = "com.atlassian.confluence.plugin.descriptor.web.conditions.HasPageCondition";
        String loggedIn = "com.atlassian.confluence.plugin.descriptor.web.conditions.user.UserLoggedInCondition";
        assertNotNull(condition);
        assertTrue(condition.shouldDisplay(Map.of(hasPage, true, loggedIn, true)));
        assertFalse(condition.shouldDisplay(Map.of(hasPage, false, loggedIn, true)));
        assertFalse(condition.shouldDisplay(Map.of(hasPage, true, loggedIn, false)));
        assertFalse(condition.shouldDisplay(Map.of(hasPage, false, loggedIn, false)));
    }

    private static Element element(org.jsoup.nodes.Element xml) {
        return (Element) Proxy.newProxyInstance(Element.class.getClassLoader(), new Class<?>[]{Element.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "attributeValue" -> xml.hasAttr((String) args[0]) ? xml.attr((String) args[0])
                            : args.length == 2 ? args[1] : null;
                    case "elements" -> xml.children().stream()
                            .filter(child -> args == null || child.tagName().equals(args[0]))
                            .map(PluginDescriptorTest::element).toList();
                    default -> throw new AssertionError("Unexpected XML accessor: " + method);
                });
    }
}
