package org.eclipse.jetty.server;

import java.io.IOException;

import javax.servlet.ServletResponse;
import javax.servlet.ServletResponseWrapper;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;


/* ------------------------------------------------------------ */
/** Wrapper to tunnel a ServletResponse via a HttpServletResponse
 */
public class ServletResponseHttpWrapper extends ServletResponseWrapper implements HttpServletResponse
{
    public ServletResponseHttpWrapper(ServletResponse response)
    {
        super(response);
    }

    public void addCookie(Cookie cookie)
    {        
    }

    public boolean containsHeader(String name)
    {
        return false;
    }

    public String encodeURL(String url)
    {
        return null;
    }

    public String encodeRedirectURL(String url)
    {
        return null;
    }

    public String encodeUrl(String url)
    {
        return null;
    }

    public String encodeRedirectUrl(String url)
    {
        return null;
    }

    public void sendError(int sc, String msg) throws IOException
    {        
    }

    public void sendError(int sc) throws IOException
    {        
    }

    public void sendRedirect(String location) throws IOException
    {        
    }

    public void setDateHeader(String name, long date)
    {        
    }

    public void addDateHeader(String name, long date)
    {        
    }

    public void setHeader(String name, String value)
    {        
    }

    public void addHeader(String name, String value)
    {        
    }

    public void setIntHeader(String name, int value)
    {        
    }

    public void addIntHeader(String name, int value)
    {        
    }

    public void setStatus(int sc)
    {        
    }

    public void setStatus(int sc, String sm)
    {        
    }

}
