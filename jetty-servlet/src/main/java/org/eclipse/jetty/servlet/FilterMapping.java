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

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;

import org.eclipse.jetty.server.DispatcherType;

import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;


public class FilterMapping extends AbstractMapping
{
    /** Dispatch types */
    public static final int DEFAULT=0;
    public static final int REQUEST=1;
    public static final int FORWARD=2;
    public static final int INCLUDE=4;
    public static final int ERROR=8;
    public static final int ASYNC=16;
    public static final int ALL=31;
    

    /* ------------------------------------------------------------ */
    /** Dispatch type from name
     */
    public static DispatcherType dispatch(String type)
    {
        if ("request".equalsIgnoreCase(type))
            return DispatcherType.REQUEST;
        if ("forward".equalsIgnoreCase(type))
            return DispatcherType.FORWARD;
        if ("include".equalsIgnoreCase(type))
            return DispatcherType.INCLUDE;
        if ("error".equalsIgnoreCase(type))
            return DispatcherType.ERROR;
        if ("async".equalsIgnoreCase(type))
            return DispatcherType.ASYNC;
        throw new IllegalArgumentException(type);
    }
    
    /* ------------------------------------------------------------ */
    /** Dispatch type from name
     */
    public static int dispatch(DispatcherType type)
    {
    	switch(type)
    	{
    	  case REQUEST:
    		  return REQUEST;
    	  case ASYNC:
    		  return ASYNC;
    	  case FORWARD:
    		  return FORWARD;
    	  case INCLUDE:
    		  return INCLUDE;
    	  case ERROR:
    		  return ERROR;
    	}
        throw new IllegalArgumentException(type.toString());
    }
	

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    
	
    private int _dispatches=DEFAULT;
    private transient FilterHolder _holder;
    private String[] _servletNames;

    /* ------------------------------------------------------------ */
    public FilterMapping()
    {}
    
    /* ------------------------------------------------------------ */
    /** Check if this filter applies to a path.
     * @param path The path to check or null to just check type
     * @param type The type of request: __REQUEST,__FORWARD,__INCLUDE, __ASYNC or __ERROR.
     * @return True if this filter applies
     */
    boolean appliesTo(String path, int type)
    {
        if (appliesTo(type))
        {
            String[] pathSpecs = getPathSpecs();
            for (int i=0;i<pathSpecs.length;i++)
                if (pathSpecs[i]!=null &&  PathMap.match(pathSpecs[i], path,true))
                    return true;
        }

        return false;
    }
    
    /* ------------------------------------------------------------ */
    /** Check if this filter applies to a particular dispatch type.
     * @param type The type of request:
     *      {@link Handler#REQUEST}, {@link Handler#FORWARD}, {@link Handler#INCLUDE} or {@link Handler#ERROR}.
     * @return <code>true</code> if this filter applies
     */
    boolean appliesTo(int type)
    {
    	if (_dispatches==0)
    		return type==REQUEST || type==ASYNC && _holder.isAsyncSupported();
        return (_dispatches&type)!=0;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the filterName.
     */
    public String getFilterName()
    {
        return getEntityName();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the holder.
     */
    FilterHolder getFilterHolder()
    {
        return _holder;
    }
    
    /* ------------------------------------------------------------ */
    public void setDispatcherTypes(EnumSet<DispatcherType> dispatcherTypes) 
    {
        _dispatches=DEFAULT;
        if (dispatcherTypes!=null)
        {
            if (dispatcherTypes.contains(DispatcherType.ERROR)) 
                _dispatches|=ERROR;
            if (dispatcherTypes.contains(DispatcherType.FORWARD)) 
                _dispatches|=FORWARD;
            if (dispatcherTypes.contains(DispatcherType.INCLUDE)) 
                _dispatches|=INCLUDE;
            if (dispatcherTypes.contains(DispatcherType.REQUEST)) 
                _dispatches|=REQUEST;
            if (dispatcherTypes.contains(DispatcherType.ASYNC)) 
                _dispatches|=ASYNC;
        }
    }
    
    
    
    /* ------------------------------------------------------------ */
    /**
     * @param dispatches The dispatches to set.
     * @see #DEFAULT
     * @see #REQUEST
     * @see #ERROR
     * @see #FORWARD
     * @see #INCLUDE
     */
    public void setDispatches(int dispatches)
    {
        _dispatches = dispatches;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param filterName The filterName to set.
     */
    public void setFilterName(String filterName)
    {
        setEntityName(filterName);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param holder The holder to set.
     */
    void setFilterHolder(FilterHolder holder)
    {
        _holder = holder;
        setFilterName(holder.getName());
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the servletName.
     */
    public String[] getServletNames()
    {
        return _servletNames;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param servletNames Maps the {@link #setFilterName(String) named filter} to multiple servlets
     * @see #setServletName
     */
    public void setServletNames(String[] servletNames)
    {
        _servletNames = servletNames;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param servletName Maps the {@link #setFilterName(String) named filter} to a single servlet
     * @see #setServletNames
     */
    public void setServletName(String servletName)
    {
        _servletNames = new String[]{servletName};
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return 
        TypeUtil.asList(getPathSpecs())+"/"+
        TypeUtil.asList(_servletNames)+"=="+
        _dispatches+"=>"+
        getEntityName(); 
    }
}
