// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.start;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.security.Policy;
import java.text.CollationKey;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;

/**
 * <p>
 * It allows an application to be started with the command <code>"java -jar start.jar"</code>.
 * </p>
 * 
 * <p>
 * The behaviour of Main is controlled by the <code>"org/eclipse/start/start.config"</code> file obtained as a resource
 * or file. This can be overridden with the START system property. The format of each line in this file is:
 * </p>
 * 
 * <p>
 * Each line contains entry in the format:
 * </p>
 * 
 * <pre>
 *   SUBJECT [ [!] CONDITION [AND|OR] ]*
 * </pre>
 * 
 * <p>
 * where SUBJECT:
 * </p>
 * <ul>
 * <li>ends with <code>".class"</code> is the Main class to run.</li>
 * <li>ends with <code>".xml"</code> is a configuration file for the command line</li>
 * <li>ends with <code>"/"</code> is a directory from which to add all jar and zip files.</li>
 * <li>ends with <code>"/*"</code> is a directory from which to add all unconsidered jar and zip files.</li>
 * <li>ends with <code>"/**"</code> is a directory from which to recursively add all unconsidered jar and zip files.</li>
 * <li>Containing <code>=</code> are used to assign system properties.</li>
 * <li>Containing <code>~=</code> are used to assign start properties.</li>
 * <li>Containing <code>/=</code> are used to assign a canonical path.</li>
 * <li>all other subjects are treated as files to be added to the classpath.</li>
 * </ul>
 * 
 * <p>
 * property expansion:
 * </p>
 * <ul>
 * <li><code>${name}</code> is expanded to a start property</li>
 * <li><code>$(name)</code> is expanded to either a start property or a system property.</li>
 * <li>The start property <code>${version}</code> is defined as the version of the start.jar</li>
 * </ul>
 * 
 * <p>
 * Files starting with <code>"/"</code> are considered absolute, all others are relative to the home directory.
 * </p>
 * 
 * <p>
 * CONDITION is one of:
 * </p>
 * <ul>
 * <li><code>always</code></li>
 * <li><code>never</code></li>
 * <li><code>available classname</code> - true if class on classpath</li>
 * <li><code>property name</code> - true if set as start property</li>
 * <li><code>system name</code> - true if set as system property</li>
 * <li><code>exists file</code> - true if file/dir exists</li>
 * <li><code>java OPERATOR version</code> - java version compared to literal</li>
 * <li><code>nargs OPERATOR number</code> - number of command line args compared to literal</li>
 * <li>OPERATOR := one of <code>"&lt;"</code>,<code>"&gt;"</code>,<code>"&lt;="</code>,<code>"&gt;="</code>,
 * <code>"=="</code>,<code>"!="</code></li>
 * </ul>
 * 
 * <p>
 * CONDITIONS can be combined with <code>AND</code> <code>OR</code> or <code>!</code>, with <code>AND</code> being the
 * assume operator for a list of CONDITIONS.
 * </p>
 * 
 * <p>
 * Classpath operations are evaluated on the fly, so once a class or jar is added to the classpath, subsequent available
 * conditions will see that class.
 * </p>
 * 
 * <p>
 * The configuration file may be divided into sections with option names like: [ssl,default]
 * </p>
 * 
 * <p>
 * Clauses after a section header will only be included if they match one of the tags in the options property. By
 * default options are set to "default,*" or the OPTIONS property may be used to pass in a list of tags, eg. :
 * </p>
 * 
 * <pre>
 *    java -jar start.jar OPTIONS=jetty,jsp,ssl
 * </pre>
 * 
 * <p>
 * The tag '*' is always appended to the options, so any section with the * tag is always applied.
 * </p>
 */
public class Config
{
    public static final String DEFAULT_SECTION = "";
    static
    {
        Package pkg = Config.class.getPackage();
        if (pkg != null && (pkg.getImplementationVersion() != null))
            _version = pkg.getImplementationVersion();
        else
            _version = System.getProperty("jetty.version","Unknown");
    }

    /**
     * Natural language sorting for key names.
     */
    private Comparator<String> keySorter = new Comparator<String>() 
    {
        private Collator collator = Collator.getInstance();

        public int compare(String o1, String o2)
        {
            CollationKey key1 = collator.getCollationKey(o1);
            CollationKey key2 = collator.getCollationKey(o2);
            return key1.compareTo(key2);
        }
    };

    private static final String _version;
    private static boolean DEBUG = false;
    private Map<String, Classpath> _classpaths = new HashMap<String, Classpath>();
    private List<String> _xml = new ArrayList<String>();
    private Set<String> _policies = new HashSet<String>();
    private String _classname = null;
    private Map<String, String> _properties = new HashMap<String, String>();
    private int argCount = 0;

    private boolean addClasspathComponent(List<String> sections, String component)
    {
        for (String section : sections)
        {
            Classpath cp = _classpaths.get(section);
            if (cp == null)
            {
                cp = new Classpath();
            }

            boolean added = cp.addComponent(component);
            _classpaths.put(section,cp);

            if (!added)
            {
                // First failure means all failed.
                return false;
            }
        }

        return true;
    }

    private boolean addClasspathPath(List<String> sections, String path)
    {
        for (String section : sections)
        {
            Classpath cp = _classpaths.get(section);
            if (cp == null)
            {
                cp = new Classpath();
            }
            if (!cp.addClasspath(path))
            {
                // First failure means all failed.
                return false;
            }
            _classpaths.put(section,cp);
        }

        return true;
    }

    private void addJars(List<String> sections, File dir, boolean recurse) throws IOException
    {
        List<File> entries = new ArrayList<File>();
        File[] files = dir.listFiles();
        if (files == null)
        {
            // No files found, skip it.
            return;
        }
        entries.addAll(Arrays.asList(files));
        Collections.sort(entries,FilenameComparator.INSTANCE);

        for (File entry : entries)
        {
            if (entry.isDirectory() && recurse)
                addJars(sections,entry,recurse);
            else
            {
                String name = entry.getName().toLowerCase();
                if (name.endsWith(".jar") || name.endsWith(".zip"))
                {
                    String jar = entry.getCanonicalPath();
                    boolean added = addClasspathComponent(sections,jar);
                    debug((added?"  CLASSPATH+=":"  !") + jar);
                }
            }
        }
    }

    private void close(InputStream stream)
    {
        if (stream == null)
            return;

        try
        {
            stream.close();
        }
        catch (IOException ignore)
        {
            /* ignore */
        }
    }

    private void close(Reader reader)
    {
        if (reader == null)
            return;

        try
        {
            reader.close();
        }
        catch (IOException ignore)
        {
            /* ignore */
        }
    }
    
    public static boolean isDebug()
    {
        return DEBUG;
    }

    public static void debug(String msg)
    {
        if (DEBUG)
        {
            System.err.println(msg);
        }
    }

    public static void debug(Throwable t)
    {
        if (DEBUG)
        {
            t.printStackTrace(System.err);
        }
    }

    private String expand(String s)
    {
        int i1 = 0;
        int i2 = 0;
        while (s != null)
        {
            i1 = s.indexOf("$(",i2);
            if (i1 < 0)
                break;
            i2 = s.indexOf(")",i1 + 2);
            if (i2 < 0)
                break;
            String name = s.substring(i1 + 2,i2);
            String property = getSystemProperty(name);
            s = s.substring(0,i1) + property + s.substring(i2 + 1);
        }

        i1 = 0;
        i2 = 0;
        while (s != null)
        {
            i1 = s.indexOf("${",i2);
            if (i1 < 0)
                break;
            i2 = s.indexOf("}",i1 + 2);
            if (i2 < 0)
                break;
            String name = s.substring(i1 + 2,i2);
            String property = getProperty(name);
            s = s.substring(0,i1) + property + s.substring(i2 + 1);
        }

        return s;
    }

    /**
     * Get the default classpath.
     * 
     * @return the default classpath
     */
    public Classpath getClasspath()
    {
        return _classpaths.get(DEFAULT_SECTION);
    }

    /**
     * Get the combined classpath representing the default classpath plus all named sections.
     * 
     * NOTE: the default classpath will be prepended, and the '*' classpath will be appended.
     * 
     * @param sectionIds
     *            the list of section ids to fetch
     * @return the {@link Classpath} representing combination all of the selected sectionIds, combined with the default
     *         section id, and '*' special id.
     */
    public Classpath getCombinedClasspath(Collection<String> sectionIds)
    {
        Classpath cp = new Classpath();

        cp.overlay(_classpaths.get(DEFAULT_SECTION));
        for (String sectionId : sectionIds)
        {
            cp.overlay(_classpaths.get(sectionId));
        }
        cp.overlay(_classpaths.get("*"));
        return cp;
    }

    public String getMainClassname()
    {
        return _classname;
    }

    public String getProperty(String name)
    {
        if ("version".equalsIgnoreCase(name))
            return _version;

        return _properties.get(name);
    }

    public String getProperty(String name, String dftValue)
    {
        if (_properties.containsKey(name))
            return _properties.get(name);
        return dftValue;
    }

    /**
     * Get the classpath for the named section
     * 
     * @param sectionId
     * @return
     */
    public Classpath getSectionClasspath(String sectionId)
    {
        return _classpaths.get(sectionId);
    }

    /**
     * Get the list of section Ids.
     * 
     * @return
     */
    public Set<String> getSectionIds()
    {
        Set<String> ids = new TreeSet<String>(keySorter);
        ids.addAll(_classpaths.keySet());
        return ids;
    }

    private String getSystemProperty(String name)
    {
        if ("version".equalsIgnoreCase(name))
            return _version;
        if (_properties.containsKey(name))
            return _properties.get(name);
        return System.getProperty(name);
    }

    public List<String> getXmlConfigs()
    {
        return _xml;
    }

    private boolean isAvailable(List<String> sections, String classname)
    {
        // Try default/parent class loader first.
        try
        {
            Class.forName(classname);
            return true;
        }
        catch (NoClassDefFoundError e)
        {
            debug(e);
        }
        catch (ClassNotFoundException e)
        {
            debug(e);
        }

        // Try section classloaders instead
        ClassLoader loader;
        Classpath classpath;
        for (String sectionId : sections)
        {
            classpath = _classpaths.get(sectionId);
            if (classpath == null)
            {
                // skip, no classpath
                continue;
            }

            loader = classpath.getClassLoader();

            try
            {
                loader.loadClass(classname);
                return true;
            }
            catch (NoClassDefFoundError e)
            {
                debug(e);
            }
            catch (ClassNotFoundException e)
            {
                debug(e);
            }
        }
        return false;
    }

    /**
     * Parse the configuration
     * 
     * @param buf
     * @throws IOException
     */
    public void parse(CharSequence buf) throws IOException
    {
        parse(new StringReader(buf.toString()));
    }

    /**
     * Parse the configuration
     * 
     * @param buf
     * @throws IOException
     */
    public void parse(InputStream stream) throws IOException
    {
        InputStreamReader reader = null;
        try
        {
            reader = new InputStreamReader(stream);
            parse(reader);
        }
        finally
        {
            close(reader);
        }
    }

    public void parse(Reader reader) throws IOException
    {
        BufferedReader buf = null;

        try
        {
            buf = new BufferedReader(reader);

            List<String> sections = new ArrayList<String>();
            sections.add(DEFAULT_SECTION);
            _classpaths.put(DEFAULT_SECTION,new Classpath());
            Version java_version = new Version(System.getProperty("java.version"));
            Version ver = new Version();

            String line = null;
            while ((line = buf.readLine()) != null)
            {
                String trim = line.trim();
                if (trim.length() == 0) // empty line
                    continue;

                if (trim.startsWith("#")) // comment
                    continue;

                // handle options
                if (trim.startsWith("[") && trim.endsWith("]"))
                {
                    sections = Arrays.asList(trim.substring(1,trim.length() - 1).split(","));
                    // Ensure section classpaths exist
                    for (String sectionId : sections)
                    {
                        if (!_classpaths.containsKey(sectionId))
                        {
                            _classpaths.put(sectionId,new Classpath());
                        }
                    }
                }

                try
                {
                    StringTokenizer st = new StringTokenizer(line);
                    String subject = st.nextToken();
                    boolean expression = true;
                    boolean not = false;
                    String condition = null;
                    // Evaluate all conditions
                    while (st.hasMoreTokens())
                    {
                        condition = st.nextToken();
                        if (condition.equalsIgnoreCase("!"))
                        {
                            not = true;
                            continue;
                        }
                        if (condition.equalsIgnoreCase("OR"))
                        {
                            if (expression)
                                break;
                            expression = true;
                            continue;
                        }
                        if (condition.equalsIgnoreCase("AND"))
                        {
                            if (!expression)
                                break;
                            continue;
                        }
                        boolean eval = true;
                        if (condition.equals("true") || condition.equals("always"))
                        {
                            eval = true;
                        }
                        else if (condition.equals("false") || condition.equals("never"))
                        {
                            eval = false;
                        }
                        else if (condition.equals("available"))
                        {
                            String class_to_check = st.nextToken();
                            eval = isAvailable(sections,class_to_check);
                        }
                        else if (condition.equals("exists"))
                        {
                            try
                            {
                                eval = false;
                                File file = new File(expand(st.nextToken()));
                                eval = file.exists();
                            }
                            catch (Exception e)
                            {
                                debug(e);
                            }
                        }
                        else if (condition.equals("property"))
                        {
                            String property = getProperty(st.nextToken());
                            eval = property != null && property.length() > 0;
                        }
                        else if (condition.equals("system"))
                        {
                            String property = System.getProperty(st.nextToken());
                            eval = property != null && property.length() > 0;
                        }
                        else if (condition.equals("java"))
                        {
                            String operator = st.nextToken();
                            String version = st.nextToken();
                            ver.parse(version);
                            eval = (operator.equals("<") && java_version.compare(ver) < 0) || (operator.equals(">") && java_version.compare(ver) > 0)
                                    || (operator.equals("<=") && java_version.compare(ver) <= 0) || (operator.equals("=<") && java_version.compare(ver) <= 0)
                                    || (operator.equals("=>") && java_version.compare(ver) >= 0) || (operator.equals(">=") && java_version.compare(ver) >= 0)
                                    || (operator.equals("==") && java_version.compare(ver) == 0) || (operator.equals("!=") && java_version.compare(ver) != 0);
                        }
                        else if (condition.equals("nargs"))
                        {
                            String operator = st.nextToken();
                            int number = Integer.parseInt(st.nextToken());
                            eval = (operator.equals("<") && argCount < number) || (operator.equals(">") && argCount > number)
                                    || (operator.equals("<=") && argCount <= number) || (operator.equals("=<") && argCount <= number)
                                    || (operator.equals("=>") && argCount >= number) || (operator.equals(">=") && argCount >= number)
                                    || (operator.equals("==") && argCount == number) || (operator.equals("!=") && argCount != number);
                        }
                        else
                        {
                            System.err.println("ERROR: Unknown condition: " + condition);
                            eval = false;
                        }
                        expression &= not?!eval:eval;
                        not = false;
                    }

                    String file = expand(subject);
                    debug((expression?"T ":"F ") + line);
                    if (!expression)
                        continue;

                    // Setting of a start property
                    if (subject.indexOf("~=") > 0)
                    {
                        int i = file.indexOf("~=");
                        String property = file.substring(0,i);
                        String value = fixPath(file.substring(i + 2));
                        debug("  " + property + "~=" + value);
                        setProperty(property,value);
                    }
                    else
                    // Setting of start property with canonical path
                    if (subject.indexOf("/=") > 0)
                    {
                        int i = file.indexOf("/=");
                        String property = file.substring(0,i);
                        String value = fixPath(file.substring(i + 2));
                        String canonical = new File(value).getCanonicalPath();
                        debug("  " + property + "/=" + value + "==" + canonical);
                        setProperty(property,canonical);
                    }
                    else
                    // Setting of system property
                    if (subject.indexOf("=") > 0)
                    {
                        int i = file.indexOf("=");
                        String property = file.substring(0,i);
                        String value = fixPath(file.substring(i + 1));
                        debug("  " + property + "=" + value);
                        System.setProperty(property,value);
                    }
                    else
                    // Add all unconsidered JAR and ZIP files to classpath
                    if (subject.endsWith("/*"))
                    {
                        // directory of JAR files - only add jars and zips within the directory
                        File dir = new File(fixPath(file.substring(0,file.length() - 1)));
                        addJars(sections,dir,false);
                    }
                    else
                    // Recursively add all unconsidered JAR and ZIP files to classpath
                    if (subject.endsWith("/**"))
                    {
                        //directory hierarchy of jar files - recursively add all jars and zips in the hierarchy
                        File dir = new File(fixPath(file.substring(0,file.length() - 2)));
                        addJars(sections,dir,true);
                    }
                    else
                    // Add raw classpath directory to classpath
                    if (subject.endsWith("/"))
                    {
                        // class directory
                        File cd = new File(fixPath(file));
                        String d = cd.getCanonicalPath();
                        boolean added = addClasspathComponent(sections,d);
                        debug((added?"  CLASSPATH+=":"  !") + d);
                    }
                    else
                    // Add XML configuration
                    if (subject.toLowerCase().endsWith(".xml"))
                    {
                        // Config file
                        File f = new File(fixPath(file));
                        if (f.exists())
                            _xml.add(f.getCanonicalPath());
                        debug("  ARGS+=" + f);
                    }
                    else
                    // Set the main class to execute (overrides any previously set)
                    if (subject.toLowerCase().endsWith(".class"))
                    {
                        // Class
                        String cn = expand(subject.substring(0,subject.length() - 6));
                        if (cn != null && cn.length() > 0)
                        {
                            debug("  CLASS=" + cn);
                            _classname = cn;
                        }
                    }
                    else
                    // Add raw classpath entry
                    if (subject.toLowerCase().endsWith(".path"))
                    {
                        // classpath (jetty.class.path?) to add to runtime classpath
                        String cn = expand(subject.substring(0,subject.length() - 5));
                        if (cn != null && cn.length() > 0)
                        {
                            debug("  PATH=" + cn);
                            addClasspathPath(sections,cn);
                        }
                    }
                    else
                    // Add Security Policy file reference
                    if (subject.toLowerCase().endsWith(".policy"))
                    {
                        //policy file to parse
                        String cn = expand(subject.substring(0,subject.length()));
                        if (cn != null && cn.length() > 0)
                        {
                            debug("  POLICY=" + cn);
                            _policies.add(fixPath(cn));
                        }
                    }
                    else
                    {
                        // single JAR file
                        File f = new File(fixPath(file));
                        if (f.exists())
                        {
                            String d = f.getCanonicalPath();
                            boolean added = addClasspathComponent(sections,d);
                            if (!added)
                            {
                                added = addClasspathPath(sections,expand(subject));
                            }
                            debug((added?"  CLASSPATH+=":"  !") + d);
                        }
                    }
                }
                catch (Exception e)
                {
                    System.err.println("on line: '" + line + "'");
                    e.printStackTrace();
                }
            }
        }
        finally
        {
            close(buf);
        }
    }

    private String fixPath(String path)
    {
        return path.replace('/',File.separatorChar);
    }

    public void parse(URL url) throws IOException
    {
        InputStream stream = null;
        InputStreamReader reader = null;
        try
        {
            stream = url.openStream();
            reader = new InputStreamReader(stream);
            parse(reader);
        }
        finally
        {
            close(reader);
            close(stream);
        }
    }

    public void setArgCount(int argCount)
    {
        this.argCount = argCount;
    }

    public void setProperty(String name, String value)
    {
        if (name.equals("DEBUG"))
        {
            DEBUG = Boolean.parseBoolean(value);
        }
        _properties.put(name,value);
    }

    public Policy getPolicyInstance(ClassLoader cl) throws ClassNotFoundException, SecurityException, NoSuchMethodException, IllegalArgumentException,
            InstantiationException, IllegalAccessException, InvocationTargetException
    {
        Class<?> jettyPolicy = cl.loadClass("org.eclipse.jetty.policy.JettyPolicy");
        Constructor<?> c = jettyPolicy.getConstructor(new Class[]
        { Set.class, Map.class });
        Object policyClass = c.newInstance(_policies,_properties);

        if (policyClass instanceof Policy)
        {
            return (Policy)policyClass;
        }

        throw new ClassCastException("Unable to cast to " + Policy.class.getName() + " : " + policyClass.getClass().getName());
    }
}
