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

package org.eclipse.jetty.continuation;

import java.lang.reflect.Constructor;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

/* ------------------------------------------------------------ */
/** ContinuationSupport.
 * 
 */
public class ContinuationSupport
{
    static final boolean __jetty6;
    static final boolean __servlet3;
    static final Constructor<? extends Continuation> __newServlet3Continuation;
    static final Constructor<? extends Continuation> __newJetty6Continuation;
    static 
    {
        boolean s3=false;
        Constructor<? extends Continuation>s3cc=null;
        try
        {       
            s3=ServletRequest.class.getMethod("startAsync",null)!=null;
            Class<?> s3c = ContinuationSupport.class.getClassLoader().loadClass("org.eclipse.jetty.continuation.Servlet3Continuation");
            s3cc=s3c.getConstructors()[0];
            s3=true;
        }
        catch (Exception e)
        {}
        finally
        {
            __servlet3=s3;
            __newServlet3Continuation=s3cc;
        }
        
        
        boolean j6=false;
        Constructor<? extends Continuation>j6cc=null;
        try
        {      
            j6=ContinuationSupport.class.getClassLoader().loadClass("org.mortbay.util.ajax.ContinuationSupport")!=null;
            Class<?> j6c = ContinuationSupport.class.getClassLoader().loadClass("org.eclipse.jetty.continuation.Jetty6Continuation");
            j6cc=j6c.getConstructors()[0];
            j6=true;
        }
        catch (Exception e)
        {}
        finally
        {
            __jetty6=j6;
            __newJetty6Continuation=j6cc;
        }
    }

    public static Continuation getContinuation(final ServletRequest request, final ServletResponse response)
    {   
        Continuation continuation = (Continuation) request.getAttribute(Continuation.ATTRIBUTE);
        if (continuation!=null)
            return continuation;
        
        if (__servlet3 )
        { 
            try
            {
                continuation=__newServlet3Continuation.newInstance(request,response);
                request.setAttribute(Continuation.ATTRIBUTE,continuation);
                return continuation;
            }
            catch(Exception e)
            {
                throw new RuntimeException(e);
            }
        }
        
        if (__jetty6)
        {
            Object c=request.getAttribute("org.mortbay.jetty.ajax.Continuation");
            try
            {
                continuation= __newJetty6Continuation.newInstance(request,response,c);
                request.setAttribute(Continuation.ATTRIBUTE,continuation);
                return continuation;
            }
            catch(Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        throw new IllegalStateException("!(Jetty || Servlet 3.0 || ContinuationFilter)");
    }
}
