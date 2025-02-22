/*
 * Copyright 2000-2023 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.flow.server.communication;

import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.jcip.annotations.NotThreadSafe;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentTest;
import com.vaadin.flow.component.HasComponents;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.UITest;
import com.vaadin.flow.component.dependency.JavaScript;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import com.vaadin.flow.component.internal.UIInternals;
import com.vaadin.flow.component.internal.UIInternals.JavaScriptInvocation;
import com.vaadin.flow.di.Lookup;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.dom.ElementFactory;
import com.vaadin.flow.internal.JsonUtils;
import com.vaadin.flow.internal.StateTree;
import com.vaadin.flow.router.ParentLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteConfiguration;
import com.vaadin.flow.router.RoutePathProvider;
import com.vaadin.flow.router.RouterLayout;
import com.vaadin.flow.server.MockServletServiceSessionSetup;
import com.vaadin.flow.server.MockVaadinContext.RoutePathProviderImpl;
import com.vaadin.flow.server.VaadinServletContext;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.frontend.BundleUtils;
import com.vaadin.flow.shared.ApplicationConstants;
import com.vaadin.flow.shared.ui.Dependency;
import com.vaadin.flow.shared.ui.LoadMode;

import elemental.json.Json;
import elemental.json.JsonArray;
import elemental.json.JsonObject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@NotThreadSafe
public class UidlWriterTest {
    private static final String CSS_STYLE_NAME = Dependency.Type.STYLESHEET
            .name();
    private MockServletServiceSessionSetup mocks;

    @JavaScript("UI-parent-JAVASCRIPT")
    private static class ParentUI extends UI {
    }

    @JavaScript("UI-JAVASCRIPT")
    private static class TestUI extends ParentUI {
    }

    @Tag("div")
    @JavaScript("super-JAVASCRIPT")
    @StyleSheet("super-STYLESHEET")
    public static class SuperComponent extends Component {
    }

    public static class EmptyClassWithInterface extends SuperComponent
            implements AnotherComponentInterface {
    }

    @JavaScript("JAVASCRIPT")
    @StyleSheet("STYLESHEET")
    public static class ActualComponent extends EmptyClassWithInterface
            implements ComponentInterface {
    }

    @JavaScript("child1-JAVASCRIPT")
    @JavaScript("child2-JAVASCRIPT")
    @StyleSheet("child1-STYLESHEET")
    @StyleSheet("child2-STYLESHEET")
    public static class ChildComponent extends ActualComponent
            implements ChildComponentInterface2 {
    }

    @JavaScript("interface-JAVASCRIPT")
    @StyleSheet("interface-STYLESHEET")
    public interface ComponentInterface {
    }

    @JavaScript("anotherinterface-JAVASCRIPT")
    @StyleSheet("anotherinterface-STYLESHEET")
    public interface AnotherComponentInterface {
    }

    @JavaScript("childinterface1-JAVASCRIPT")
    @StyleSheet("childinterface1-STYLESHEET")
    public interface ChildComponentInterface1 {
    }

    @JavaScript("childinterface2-JAVASCRIPT")
    @StyleSheet("childinterface2-STYLESHEET")
    public interface ChildComponentInterface2 extends ChildComponentInterface1 {
    }

    @Tag("test")
    @JavaScript(value = "lazy.js", loadMode = LoadMode.LAZY)
    @StyleSheet(value = "lazy.css", loadMode = LoadMode.LAZY)
    @JavaScript(value = "inline.js", loadMode = LoadMode.INLINE)
    @StyleSheet(value = "inline.css", loadMode = LoadMode.INLINE)
    @JavaScript("eager.js")
    @StyleSheet("eager.css")
    public static class ComponentWithAllDependencyTypes extends Component {
    }

    @Tag("base")
    @Route(value = "", layout = ParentClass.class)
    public static class BaseClass extends Component {
    }

    @Tag("parent")
    @ParentLayout(SuperParentClass.class)
    public static class ParentClass extends Component implements RouterLayout {
    }

    @Tag("super-parent")
    public static class SuperParentClass extends Component
            implements RouterLayout {
    }

    @Tag("components-container")
    public static class ComponentsContainer extends Component
            implements HasComponents {

    }

    @After
    public void tearDown() {
        if (mocks != null) {
            mocks.cleanup();
        }
    }

    @Test
    public void testEncodeExecuteJavaScript_npmMode() {
        Element element = ElementFactory.createDiv();

        JavaScriptInvocation invocation1 = new JavaScriptInvocation(
                "$0.focus()", element);
        JavaScriptInvocation invocation2 = new JavaScriptInvocation(
                "console.log($0, $1)", "Lives remaining:", Integer.valueOf(3));
        List<PendingJavaScriptInvocation> executeJavaScriptList = Stream
                .of(invocation1, invocation2)
                .map(invocation -> new PendingJavaScriptInvocation(
                        element.getNode(), invocation))
                .collect(Collectors.toList());

        JsonArray json = UidlWriter
                .encodeExecuteJavaScriptList(executeJavaScriptList);

        JsonArray expectedJson = JsonUtils.createArray(JsonUtils.createArray(
                // Null since element is not attached
                Json.createNull(), Json.create("$0.focus()")),
                JsonUtils.createArray(Json.create("Lives remaining:"),
                        Json.create(3), Json.create("console.log($0, $1)")));

        assertTrue(JsonUtils.jsonEquals(expectedJson, json));
    }

    @Test
    public void componentDependencies_npmMode() throws Exception {
        UI ui = initializeUIForDependenciesTest(new TestUI());
        UidlWriter uidlWriter = new UidlWriter();
        addInitialComponentDependencies(ui, uidlWriter);

        // no dependencies should be resent in next response
        JsonObject response = uidlWriter.createUidl(ui, false);
        assertFalse(response.hasKey(LoadMode.EAGER.name()));
        assertFalse(response.hasKey(LoadMode.INLINE.name()));
        assertFalse(response.hasKey(LoadMode.LAZY.name()));
    }

    @Test
    public void componentDependencies_productionMode_scanForParentClasses()
            throws Exception {
        UI ui = initializeUIForDependenciesTest(new TestUI());
        mocks.getDeploymentConfiguration().setProductionMode(true);

        UidlWriter uidlWriter = new UidlWriter();
        ui.add(new ChildComponent());

        // no dependencies should be resent in next response
        JsonObject response = uidlWriter.createUidl(ui, false);
        Set<String> chunks = getDependenciesMap(response).keySet().stream()
                .filter(key -> key
                        .startsWith("return window.Vaadin.Flow.loadOnDemand('"))
                .map(key -> key
                        .replace("return window.Vaadin.Flow.loadOnDemand('", "")
                        .replace("');", ""))
                .collect(Collectors.toSet());

        Set<String> expectedChunks = Stream
                .of(TestUI.class, BaseClass.class, ChildComponent.class,
                        ActualComponent.class, EmptyClassWithInterface.class,
                        SuperComponent.class)
                .map(BundleUtils::getChunkId).collect(Collectors.toSet());

        assertEquals(expectedChunks, chunks);
    }

    @Test
    public void componentDependencies_developmentMode_onlySendComponentSpecificChunks()
            throws Exception {
        UidlWriter uidlWriter = new UidlWriter();
        UI ui = initializeUIForDependenciesTest(new TestUI());
        ui.add(new ChildComponent());

        // no dependencies should be resent in next response
        JsonObject response = uidlWriter.createUidl(ui, false);
        Set<String> chunks = getDependenciesMap(response).keySet().stream()
                .filter(key -> key
                        .startsWith("return window.Vaadin.Flow.loadOnDemand('"))
                .map(key -> key
                        .replace("return window.Vaadin.Flow.loadOnDemand('", "")
                        .replace("');", ""))
                .collect(Collectors.toSet());

        Set<String> expectedChunks = Stream
                .of(TestUI.class, BaseClass.class, ChildComponent.class)
                .map(BundleUtils::getChunkId).collect(Collectors.toSet());

        assertEquals(expectedChunks, chunks);
    }

    @Test
    public void testComponentInterfaceDependencies_npmMode() throws Exception {
        UI ui = initializeUIForDependenciesTest(new TestUI());
        UidlWriter uidlWriter = new UidlWriter();

        addInitialComponentDependencies(ui, uidlWriter);

        // test that dependencies only from new child interfaces are added
        ui.add(new ActualComponent(), new SuperComponent(),
                new ChildComponent());

        JsonObject response = uidlWriter.createUidl(ui, false);
        Map<String, JsonObject> dependenciesMap = ComponentTest
                .filterLazyLoading(getDependenciesMap(response));

        assertEquals(4, dependenciesMap.size());
        assertDependency("childinterface1-" + CSS_STYLE_NAME, CSS_STYLE_NAME,
                dependenciesMap);
        assertDependency("childinterface2-" + CSS_STYLE_NAME, CSS_STYLE_NAME,
                dependenciesMap);
        assertDependency("child1-" + CSS_STYLE_NAME, CSS_STYLE_NAME,
                dependenciesMap);
        assertDependency("child2-" + CSS_STYLE_NAME, CSS_STYLE_NAME,
                dependenciesMap);
    }

    @Test
    public void checkAllTypesOfDependencies_npmMode() throws Exception {
        UI ui = initializeUIForDependenciesTest(new TestUI());
        UidlWriter uidlWriter = new UidlWriter();
        addInitialComponentDependencies(ui, uidlWriter);

        ui.add(new ComponentWithAllDependencyTypes());
        JsonObject response = uidlWriter.createUidl(ui, false);
        Map<LoadMode, List<JsonObject>> dependenciesMap = Stream
                .of(LoadMode.values())
                .map(mode -> response.getArray(mode.name()))
                .flatMap(JsonUtils::<JsonObject> stream)
                .collect(Collectors.toMap(
                        jsonObject -> LoadMode.valueOf(
                                jsonObject.getString(Dependency.KEY_LOAD_MODE)),
                        Collections::singletonList, (list1, list2) -> {
                            List<JsonObject> result = new ArrayList<>(list1);
                            result.addAll(list2);
                            return result;
                        }));
        dependenciesMap.get(LoadMode.LAZY).removeIf(obj -> obj
                .getString(Dependency.KEY_URL).contains("Flow.loadOnDemand"));
        assertThat(
                "Dependencies with all types of load mode should be present in this response",
                dependenciesMap.size(), is(LoadMode.values().length));

        List<JsonObject> eagerDependencies = dependenciesMap
                .get(LoadMode.EAGER);
        assertThat("Should have an eager dependency", eagerDependencies,
                hasSize(1));
        assertThat("Eager dependencies should not have inline contents",
                eagerDependencies.stream()
                        .filter(json -> json.hasKey(Dependency.KEY_CONTENTS))
                        .collect(Collectors.toList()),
                hasSize(0));

        JsonObject eagerDependency = eagerDependencies.get(0);
        assertEquals("eager.css",
                eagerDependency.getString(Dependency.KEY_URL));
        assertEquals(Dependency.Type.STYLESHEET, Dependency.Type
                .valueOf(eagerDependency.getString(Dependency.KEY_TYPE)));

        List<JsonObject> lazyDependencies = dependenciesMap.get(LoadMode.LAZY);
        JsonObject lazyDependency = lazyDependencies.get(0);
        assertEquals("lazy.css", lazyDependency.getString(Dependency.KEY_URL));
        assertEquals(Dependency.Type.STYLESHEET, Dependency.Type
                .valueOf(lazyDependency.getString(Dependency.KEY_TYPE)));

        List<JsonObject> inlineDependencies = dependenciesMap
                .get(LoadMode.INLINE);
        assertInlineDependencies(inlineDependencies);
    }

    @Test
    public void resynchronizationRequested_responseFieldContainsResynchronize()
            throws Exception {
        UI ui = initializeUIForDependenciesTest(new TestUI());
        UidlWriter uidlWriter = new UidlWriter();

        JsonObject response = uidlWriter.createUidl(ui, false, true);
        assertTrue("Response contains resynchronize field",
                response.hasKey(ApplicationConstants.RESYNCHRONIZE_ID));
        assertTrue("Response resynchronize field is set to true",
                response.getBoolean(ApplicationConstants.RESYNCHRONIZE_ID));
    }

    @Test
    public void createUidl_allChangesCollected_uiIsNotDirty() throws Exception {
        UI ui = initializeUIForDependenciesTest(new TestUI());

        ComponentsContainer container = new ComponentsContainer();
        container.add(new ChildComponent());
        ui.add(container);
        // removing all elements causes an additional ListClearChange to be
        // added during collectChanges process
        container.removeAll();

        UidlWriter uidlWriter = new UidlWriter();
        uidlWriter.createUidl(ui, false, true);

        assertFalse("UI is still dirty after creating UIDL",
                ui.getInternals().isDirty());
    }

    @Test
    public void createUidl_collectChangesUIStillDirty_shouldNotLoopEndlessly()
            throws Exception {
        UI ui = initializeUIForDependenciesTest(spy(new TestUI()));
        StateTree stateTree = spy(ui.getInternals().getStateTree());
        UIInternals internals = spy(ui.getInternals());

        when(ui.getInternals()).thenReturn(internals);
        when(internals.getStateTree()).thenReturn(stateTree);
        when(stateTree.hasDirtyNodes()).thenReturn(true);

        UidlWriter uidlWriter = new UidlWriter();
        uidlWriter.createUidl(ui, false, true);

        assertTrue(
                "Simulating collectChanges bug and expecting UI to be still dirty after creating UIDL",
                ui.getInternals().isDirty());
    }

    private void assertInlineDependencies(List<JsonObject> inlineDependencies) {
        assertThat("Should have an inline dependency", inlineDependencies,
                hasSize(1));
        assertThat("Eager dependencies should not have urls",
                inlineDependencies.stream()
                        .filter(json -> json.hasKey(Dependency.KEY_URL))
                        .collect(Collectors.toList()),
                hasSize(0));

        JsonObject inlineDependency = inlineDependencies.get(0);

        String url = inlineDependency.getString(Dependency.KEY_CONTENTS);
        assertEquals("inline.css", url);
        assertEquals(Dependency.Type.STYLESHEET, Dependency.Type
                .valueOf(inlineDependency.getString(Dependency.KEY_TYPE)));
    }

    private UI initializeUIForDependenciesTest(UI ui) throws Exception {
        mocks = new MockServletServiceSessionSetup();

        VaadinServletContext context = (VaadinServletContext) mocks.getService()
                .getContext();
        Lookup lookup = context.getAttribute(Lookup.class);
        Mockito.when(lookup.lookup(RoutePathProvider.class))
                .thenReturn(new RoutePathProviderImpl());

        VaadinSession session = mocks.getSession();
        session.lock();
        ui.getInternals().setSession(session);

        RouteConfiguration routeConfiguration = RouteConfiguration
                .forRegistry(ui.getInternals().getRouter().getRegistry());
        routeConfiguration.update(() -> {
            routeConfiguration.getHandledRegistry().clean();
            routeConfiguration.setAnnotatedRoute(BaseClass.class);
        });

        for (String type : new String[] { "html", "js", "css" }) {
            mocks.getServlet().addServletContextResource("inline." + type,
                    "inline." + type);
        }

        HttpServletRequest servletRequestMock = mock(HttpServletRequest.class);

        VaadinServletRequest vaadinRequestMock = mock(
                VaadinServletRequest.class);

        when(vaadinRequestMock.getHttpServletRequest())
                .thenReturn(servletRequestMock);

        ui.doInit(vaadinRequestMock, 1);
        ui.getInternals().getRouter().initializeUI(ui,
                UITest.requestToLocation(vaadinRequestMock));

        return ui;
    }

    private void addInitialComponentDependencies(UI ui, UidlWriter uidlWriter) {
        ui.add(new ActualComponent());

        JsonObject response = uidlWriter.createUidl(ui, false);
        Map<String, JsonObject> dependenciesMap = ComponentTest
                .filterLazyLoading(getDependenciesMap(response));

        assertEquals(4, dependenciesMap.size());

        // UI parent first, then UI, then super component's dependencies, then
        // the interfaces and then the component
        assertDependency("super-" + CSS_STYLE_NAME, CSS_STYLE_NAME,
                dependenciesMap);

        assertDependency("anotherinterface-" + CSS_STYLE_NAME, CSS_STYLE_NAME,
                dependenciesMap);

        assertDependency("interface-" + CSS_STYLE_NAME, CSS_STYLE_NAME,
                dependenciesMap);

        assertDependency(CSS_STYLE_NAME, CSS_STYLE_NAME, dependenciesMap);
    }

    private Map<String, JsonObject> getDependenciesMap(JsonObject response) {
        return Stream.of(LoadMode.values())
                .map(mode -> response.getArray(mode.name()))
                .flatMap(JsonUtils::<JsonObject> stream)
                .collect(Collectors.toMap(
                        jsonObject -> jsonObject.getString(Dependency.KEY_URL),
                        Function.identity()));
    }

    private void assertDependency(String url, String type,
            Map<String, JsonObject> dependenciesMap) {
        JsonObject jsonValue = dependenciesMap.get(url);
        assertNotNull(
                "Expected dependencies map to have dependency with key=" + url,
                jsonValue);
        assertEquals(url, jsonValue.get(Dependency.KEY_URL).asString());
        assertEquals(type, jsonValue.get(Dependency.KEY_TYPE).asString());
    }

}
