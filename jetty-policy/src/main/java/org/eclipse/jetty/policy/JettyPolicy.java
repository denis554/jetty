package org.eclipse.jetty.policy;

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
//http://www.opensource.org/licenses/apache2.0.php
//
//You may elect to redistribute this code under either of these licenses. 
//========================================================================

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.security.AccessControlException;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Policy;
import java.security.Principal;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.policy.loader.DefaultPolicyLoader;
import org.eclipse.jetty.util.Scanner;


/**
 * Policy implementation that will load a set of policy files and manage the mapping of permissions and protection domains
 * 
 * The reason I created this class and added this mechanism are:
 * 
 * 1) I wanted a way to be able to follow the startup mechanic that jetty uses with jetty-start using OPTIONS=policy,default to be able to startup a security manager and policy implementation without have to rely on the existing JVM cli options 2)
 * establish a starting point to add on further functionality to permissions based security with jetty like jmx enabled permission tweaking or runtime creation and specification of policies for specific webapps 3) I wanted to have support for specifying
 * multiple policy files to source permissions from
 * 
 * Possible additions are: - directories of policy file support - jmx enabled a la #2 above - proxying of system security policy where we can proxy access to the system policy should the jvm have been started with one, I had support for this but ripped it
 * out to add in again later - merging of protection domains if process multiple policy files that declare permissions for the same codebase - an xml policy file parser, had originally added this using modello but tore it out since it would have been a
 * nightmare to get its dependencies through IP validation, could do this with jvm xml parser instead sometime - check performance of the synch'd map I am using for the protection domain mapping
 */
public class JettyPolicy extends Policy
{
    private static boolean __DEBUG = false;

    // Policy files that are actively managed by the aggregate policy mechanism
    private final Set<String> _policies;

    private final Set<PolicyBlock> _grants = new HashSet<PolicyBlock>();

    private final Map<Object, PermissionCollection> _cache = new HashMap<Object, PermissionCollection>();

    private final PolicyContext _context = new PolicyContext();

    private final Scanner scanner = new Scanner();

    private boolean initialized = false;

    public JettyPolicy(Set<String> policies, Map<String, String> properties)
    {
        try
        {
            __DEBUG = Boolean.getBoolean("org.eclipse.jetty.policy.DEBUG");
        }
        catch (AccessControlException ace)
        {
            __DEBUG = false;
        }

        _policies = policies;
        _context.setProperties(properties);

        refresh();
    }

    @Override
    public PermissionCollection getPermissions(ProtectionDomain domain)
    {
        if (!initialized)
        {

            refresh();
        }

        if (_cache.containsKey(domain))
        {
            return _cache.get(domain);
        }

        synchronized (_cache)
        {
            // check if it was added in since we obtained lock
            if (_cache.containsKey(domain))
            {
                return _cache.get(domain);
            }

            PermissionCollection perms = new Permissions();

            for (Iterator<PolicyBlock> i = _grants.iterator(); i.hasNext();)
            {
                PolicyBlock policyBlock = i.next();
                ProtectionDomain grantPD = policyBlock.toProtectionDomain();

                if (__DEBUG)
                {
                    System.out.println("----START----");
                    System.out.println("PDCS: " + policyBlock.getCodeSource());
                    System.out.println("CS: " + domain.getCodeSource());
                }

                // 1) if protection domain codesource is null, it is the global permissions (grant {})
                // 2) if protection domain codesource implies target codesource and there are no prinicpals
                // 2) if protection domain codesource implies target codesource and principals align
                if (grantPD.getCodeSource() == null || grantPD.getCodeSource().implies(domain.getCodeSource()) && grantPD.getPrincipals() == null || grantPD.getCodeSource().implies(domain.getCodeSource())
                        && validate(grantPD.getPrincipals(),domain.getPrincipals()))
                {

                    for (Enumeration<Permission> e = policyBlock.getPermissions().elements(); e.hasMoreElements();)
                    {
                        Permission perm = e.nextElement();
                        if (__DEBUG)
                        {
                            System.out.println("D: " + perm);
                        }
                        perms.add(perm);
                    }
                }
                if (__DEBUG)
                {
                    System.out.println("----STOP----");
                }
            }

            _cache.put(domain,perms);

            return perms;
        }
    }

    @Override
    public PermissionCollection getPermissions(CodeSource codesource)
    {
        if (!initialized)
        {
            synchronized (this)
            {
                refresh();
            }
        }

        if (_cache.containsKey(codesource))
        {
            return _cache.get(codesource);
        }

        synchronized (_cache)
        {
            // check if it was added in since we obtained lock
            if (_cache.containsKey(codesource))
            {
                return _cache.get(codesource);
            }

            PermissionCollection perms = new Permissions();

            for (Iterator<PolicyBlock> i = _grants.iterator(); i.hasNext();)
            {
                PolicyBlock policyBlock = i.next();
                ProtectionDomain grantPD = policyBlock.toProtectionDomain();

                if (grantPD.getCodeSource() == null || grantPD.getCodeSource().implies(codesource))
                {
                    if (__DEBUG)
                    {
                        System.out.println("----START----");
                        System.out.println("PDCS: " + grantPD.getCodeSource());
                        System.out.println("CS: " + codesource);
                    }

                    for (Enumeration<Permission> e = policyBlock.getPermissions().elements(); e.hasMoreElements();)
                    {
                        Permission perm = e.nextElement();
                        if (__DEBUG)
                        {
                            System.out.println("D: " + perm);
                        }
                        perms.add(perm);
                    }

                    if (__DEBUG)
                    {
                        System.out.println("----STOP----");
                    }
                }
            }

            _cache.put(codesource,perms);

            return perms;
        }
    }

    @Override
    public boolean implies(ProtectionDomain domain, Permission permission) {
        PermissionCollection pc;

        if (_cache == null) {
            synchronized (_cache)
            {
                refresh();
            }
        }

        synchronized (_cache) {
            pc = _cache.get(domain);
        }

        if (pc != null) {
            return pc.implies(permission);
        } 
        
        pc = getPermissions(domain);
        if (pc == null) {
            return false;
        }

        synchronized (_cache) {
            // cache it 
            _cache.put(domain, pc);
        }
        
        return pc.implies(permission);
    }
    

    private static boolean validate(Principal[] permCerts, Principal[] classCerts)
    {
        if (classCerts == null)
        {
            return false;
        }

        for (int i = 0; i < permCerts.length; ++i)
        {
            boolean found = false;
            for (int j = 0; j < classCerts.length; ++j)
            {
                if (permCerts[i].equals(classCerts[j]))
                {
                    found = true;
                    break;
                }
            }
            // if we didn't find the permCert in the classCerts then we don't match up
            if (found == false)
            {
                return false;
            }
        }

        return true;
    }

    @Override
    public synchronized void refresh()
    {

        try
        {
            if (!initialized)
            {
                initialize();
            }
            
            for (Iterator<Object> i = _cache.keySet().iterator(); i.hasNext();)
            {
                System.out.println(i.next().toString());
            }
            

            if (__DEBUG)
            {
                System.out.println("refreshing policy files");
            }

            Set<PolicyBlock> clean = new HashSet<PolicyBlock>();

            for (Iterator<String> i = _policies.iterator(); i.hasNext();)
            {
                File policyFile = new File(i.next());

                clean.addAll(DefaultPolicyLoader.load(new FileInputStream(policyFile),_context));
            }

            synchronized (_cache)
            {
                _grants.clear();
                _grants.addAll(clean);
                _cache.clear();
            }

            if (__DEBUG)
            {
                System.out.println("finished reloading policies");
            }

        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    /**
     * TODO make this optional MUST be only called from refresh or race condition
     */
    private synchronized void initialize() throws Exception
    {

        // if we have been initialized since we got lock, return
        if (initialized)
        {
            return;
        }

        List<File> scanDirs = new ArrayList<File>();

        for (Iterator<String> i = _policies.iterator(); i.hasNext();)
        {
            File policyFile = new File(i.next());
            scanDirs.add(policyFile.getParentFile());
        }

        scanner.addListener(new Scanner.DiscreteListener()
        {

            public void fileRemoved(String filename) throws Exception
            { // TODO Auto-generated method stub

            }

            public void fileChanged(String filename) throws Exception
            {
                if (filename.endsWith("policy"))
                {
                    refresh();
                }
            }

            public void fileAdded(String filename) throws Exception
            { // TODO Auto-generated method stub

            }
        });

        scanner.setScanDirs(scanDirs);
        scanner.start();
        scanner.setScanInterval(10);

        initialized = true;
    }

    public void dump(PrintStream out)
    {
        PrintWriter write = new PrintWriter(out);
        write.println("dumping policy settings");

        synchronized (_cache)
        {
            for (Iterator<Object> i = _cache.keySet().iterator(); i.hasNext();)
            {
                Object o = i.next();
                write.println(o.toString());
            }
        }
        write.flush();
    }
}
