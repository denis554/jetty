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

package org.eclipse.jetty.util.log;

import org.eclipse.jetty.util.DateCache;

/*-----------------------------------------------------------------------*/
/** StdErr Logging.
 * This implementation of the Logging facade sends all logs to StdErr with minimal formatting.
 * 
 * If the system property org.eclipse.jetty.util.log.DEBUG is set, 
 * then debug logs are printed if stderr is being used.
 * 
 */
public class StdErrLog implements Logger
{    
    private static DateCache _dateCache;
    
    private static boolean _debug = Boolean.parseBoolean(System.getProperty("org.eclipse.jetty.util.log.DEBUG","false"));
    private String _name;
    private boolean _hideStacks=false;
    
    static
    {
        try
        {
            _dateCache=new DateCache("yyyy-MM-dd HH:mm:ss");
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }
        
    }
    
    public StdErrLog()
    {
        this(null);
    }
    
    public StdErrLog(String name)
    {    
        this._name=name==null?"":name;
    }
    
    public boolean isDebugEnabled()
    {
        return _debug;
    }
    
    public void setDebugEnabled(boolean enabled)
    {
        _debug=enabled;
    }
    
    public boolean isHideStacks()
    {
        return _hideStacks;
    }

    public void setHideStacks(boolean hideStacks)
    {
        _hideStacks = hideStacks;
    }

    public void info(String msg,Object arg0, Object arg1)
    {
        String d=_dateCache.now();
        int ms=_dateCache.lastMs();
        System.err.println(d+(ms>99?".":(ms>0?".0":".00"))+ms+":"+_name+":INFO:  "+format(msg,arg0,arg1));
    }
    
    public void debug(String msg,Throwable th)
    {
        if (_debug)
        {
            String d=_dateCache.now();
            int ms=_dateCache.lastMs();
            System.err.println(d+(ms>99?".":(ms>0?".0":".00"))+ms+":"+_name+":DEBUG: "+msg);
            if (th!=null) 
            {
                if (_hideStacks)
                    System.err.println(th);
                else
                    th.printStackTrace();
            }
        }
    }
    
    public void debug(String msg,Object arg0, Object arg1)
    {
        if (_debug)
        {
            String d=_dateCache.now();
            int ms=_dateCache.lastMs();
            System.err.println(d+(ms>99?".":(ms>0?".0":".00"))+ms+":"+_name+":DEBUG: "+format(msg,arg0,arg1));
        }
    }
    
    public void warn(String msg,Object arg0, Object arg1)
    {
        String d=_dateCache.now();
        int ms=_dateCache.lastMs();
        System.err.println(d+(ms>99?".":(ms>0?".0":".00"))+ms+":"+_name+":WARN:  "+format(msg,arg0,arg1));
    }
    
    public void warn(String msg, Throwable th)
    {
        String d=_dateCache.now();
        int ms=_dateCache.lastMs();
        System.err.println(d+(ms>99?".":(ms>0?".0":".00"))+ms+":"+_name+":WARN:  "+msg);
        if (th!=null) 
        {
            if (_hideStacks)
                System.err.println(th);
            else
                th.printStackTrace();
        }
    }

    private String format(String msg, Object arg0, Object arg1)
    {
        int i0=msg.indexOf("{}");
        int i1=i0<0?-1:msg.indexOf("{}",i0+2);
        
        if (arg1!=null && i1>=0)
            msg=msg.substring(0,i1)+arg1+msg.substring(i1+2);
        if (arg0!=null && i0>=0)
            msg=msg.substring(0,i0)+arg0+msg.substring(i0+2);
        return msg;
    }
    
    public Logger getLogger(String name)
    {
        if ((name==null && this._name==null) ||
            (name!=null && name.equals(this._name)))
            return this;
        return new StdErrLog(name);
    }
    
    public String toString()
    {
        return "STDERR"+_name;
    }

}

