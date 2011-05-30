// ========================================================================
// Copyright (c) 2009 Intalio, Inc.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// Contributors:
//    Hugues Malphettes - initial API and implementation
// ========================================================================
package org.eclipse.jetty.osgi.boot;

import java.util.Dictionary;
import java.util.Hashtable;

import org.eclipse.jetty.osgi.boot.internal.serverfactory.DefaultJettyAtJettyHomeHelper;
import org.eclipse.jetty.osgi.boot.internal.serverfactory.JettyServerServiceTracker;
import org.eclipse.jetty.osgi.boot.internal.webapp.IWebBundleDeployerHelper;
import org.eclipse.jetty.osgi.boot.internal.webapp.JettyContextHandlerServiceTracker;
import org.eclipse.jetty.osgi.boot.internal.webapp.WebBundleTrackerCustomizer;
import org.eclipse.jetty.osgi.boot.utils.internal.PackageAdminServiceTracker;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.webapp.WebAppContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.BundleTracker;

/**
 * Experiment: bootstrap jetty's complete distrib from an OSGi bundle. Progress:
 * <ol>
 * <li>basic servlet [ok]</li>
 * <li>basic jetty.xml [ok]</li>
 * <li>basic jetty.xml and jetty-plus.xml [ok]</li>
 * <li>basic jsp [ok with modifications]
 * <ul>
 * <li>Needed to modify the headers of jdt.core-3.1.1 so that its dependency on
 * eclipse.runtime, eclipse.resources and eclipse.text are optional. Also we
 * should depend on the latest jdt.core from eclipse-3.5 not from eclipse-3.1.1
 * although that will require actual changes to jasper as some internal APIs of
 * jdt.core have changed.</li>
 * <li>Modifications to org.mortbay.jetty.jsp-2.1-glassfish: made all imports to
 * ant, xalan and sun packages optional.</li>
 * </ul>
 * </li>
 * <li>jsp with tag-libs [ok]</li>
 * <li>test-jndi with atomikos and derby inside ${jetty.home}/lib/ext [ok]</li>
 * </ul>
 */
public class JettyBootstrapActivator implements BundleActivator
{

    private static JettyBootstrapActivator INSTANCE = null;

    public static JettyBootstrapActivator getInstance()
    {
        return INSTANCE;
    }

    private ServiceRegistration _registeredServer;
    private Server _server;
    private JettyContextHandlerServiceTracker _jettyContextHandlerTracker;
    private PackageAdminServiceTracker _packageAdminServiceTracker;
    private BundleTracker _webBundleTracker;
    private BundleContext _bundleContext;
    
//    private ServiceRegistration _jettyServerFactoryService;
    private JettyServerServiceTracker _jettyServerServiceTracker;
    

    /**
     * Setup a new jetty Server, registers it as a service. Setup the Service
     * tracker for the jetty ContextHandlers that are in charge of deploying the
     * webapps. Setup the BundleListener that supports the extender pattern for
     * the jetty ContextHandler.
     * 
     * @param context
     */
    public void start(BundleContext context) throws Exception
    {
        INSTANCE = this;
        _bundleContext = context;

        // track other bundles and fragments attached to this bundle that we
        // should activate.
        _packageAdminServiceTracker = new PackageAdminServiceTracker(context);

    	_jettyServerServiceTracker = new JettyServerServiceTracker();
        context.addServiceListener(_jettyServerServiceTracker,"(objectclass=" + Server.class.getName() + ")");

        //Register the Jetty Server Factory as a ManagedServiceFactory:
//          Properties jettyServerMgdFactoryServiceProps = new Properties(); 
//          jettyServerMgdFactoryServiceProps.put("pid", OSGiWebappConstants.MANAGED_JETTY_SERVER_FACTORY_PID);
//          _jettyServerFactoryService = context.registerService(
//          		ManagedServiceFactory.class.getName(),  new JettyServersManagedFactory(),
//          		jettyServerMgdFactoryServiceProps);
    
        _jettyContextHandlerTracker = new JettyContextHandlerServiceTracker(_jettyServerServiceTracker);

        // the tracker in charge of the actual deployment
        // and that will configure and start the jetty server.
        context.addServiceListener(_jettyContextHandlerTracker,"(objectclass=" + ContextHandler.class.getName() + ")");

        //see if we shoult start a default jetty instance right now.
        DefaultJettyAtJettyHomeHelper.startJettyAtJettyHome(context);
        
        // now ready to support the Extender pattern:        
        _webBundleTracker = new BundleTracker(context,
        		Bundle.ACTIVE | Bundle.STOPPING, new WebBundleTrackerCustomizer());
        _webBundleTracker.open();
        
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
     */
    public void stop(BundleContext context) throws Exception
    {
        try
        {
        	
        	if (_webBundleTracker != null)
        	{
        		_webBundleTracker.close();
        		_webBundleTracker = null;
        	}
            if (_jettyContextHandlerTracker != null)
            {
                _jettyContextHandlerTracker.stop();
                context.removeServiceListener(_jettyContextHandlerTracker);
                _jettyContextHandlerTracker = null;
            }
            if (_jettyServerServiceTracker != null)
            {
            	_jettyServerServiceTracker.stop();
                context.removeServiceListener(_jettyServerServiceTracker);
                _jettyServerServiceTracker = null;
            }
            if (_packageAdminServiceTracker != null)
            {
                _packageAdminServiceTracker.stop();
                context.removeServiceListener(_packageAdminServiceTracker);
                _packageAdminServiceTracker = null;
            }
            if (_registeredServer != null)
            {
                try
                {
                    _registeredServer.unregister();
                }
                catch (IllegalArgumentException ill)
                {
                    // already unregistered.
                }
                finally
                {
                	_registeredServer = null;
                }
            }
//        	if (_jettyServerFactoryService != null)
//        	{
//                try
//                {
//                	_jettyServerFactoryService.unregister();
//                }
//                catch (IllegalArgumentException ill)
//                {
//                    // already unregistered.
//                }
//                finally
//                {
//                	_jettyServerFactoryService = null;
//                }
//        	}

        }
        finally
        {
            if (_server != null)
            {
            	_server.stop();
            }
            INSTANCE = null;
        }
    }

    /**
     * Helper method that creates a new org.jetty.webapp.WebAppContext and
     * registers it as an OSGi service. The tracker
     * {@link JettyContextHandlerServiceTracker} will do the actual deployment.
     * 
     * @param contributor
     *            The bundle
     * @param webappFolderPath
     *            The path to the root of the webapp. Must be a path relative to
     *            bundle; either an absolute path.
     * @param contextPath
     *            The context path. Must start with "/"
     * @throws Exception
     */
    public static void registerWebapplication(Bundle contributor, String webappFolderPath, String contextPath) throws Exception
    {
    	checkBundleActivated();
    	WebAppContext contextHandler = new WebAppContext();
        Dictionary dic = new Hashtable();
        dic.put(OSGiWebappConstants.SERVICE_PROP_WAR,webappFolderPath);
        dic.put(OSGiWebappConstants.SERVICE_PROP_CONTEXT_PATH,contextPath);
        String requireTldBundle = (String)contributor.getHeaders().get(OSGiWebappConstants.REQUIRE_TLD_BUNDLE);
        if (requireTldBundle != null) {
        	dic.put(OSGiWebappConstants.SERVICE_PROP_REQUIRE_TLD_BUNDLE, requireTldBundle);
        }
        contributor.getBundleContext().registerService(ContextHandler.class.getName(),contextHandler,dic);
    }

    /**
     * Helper method that creates a new org.jetty.webapp.WebAppContext and
     * registers it as an OSGi service. The tracker
     * {@link JettyContextHandlerServiceTracker} will do the actual deployment.
     * 
     * @param contributor
     *            The bundle
     * @param webappFolderPath
     *            The path to the root of the webapp. Must be a path relative to
     *            bundle; either an absolute path.
     * @param contextPath
     *            The context path. Must start with "/"
     * @param dic
     *        TODO: parameter description
     * @throws Exception
     */
    public static void registerWebapplication(Bundle contributor, String webappFolderPath, String contextPath, Dictionary<String, String> dic) throws Exception
    {
    	checkBundleActivated();
        WebAppContext contextHandler = new WebAppContext();
        dic.put(OSGiWebappConstants.SERVICE_PROP_WAR,webappFolderPath);
        dic.put(OSGiWebappConstants.SERVICE_PROP_CONTEXT_PATH,contextPath);
        contributor.getBundleContext().registerService(ContextHandler.class.getName(),contextHandler,dic);
    }

    /**
     * Helper method that creates a new skeleton of a ContextHandler and
     * registers it as an OSGi service. The tracker
     * {@link JettyContextHandlerServiceTracker} will do the actual deployment.
     * 
     * @param contributor
     *            The bundle that registers a new context
     * @param contextFilePath
     *            The path to the file inside the bundle that defines the
     *            context.
     * @throws Exception
     */
    public static void registerContext(Bundle contributor, String contextFilePath) throws Exception
    {
        registerContext(contributor,contextFilePath,new Hashtable<String, String>());
    }

    /**
     * Helper method that creates a new skeleton of a ContextHandler and
     * registers it as an OSGi service. The tracker
     * {@link JettyContextHandlerServiceTracker} will do the actual deployment.
     * 
     * @param contributor
     *            The bundle that registers a new context
     * @param contextFilePath
     *            The path to the file inside the bundle that defines the
     *            context.
     * @param dic
     *          TODO: parameter description
     * @throws Exception
     */
    public static void registerContext(Bundle contributor, String contextFilePath, Dictionary<String, String> dic) throws Exception
    {
    	checkBundleActivated();
        ContextHandler contextHandler = new ContextHandler();
        dic.put(OSGiWebappConstants.SERVICE_PROP_CONTEXT_FILE_PATH,contextFilePath);
        dic.put(IWebBundleDeployerHelper.INTERNAL_SERVICE_PROP_UNKNOWN_CONTEXT_HANDLER_TYPE,Boolean.TRUE.toString());
        contributor.getBundleContext().registerService(ContextHandler.class.getName(),contextHandler,dic);
    }

    public static void unregister(String contextPath)
    {
        // todo
    }
    
    /**
     * Since org.eclipse.jetty.osgi.boot does not have a lazy activation policy
     * when one fo the static methods to register a webapp is called we should make sure that
     * the bundle is started.
     */
    private static void checkBundleActivated()
    {
        if (INSTANCE == null)
        {
            Bundle thisBundle = FrameworkUtil.getBundle(JettyBootstrapActivator.class);
            try
            {
                thisBundle.start();
            }
            catch (BundleException e)
            {
                // nevermind.
            }
        }
    }
    
    /**
     * @return The bundle context for this bundle.
     */
    public static BundleContext getBundleContext()
    {
        checkBundleActivated();
        return INSTANCE._bundleContext;
    }
    

}
