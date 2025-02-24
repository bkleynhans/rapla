/*--------------------------------------------------------------------------*
 | Copyright (C) 2014 Christopher Kohlhaas                                  |
 |                                                                          |
 | This program is free software; you can redistribute it and/or modify     |
 | it under the terms of the GNU General Public License as published by the |
 | Free Software Foundation. A copy of the license has been included with   |
 | these distribution in the COPYING file, if not go to www.fsf.org         |
 |                                                                          |
 | As a special exception, you are granted the permissions to link this     |
 | program with every library, which license fulfills the Open Source       |
 | Definition as published by the Open Source Initiative (OSI).             |
 *--------------------------------------------------------------------------*/
package org.rapla.test.util;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.webapp.WebAppContext;
import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;
import org.jboss.resteasy.plugins.server.servlet.ResteasyBootstrap;
import org.jetbrains.annotations.NotNull;
import org.rapla.RaplaResources;
import org.rapla.components.i18n.BundleManager;
import org.rapla.components.i18n.internal.AbstractBundleManager;
import org.rapla.components.i18n.server.ServerBundleManager;
import org.rapla.entities.domain.permission.PermissionExtension;
import org.rapla.entities.domain.permission.impl.RaplaDefaultPermissionImpl;
import org.rapla.entities.dynamictype.internal.StandardFunctions;
import org.rapla.entities.extensionpoints.FunctionFactory;
import org.rapla.facade.RaplaFacade;
import org.rapla.facade.client.ClientFacade;
import org.rapla.facade.internal.ClientFacadeImpl;
import org.rapla.facade.internal.FacadeImpl;
import org.rapla.framework.RaplaException;
import org.rapla.framework.RaplaLocale;
import org.rapla.framework.internal.DefaultScheduler;
import org.rapla.framework.internal.RaplaLocaleImpl;
import org.rapla.inject.Injector;
import org.rapla.logger.Logger;
import org.rapla.logger.RaplaBootstrapLogger;
import org.rapla.plugin.eventtimecalculator.DurationFunctions;
import org.rapla.plugin.eventtimecalculator.EventTimeCalculatorFactory;
import org.rapla.plugin.eventtimecalculator.EventTimeCalculatorResources;
import org.rapla.rest.client.CustomConnector;
import org.rapla.scheduler.CommandScheduler;
import org.rapla.scheduler.Promise;
import org.rapla.server.PromiseWait;
import org.rapla.server.ServerServiceContainer;
import org.rapla.server.ServerCreator;
import org.rapla.server.internal.PromiseWaitImpl;
import org.rapla.server.internal.ServerContainerContext;
import org.rapla.server.internal.ServerServiceImpl;
import org.rapla.server.internal.rest.RestApplication;
import org.rapla.storage.ImportExportManager;
import org.rapla.storage.StorageOperator;
import org.rapla.storage.dbfile.FileOperator;
import org.rapla.storage.dbrm.MyCustomConnector;
import org.rapla.storage.dbrm.RemoteAuthentificationService;
import org.rapla.storage.dbrm.RemoteConnectionInfo;
import org.rapla.storage.dbrm.RemoteOperator;
import org.rapla.storage.dbrm.RemoteStorage;
import org.rapla.storage.dbsql.DBOperator;
import org.rapla.storage.impl.DefaultRaplaLock;
import org.rapla.storage.impl.server.ImportExportManagerImpl;
import org.rapla.storage.impl.server.LocalAbstractCachableOperator;
import org.xml.sax.InputSource;

import javax.inject.Provider;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public abstract class RaplaTestCase
{
    public static Logger initLoger()
    {
        System.setProperty("jetty.home", "target/test");
        return RaplaBootstrapLogger.createRaplaLogger();
    }

    public static <T> T  waitForWithRaplaException(Promise<T> promise, int timeout) throws RaplaException
    {
        final CompletableFuture<T> future = new CompletableFuture<>();
        promise.handle((t, ex) ->
        {
            if (ex != null)
            {
                future.completeExceptionally(ex);
            }
            else
            {
                future.complete(t);
            }
            return t;
        });
        try
        {
            T t = future.get(timeout, TimeUnit.MILLISECONDS);
            return t;
        }
        catch (Exception ex)
        {
            final Throwable cause = ex.getCause();
            if ( cause instanceof RaplaException)
            {
                throw (RaplaException)cause;
            }
            if ( cause instanceof RuntimeException)
            {
                throw (RuntimeException)cause;
            }
            if ( cause instanceof Error)
            {
                throw (Error)cause;
            }
            throw new RaplaException(ex);
        }
    }

    public static class ServerContext
    {
        ServerServiceContainer container;
        Server server;

        public Server getServer()
        {
            return server;
        }

        public ServerServiceContainer getServiceContainer()
        {
            return container;
        }
    }

    public static ServerContext createServerContext( Logger logger, String xmlFile, int port) throws Exception
    {
        ServerContainerContext containerContext = new ServerContainerContext();
        containerContext.addFileDatasource("raplafile",getTestDataFile(xmlFile));
        return createServerContext(logger, containerContext, port);
    }

    @NotNull public static ServerContext createServerContext(Logger logger, ServerContainerContext containerContext, int port) throws Exception
    {
        FileOperator.setDefaultFileIO(new VoidFileIO());
        final ServerCreator.ServerContext serverContext = ServerCreator.create(logger, containerContext);
        final ServerServiceContainer serviceContainer = serverContext.getServiceContainer();
        Injector injector = serverContext.getMembersInjector();
        final Server server = createServer(serviceContainer, injector, port);
        ServerContext result = new ServerContext();
        result.server =  server;
        result.container = serviceContainer;
        return result;
    }

    private static Server createServer(final ServerServiceContainer serverService,Injector membersInjector,int port) throws Exception
    {
        final ServerServiceImpl serverServiceImpl = (ServerServiceImpl) serverService;
        File webappFolder = new File("test");
        Server jettyServer = new Server(port);
        String contextPath = "rapla";
        WebAppContext context = new WebAppContext(jettyServer, contextPath, "/");
        //        context.addFilter(org.rapla.server.HTTPMethodOverrideFilter.class, "/rapla/*", null);
        context.addEventListener(new ResteasyBootstrap());
        final Filter filter = new Filter()
        {
            @Override
            public void init(FilterConfig filterConfig) throws ServletException
            {
                // do not init context as given from outside
            }

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
            {
                request.setAttribute(Injector.class.getCanonicalName(), membersInjector);
                chain.doFilter(request, response);
            }

            @Override
            public void destroy()
            {

            }
        };
        final FilterHolder holder = new FilterHolder(filter);
        context.addFilter(holder, "/*", EnumSet.allOf(DispatcherType.class));
        context.setInitParameter("resteasy.servlet.mapping.prefix", "/rapla");
        context.setInitParameter("resteasy.use.builtin.providers", "false");
        context.setInitParameter("javax.ws.rs.Application", RestApplication.class.getCanonicalName());
        context.setResourceBase(webappFolder.getAbsolutePath());
        context.setMaxFormContentSize(64000000);

        final ServletHolder servletHolder = new ServletHolder(HttpServletDispatcher.class);
        servletHolder.setServlet(new HttpServletDispatcher());
        context.addServlet(servletHolder, "/rapla/*");
        jettyServer.start();
        Handler[] childHandlers = context.getChildHandlersByClass(ServletHandler.class);
        final ServletHandler childHandler = (ServletHandler) childHandlers[0];
        final ServletHolder[] servlets = childHandler.getServlets();
        ServletHolder servlet = servlets[0];

        URL server = new URL("http://127.0.0.1:"+port+"/rapla/auth");
        HttpURLConnection connection = (HttpURLConnection)server.openConnection();
        int timeout = 10000;
        int interval = 200;
        for ( int i=0;i<timeout / interval;i++)
        {
            try
            {
                connection.connect();
            }
            catch (ConnectException ex) {
                Thread.sleep(interval);
            }
        }

        return jettyServer;
    }

    public static ServerServiceContainer createServiceContainer( Logger logger, String xmlFile) throws Exception
    {
        ServerContainerContext containerContext = new ServerContainerContext();
        containerContext.addFileDatasource("raplafile",getTestDataFile(xmlFile));
        FileOperator.setDefaultFileIO( new VoidFileIO());
        final ServerCreator.ServerContext serverContext = ServerCreator.create(logger, containerContext);
        final ServerServiceContainer serverServiceContainer = serverContext.getServiceContainer();
        return serverServiceContainer;
    }


    public static String getTestDataFile(String xmlFile) throws RaplaException
    {
        final URL resource = RaplaTestCase.class.getResource(xmlFile);
        try
        {
            return new File(resource.toURI()).getAbsolutePath();
        }
        catch (URISyntaxException e)
        {
            throw new RaplaException(e);
        }
    }

    public static ClientFacade createSimpleSimpsonsWithHomer() throws RaplaException
    {
        final Logger raplaLogger = RaplaBootstrapLogger.createRaplaLogger();
        RaplaFacade facade = RaplaTestCase.createFacadeWithFile(raplaLogger,"/testdefault.xml");
        RaplaResources i18n = ((FacadeImpl)facade).getI18n();
        ClientFacade clientFacade = new ClientFacadeImpl( facade,raplaLogger, i18n );
        clientFacade.login("homer","duffs".toCharArray());
        return clientFacade;
    }

    public static RaplaFacade createFacadeWithFile(Logger logger, String xmlFile) throws RaplaException
    {
        String resolvedPath = getTestDataFile(xmlFile);
        return _createFacadeWithFile(logger, resolvedPath, new VoidFileIO());
    }

    public static RaplaFacade createFacadeWithFile(Logger logger, String resolvedPath, FileOperator.FileIO fileIO) throws RaplaException
    {
        return _createFacadeWithFile(logger, resolvedPath, fileIO);
    }

    private static FacadeImpl _createFacadeWithFile(Logger logger, String resolvedPath, FileOperator.FileIO fileIO) throws RaplaException
    {

        BundleManager bundleManager = new ServerBundleManager();
        RaplaResources i18n = new RaplaResources(bundleManager);

        final DefaultScheduler scheduler = new DefaultScheduler(logger);
        RaplaLocale raplaLocale = new RaplaLocaleImpl(bundleManager);
        AtomicReference<RaplaFacade> facadeReference = new AtomicReference<RaplaFacade>();
        Provider<RaplaFacade> facadeProvider = new Provider<RaplaFacade>()
        {
            @Override
            public RaplaFacade get()
            {
                return facadeReference.get();
            }
        };
        Map<String, FunctionFactory> functionFactoryMap = createFactoryMap( facadeProvider,bundleManager,logger);

        RaplaDefaultPermissionImpl defaultPermission = new RaplaDefaultPermissionImpl();
        Set<PermissionExtension> permissionExtensions = new LinkedHashSet<>();
        permissionExtensions.add(defaultPermission);
        PromiseWait promiseWait = new PromiseWaitImpl(logger);
        FileOperator operator = new FileOperator(logger, promiseWait,i18n, raplaLocale, scheduler, functionFactoryMap, resolvedPath,
                permissionExtensions);
        FacadeImpl facade = new FacadeImpl(i18n, scheduler, logger);
        facadeReference.set( facade);
        facade.setOperator(operator);
        operator.setFileIO(fileIO);
        operator.connect();
        return facade;
    }

    static private Map<String, FunctionFactory> createFactoryMap(Provider<RaplaFacade> facadeProvider,BundleManager bundleManager,Logger logger)
    {
        Map<String,FunctionFactory> map = new HashMap<String,FunctionFactory>();
        RaplaLocale raplaLocale = new RaplaLocaleImpl(bundleManager);
        map.put(StandardFunctions.NAMESPACE,new StandardFunctions(raplaLocale));

        EventTimeCalculatorResources resources = new EventTimeCalculatorResources(new ServerBundleManager());

        EventTimeCalculatorFactory eventTimeCalculatorFactory = new EventTimeCalculatorFactory(facadeProvider, logger, resources);
        map.put(DurationFunctions.NAMESPACE,new DurationFunctions(eventTimeCalculatorFactory));
        return map;
    }

    static public  void dispose(RaplaFacade facade) throws RaplaException
    {
        if ( facade == null)
        {
            return;
        }
        final StorageOperator operator = facade.getOperator();
        operator.disconnect();
        if ( operator instanceof LocalAbstractCachableOperator)
        {
            final DefaultScheduler scheduler = (DefaultScheduler)((LocalAbstractCachableOperator) operator).getScheduler();
            scheduler.dispose();
        }
        else if ( operator instanceof RemoteOperator)
        {
            final CommandScheduler scheduler = ((RemoteOperator) operator).getScheduler();
            if ( scheduler instanceof DefaultScheduler )
            {
                ((DefaultScheduler)scheduler).dispose();
            }
            if ( scheduler instanceof org.rapla.scheduler.client.gwt.GwtCommandScheduler)
            {
                //((org.rapla.scheduler.client.gwt.GwtCommandScheduler)scheduler).dispose();
            }
        }

    }



    static class MyImportExportManagerProvider implements Provider<ImportExportManager>
    {

        private ImportExportManager manager;

        @Override public ImportExportManager get()
        {
            return manager;
        }

        public void setManager(ImportExportManager manager)
        {
            this.manager = manager;
        }
    }

    public static RaplaFacade createFacadeWithDatasource(Logger logger, javax.sql.DataSource dataSource,String xmlFile) throws RaplaException
    {
        AbstractBundleManager bundleManager = new ServerBundleManager();
        RaplaResources i18n = new RaplaResources(bundleManager);

        final DefaultScheduler scheduler = new DefaultScheduler(logger);
        RaplaLocale raplaLocale = new RaplaLocaleImpl(bundleManager);

        Map<String, FunctionFactory> functionFactoryMap = new HashMap<String, FunctionFactory>();
        StandardFunctions functions = new StandardFunctions(raplaLocale);
        functionFactoryMap.put(StandardFunctions.NAMESPACE, functions);

        RaplaDefaultPermissionImpl defaultPermission = new RaplaDefaultPermissionImpl();
        Set<PermissionExtension> permissionExtensions = new LinkedHashSet<>();
        permissionExtensions.add(defaultPermission);

        MyImportExportManagerProvider importExportManager = new MyImportExportManagerProvider();
        PromiseWait promiseWait = new PromiseWaitImpl(logger);
        DBOperator operator = new DBOperator(logger, promiseWait,i18n, raplaLocale, scheduler, functionFactoryMap, importExportManager,dataSource,
                DefaultPermissionControllerSupport.getPermissionExtensions());
        if ( xmlFile != null)
        {
            String resolvedPath = getTestDataFile(xmlFile);
            FileOperator fileOperator = new FileOperator(logger, promiseWait, i18n, raplaLocale, scheduler, functionFactoryMap, resolvedPath,
                    DefaultPermissionControllerSupport.getPermissionExtensions());
            fileOperator.setFileIO(new VoidFileIO());
            importExportManager.setManager( new ImportExportManagerImpl(logger,fileOperator,operator));
            operator.removeAll();
        }
        FacadeImpl facade = new FacadeImpl(i18n, scheduler, logger);
        facade.setOperator(operator);
        operator.connect();
        return facade;
    }

    public static Provider<ClientFacade> createFacadeWithRemote(final Logger logger, int port) throws RaplaException
    {
        final String serverURL = "http://localhost:" + port + "/rapla";


        final AbstractBundleManager bundleManager = new ServerBundleManager();
        final RaplaResources i18n = new RaplaResources(bundleManager);

        final CommandScheduler scheduler = new DefaultScheduler(logger);
        final RaplaLocale raplaLocale = new RaplaLocaleImpl(bundleManager);


        final Map<String, FunctionFactory> functionFactoryMap = new HashMap<String, FunctionFactory>();
        final StandardFunctions functions = new StandardFunctions(raplaLocale);
        functionFactoryMap.put(StandardFunctions.NAMESPACE, functions);
        final RaplaDefaultPermissionImpl defaultPermission = new RaplaDefaultPermissionImpl();
        Set<PermissionExtension> permissionExtensions = new LinkedHashSet<>();
        permissionExtensions.add(defaultPermission);
        Provider<ClientFacade> clientFacadeProvider = new Provider<ClientFacade>()
        {
            @Override public ClientFacade get()
            {
                RemoteConnectionInfo connectionInfo = new RemoteConnectionInfo();
                connectionInfo.setServerURL(serverURL);
                //final ConnectInfo connectInfo = new ConnectInfo("homer", "duffs".toCharArray());
                connectionInfo.setReconnectInfo(null);
                AtomicReference<RemoteAuthentificationService> serviceAtomicReference = new AtomicReference<>();
                Provider<RemoteAuthentificationService> authenticationProvider = new Provider<RemoteAuthentificationService>()
                {
                    @Override
                    public RemoteAuthentificationService get()
                    {
                        return serviceAtomicReference.get();
                    }
                };
                MyCustomConnector customConnector = new MyCustomConnector(connectionInfo, () ->i18n, authenticationProvider,scheduler, logger);
                RemoteAuthentificationService remoteAuthentificationService = getRemotService(RemoteAuthentificationService.class,customConnector);
                serviceAtomicReference.set( remoteAuthentificationService);
                RemoteStorage remoteStorage = getRemotService( RemoteStorage.class, customConnector);
                final DefaultRaplaLock lockManager = new DefaultRaplaLock(logger);
                final LinkedHashSet<PermissionExtension> permissionExtensionsList = DefaultPermissionControllerSupport.getPermissionExtensions();
                RemoteOperator remoteOperator = new RemoteOperator(logger, i18n, raplaLocale, scheduler, functionFactoryMap, remoteAuthentificationService,
                        remoteStorage, connectionInfo, permissionExtensionsList, lockManager);
                FacadeImpl facade = new FacadeImpl(i18n, scheduler, logger);
                ClientFacadeImpl clientFacade = new ClientFacadeImpl(facade,logger,i18n);
                clientFacade.setOperator(remoteOperator);
                return clientFacade;
            }
        };
        return clientFacadeProvider;
    }

    public  static <T> T getRemotService(Class<T> interfaceClass,CustomConnector customConnector)
    {
        try
        {
            final String className = interfaceClass.getCanonicalName() + "_JavaJsonProxy";
            Object remoteService = Class.forName(className).getConstructor(CustomConnector.class).newInstance(customConnector);
            return interfaceClass.cast( remoteService);
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException(e);
        }

    }

    public static class VoidFileIO extends FileOperator.DefaultFileIO
    {
        @Override public void write(FileOperator.RaplaWriter writer, URI storageURL) throws IOException
        {
            //super.write(writer, storageURL);
        }

        @Override public InputSource getInputSource(URI storageURL) throws IOException
        {
            return super.getInputSource(storageURL);
        }
    }

    /*
    public RaplaTestCase()
    {
        try
        {
            new File("temp").mkdir();
            File testFolder = new File(TEST_FOLDER_NAME);
            System.setProperty("jetty.home", testFolder.getPath());
            testFolder.mkdir();
            IOUtil.copyReservations(TEST_SRC_FOLDER_NAME + "/test.xconf", TEST_FOLDER_NAME + "/test.xconf");
            //IOUtil.copyReservations( "test-src/test.xlog", TEST_FOLDER_NAME + "/test.xlog" );
        }
        catch (IOException ex)
        {
            throw new RuntimeException("Can't initialize config-files: " + ex.getMessage());
        }
        try
        {
            Class<?> forName = RaplaTestCase.class.getClassLoader().loadClass("org.slf4j.bridge.SLF4JBridgeHandler");
            forName.getMethod("removeHandlersForRootLogger", new Class[] {}).invoke(null, new Object[] {});
            forName.getMethod("install", new Class[] {}).invoke(null, new Object[] {});
        }
        catch (Exception ex)
        {
            getLogger().warn("Can't install logging bridge  " + ex.getMessage());
            // Todo bootstrap log
        }

    }


    public void copyDataFile(String testFile) throws IOException
    {
        try
        {
            IOUtil.copyReservations(testFile, TEST_FOLDER_NAME + "/test.xml");
        }
        catch (IOException ex)
        {
            throw new IOException("Failed to copyReservations TestFile '" + testFile + "': " + ex.getMessage());
        }
    }

    protected <T> T getService(Class<T> role) throws RaplaException
    {
        return null;
    }

    protected SerializableDateTimeFormat formater()
    {
        return new SerializableDateTimeFormat();
    }

    protected Logger getLogger()
    {
        return logger;
    }

    protected void setUp(String testFile) throws Exception
    {
        ErrorDialog.THROW_ERROR_DIALOG_EXCEPTION = true;

        //        URL configURL = new URL("file:./" + TEST_FOLDER_NAME + "/test.xconf");
        //env.setConfigURL( configURL);
        copyDataFile(TEST_SRC_FOLDER_NAME + "/" + testFile);
        RaplaFacade facade = getFacade();
        facade.login("homer", "duffs".toCharArray());
    }

    @Before protected void setUp() throws Exception
    {
        setUp("testdefault.xml");
    }

    protected RaplaFacade getFacade() throws RaplaException
    {
        return getService(RaplaFacade.class);
    }

    protected RaplaLocale getRaplaLocale() throws RaplaException
    {
        return getService(RaplaLocale.class);
    }

    protected void tearDown() throws Exception
    {
        if (raplaContainer != null)
            raplaContainer.dispose();
    }
*/
}
