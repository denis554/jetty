// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.servlets;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.Socket;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.testing.HttpTester;
import org.eclipse.jetty.testing.ServletTester;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.IO;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GzipFilterTest
{
    private static String _content =
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. In quis felis nunc. "+
        "Quisque suscipit mauris et ante auctor ornare rhoncus lacus aliquet. Pellentesque "+
        "habitant morbi tristique senectus et netus et malesuada fames ac turpis egestas. "+
        "Vestibulum sit amet felis augue, vel convallis dolor. Cras accumsan vehicula diam "+
        "at faucibus. Etiam in urna turpis, sed congue mi. Morbi et lorem eros. Donec vulputate "+
        "velit in risus suscipit lobortis. Aliquam id urna orci, nec sollicitudin ipsum. "+
        "Cras a orci turpis. Donec suscipit vulputate cursus. Mauris nunc tellus, fermentum "+
        "eu auctor ut, mollis at diam. Quisque porttitor ultrices metus, vitae tincidunt massa "+
        "sollicitudin a. Vivamus porttitor libero eget purus hendrerit cursus. Integer aliquam "+
        "consequat mauris quis luctus. Cras enim nibh, dignissim eu faucibus ac, mollis nec neque. "+
        "Aliquam purus mauris, consectetur nec convallis lacinia, porta sed ante. Suspendisse "+
        "et cursus magna. Donec orci enim, molestie a lobortis eu, imperdiet vitae neque.";

    @Rule
    public TestingDir testdir = new TestingDir();

    private ServletTester tester;

    @Before
    public void setUp() throws Exception
    {
        testdir.ensureEmpty();

        File testFile = testdir.getFile("file.txt");
        BufferedOutputStream testOut = new BufferedOutputStream(new FileOutputStream(testFile));
        ByteArrayInputStream testIn = new ByteArrayInputStream(_content.getBytes("ISO8859_1"));
        IO.copy(testIn,testOut);
        testOut.close();
        
        tester=new ServletTester();
        tester.setContextPath("/context");
        tester.setResourceBase(testdir.getDir().getCanonicalPath());
        tester.addServlet(org.eclipse.jetty.servlet.DefaultServlet.class, "/");
        FilterHolder holder = tester.addFilter(GzipFilter.class,"/*",0);
        holder.setInitParameter("mimeTypes","text/plain");
        tester.start();
    }

    @After
    public void tearDown() throws Exception
    {
        tester.stop();
        IO.delete(testdir.getDir());
    }

    @Test
    public void testGzipFilter() throws Exception
    {
        // generated and parsed test
        HttpTester request = new HttpTester();
        HttpTester response = new HttpTester();

        request.setMethod("GET");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setHeader("accept-encoding","gzip");
        request.setURI("/context/file.txt");
        
        ByteArrayBuffer reqsBuff = new ByteArrayBuffer(request.generate().getBytes());
        ByteArrayBuffer respBuff = tester.getResponses(reqsBuff);
        response.parse(respBuff.asArray());
                
        assertTrue(response.getMethod()==null);
        assertTrue(response.getHeader("Content-Encoding").equalsIgnoreCase("gzip"));
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());
        
        int length = Integer.valueOf(response.getHeader("Content-Length"));
        
        InputStream testIn = new GZIPInputStream(new ByteArrayInputStream(response.getContentBytes()));
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn,testOut);
        
        assertEquals(_content, testOut.toString("UTF8"));
    }
}
