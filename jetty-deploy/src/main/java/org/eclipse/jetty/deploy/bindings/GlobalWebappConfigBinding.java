package org.eclipse.jetty.deploy.bindings;
//========================================================================
//Copyright (c) Webtide LLC
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//
//The Eclipse Public License is available at 
//http://www.eclipse.org/legal/epl-v10.html
//
//The Apache License v2.0 is available at
//http://www.apache.org/licenses/LICENSE-2.0.txt
//
//You may elect to redistribute this code under either of these licenses. 
//========================================================================

import java.net.URL;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppLifeCycle;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.FileResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlConfiguration;

/**
 * Provides a way of globally setting various aspects of webapp contexts.
 * 
 * Adding this binding will allow the user to arbitrarily apply a file of 
 * jetty-web.xml like settings to a webapp context.
 * 
 * Example usage would be:
 * - adding a server or system class setting to all webapp contexts
 * - adding an override descriptor 
 * 
 * Note: Currently properties from startup will not be available for 
 * reference.
 *
 */
public class GlobalWebappConfigBinding implements AppLifeCycle.Binding
{

    private String _jettyXml;

    public String getJettyXml()
    {
        return _jettyXml;
    }

    public void setJettyXml(String jettyXml)
    {
        this._jettyXml = jettyXml;
    }

    public String[] getBindingTargets()
    {
        return new String[]  { "deploying" };
    }

    public void processBinding(Node node, App app) throws Exception
    {
        ContextHandler handler = app.getContextHandler();
        if (handler == null)
        {
            throw new NullPointerException("No Handler created for App: " + app);
        }

        if (handler instanceof WebAppContext)
        {
            WebAppContext context = (WebAppContext)handler;

            if (Log.isDebugEnabled())
            {
                Log.debug("Binding: Configuring webapp context with global settings from: " + _jettyXml);
            }

            if ( _jettyXml == null )
            {
                Log.warn("Binding: global context binding is enabled but no jetty-web.xml file has been registered");
            }
            
            Resource globalContextSettings = new FileResource(new URL(_jettyXml));

            if (globalContextSettings.exists())
            {
                XmlConfiguration jettyXmlConfig = new XmlConfiguration(globalContextSettings.getInputStream());

                jettyXmlConfig.configure(context);
            }
            else
            {
                Log.info("Binding: Unable to locate global webapp context settings: " + _jettyXml);
            }
        }
    }

}
