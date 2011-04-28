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
// ========================================================================
package org.eclipse.jetty.osgi.boot.utils.internal;

import java.util.ArrayList;
import java.util.List;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.service.packageadmin.PackageAdmin;

/**
 * When the PackageAdmin service is activated we can look for the fragments
 * attached to this bundle and "activate" them.
 * 
 */
public class PackageAdminServiceTracker implements ServiceListener
{
    private BundleContext _context;

    private List<BundleActivator> _activatedFragments = new ArrayList<BundleActivator>();
    private boolean _fragmentsWereActivated = false;
    public static PackageAdminServiceTracker INSTANCE = null;

    public PackageAdminServiceTracker(BundleContext context)
    {
        INSTANCE = this;
        _context = context;
        if (!setup())
        {
            try
            {
                _context.addServiceListener(this,"(objectclass=" + PackageAdmin.class.getName() + ")");
            }
            catch (InvalidSyntaxException e)
            {
                e.printStackTrace(); // won't happen
            }
        }
    }

    /**
     * @return true if the fragments were activated by this method.
     */
    private boolean setup()
    {
        ServiceReference sr = _context.getServiceReference(PackageAdmin.class.getName());
        _fragmentsWereActivated = sr != null;
        if (sr != null)
            invokeFragmentActivators(sr);
        return _fragmentsWereActivated;
    }

    /**
     * Invokes the optional BundleActivator in each fragment. By convention the
     * bundle activator for a fragment must be in the package that is defined by
     * the symbolic name of the fragment and the name of the class must be
     * 'FragmentActivator'.
     * 
     * @param event
     *            The <code>ServiceEvent</code> object.
     */
    public void serviceChanged(ServiceEvent event)
    {
        if (event.getType() == ServiceEvent.REGISTERED)
        {
            invokeFragmentActivators(event.getServiceReference());
        }
    }
    
    /**
     * Helper to access the PackageAdmin and return the fragments hosted by a bundle.
     * when we drop the support for the older versions of OSGi, we will stop using the PackageAdmin
     * service.
     * @param bundle
     * @return
     */
    public Bundle[] getFragments(Bundle bundle)
    {
        ServiceReference sr = _context.getServiceReference(PackageAdmin.class.getName());
        if (sr == null)
        {//we should never be here really.
            return null;
        }
        PackageAdmin admin = (PackageAdmin)_context.getService(sr);
        return admin.getFragments(bundle);
    }

    private void invokeFragmentActivators(ServiceReference sr)
    {
        PackageAdmin admin = (PackageAdmin)_context.getService(sr);
        Bundle[] fragments = admin.getFragments(_context.getBundle());
        if (fragments == null)
        {
            return;
        }
        for (Bundle frag : fragments)
        {
            // find a convention to look for a class inside the fragment.
            try
            {
                String fragmentActivator = frag.getSymbolicName() + ".FragmentActivator";
                Class<?> c = Class.forName(fragmentActivator);
                if (c != null)
                {
                    BundleActivator bActivator = (BundleActivator)c.newInstance();
                    bActivator.start(_context);
                    _activatedFragments.add(bActivator);
                }
            }
            catch (NullPointerException e)
            {
                // e.printStackTrace();
            }
            catch (InstantiationException e)
            {
                // e.printStackTrace();
            }
            catch (IllegalAccessException e)
            {
                // e.printStackTrace();
            }
            catch (ClassNotFoundException e)
            {
                // e.printStackTrace();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    public void stop()
    {
        INSTANCE = null;
        for (BundleActivator fragAct : _activatedFragments)
        {
            try
            {
                fragAct.stop(_context);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

}
