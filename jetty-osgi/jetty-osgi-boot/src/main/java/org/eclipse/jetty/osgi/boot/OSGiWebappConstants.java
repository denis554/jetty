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

/**
 * Name of the service properties for a ContextHandler that configure a webapp deployed on jetty OSGi.
 */
public class OSGiWebappConstants
{
    /** url scheme to deploy war file as bundled webapp */
    public static final String RFC66_WAR_URL_SCHEME = "war";

    /**
     * Name of the header that defines the context path for the embedded webapp.
     */
    public static final String RFC66_WEB_CONTEXTPATH = "Web-ContextPath";

    /**
     * Name of the header that defines the path to the folder where the jsp
     * files are extracted.
     */
    public static final String RFC66_JSP_EXTRACT_LOCATION = "Jsp-ExtractLocation";

    /** Name of the servlet context attribute that points to the bundle context. */
    public static final String RFC66_OSGI_BUNDLE_CONTEXT = "osgi-bundlecontext";

    /** Name of the servlet context attribute that points to the bundle object.
     * We can't always rely on the bundle-context as there might be no such thing. */
    public static final String JETTY_OSGI_BUNDLE = "osgi-bundle";

    /** List of relative pathes within the bundle to the jetty context files. */
    public static final String JETTY_CONTEXT_FILE_PATH = "Jetty-ContextFilePath";

    /** path within the bundle to the folder that contains the basic resources. */
    public static final String JETTY_WAR_FOLDER_PATH = "Jetty-WarFolderPath";

    /** path within a fragment hosted by a web-bundle to a folder that contains basic resources.
     * the path is appended to the lookup path where jetty locates static resources */
    public static final String JETTY_WAR_FRAGMENT_FOLDER_PATH = "Jetty-WarFragmentFolderPath";

    /** path within a fragment hosted by a web-bundle to a folder that contains basic resources.
     * The path is prefixed to the lookup path where jetty locates static resources:
     * this will override static resources with the same name in the web-bundle. */
    public static final String JETTY_WAR_PATCH_FRAGMENT_FOLDER_PATH = "Jetty-WarPatchFragmentFolderPath";

    // OSGi ContextHandler service properties.
    /** web app context path */
    public static final String SERVICE_PROP_CONTEXT_PATH = "contextPath";

    /** Path to the web application base folderr */
    public static final String SERVICE_PROP_WAR = "war";

    /** Extra classpath */
    public static final String SERVICE_PROP_EXTRA_CLASSPATH = "extraClasspath";

    /** jetty context file path */
    public static final String SERVICE_PROP_CONTEXT_FILE_PATH = "contextFilePath";

    /** web.xml file path */
    public static final String SERVICE_PROP_WEB_XML_PATH = "webXmlFilePath";

    /** defaultweb.xml file path */
    public static final String SERVICE_PROP_DEFAULT_WEB_XML_PATH = "defaultWebXmlFilePath";

    /**
     * path to the base folder that overrides the computed bundle installation
     * location if not null useful to install webapps or jetty context files
     * that are in fact not embedded in a bundle
     */
    public static final String SERVICE_PROP_BUNDLE_INSTALL_LOCATION_OVERRIDE = "thisBundleInstall";
    
    /**
     * Comma separated list of bundles that contain tld file used by the webapp.
     */
    public static final String REQUIRE_TLD_BUNDLE = "Require-TldBundle";
    /**
     * Comma separated list of bundles that contain tld file used by the webapp.
     * Both the name of the manifest header and the name of the service property.
     */
    public static final String SERVICE_PROP_REQUIRE_TLD_BUNDLE = REQUIRE_TLD_BUNDLE;
}
