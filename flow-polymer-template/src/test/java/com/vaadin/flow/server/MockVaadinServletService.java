/**
 * Copyright (C) 2022 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.flow.server;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;

import java.util.Collections;
import java.util.List;

import org.mockito.Mockito;

import com.vaadin.flow.di.Instantiator;
import com.vaadin.flow.di.Lookup;
import com.vaadin.flow.di.ResourceProvider;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.router.Router;

public class MockVaadinServletService extends VaadinServletService {

    private Instantiator instantiator;

    private Router router;

    private ResourceProvider resourceProvider = Mockito
            .mock(ResourceProvider.class);

    private Lookup lookup = Mockito.mock(Lookup.class);

    private static class MockVaadinServlet extends VaadinServlet {

        private final DeploymentConfiguration configuration;

        private VaadinServletService service;

        private MockVaadinServlet(DeploymentConfiguration configuration) {
            this.configuration = configuration;
        }

        @Override
        protected DeploymentConfiguration createDeploymentConfiguration()
                throws ServletException {
            return configuration;
        }

        @Override
        protected VaadinServletService createServletService(
                DeploymentConfiguration deploymentConfiguration)
                throws ServiceException {
            return service;
        }

    }

    public MockVaadinServletService(
            DeploymentConfiguration deploymentConfiguration) {
        super(new MockVaadinServlet(deploymentConfiguration),
                deploymentConfiguration);
        init();
    }

    public void setRouter(Router router) {
        this.router = router;
    }

    @Override
    public Router getRouter() {
        return router != null ? router : super.getRouter();
    }

    @Override
    protected RouteRegistry getRouteRegistry() {
        return Mockito.mock(RouteRegistry.class);
    }

    @Override
    protected List<RequestHandler> createRequestHandlers()
            throws ServiceException {
        return Collections.emptyList();
    }

    public void init(Instantiator instantiator) {
        this.instantiator = instantiator;

        init();
    }

    @Override
    protected Instantiator createInstantiator() throws ServiceException {
        if (instantiator != null) {
            return instantiator;
        }
        return super.createInstantiator();
    }

    @Override
    public void setClassLoader(ClassLoader classLoader) {
        if (classLoader != null) {
            super.setClassLoader(classLoader);
        }
    }

    @Override
    public void init() {
        try {
            MockVaadinServlet servlet = (MockVaadinServlet) getServlet();
            servlet.service = this;

            if (getServlet().getServletConfig() == null) {
                ServletConfig config = Mockito.mock(ServletConfig.class);
                ServletContext context = Mockito.mock(ServletContext.class);
                Mockito.when(config.getServletContext()).thenReturn(context);

                Mockito.when(lookup.lookup(ResourceProvider.class))
                        .thenReturn(resourceProvider);
                StaticFileHandlerFactory factory = Mockito
                        .mock(StaticFileHandlerFactory.class);
                Mockito.when(lookup.lookup(StaticFileHandlerFactory.class))
                        .thenReturn(factory);
                Mockito.when(context.getAttribute(Lookup.class.getName()))
                        .thenReturn(lookup);
                getServlet().init(config);
            }
            super.init();
        } catch (ServiceException | ServletException e) {
            throw new RuntimeException(e);
        }
    }

}
