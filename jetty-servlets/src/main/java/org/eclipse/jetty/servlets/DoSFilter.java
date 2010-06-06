// ========================================================================
// Copyright (c) 2009 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.util.HashSet;
import java.util.Queue;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.Timeout;

/**
 * Denial of Service filter
 *
 * <p>
 * This filter is based on the {@link QoSFilter}. it is useful for limiting
 * exposure to abuse from request flooding, whether malicious, or as a result of
 * a misconfigured client.
 * <p>
 * The filter keeps track of the number of requests from a connection per
 * second. If a limit is exceeded, the request is either rejected, delayed, or
 * throttled.
 * <p>
 * When a request is throttled, it is placed in a priority queue. Priority is
 * given first to authenticated users and users with an HttpSession, then
 * connections which can be identified by their IP addresses. Connections with
 * no way to identify them are given lowest priority.
 * <p>
 * The {@link #extractUserId(ServletRequest request)} function should be
 * implemented, in order to uniquely identify authenticated users.
 * <p>
 * The following init parameters control the behavior of the filter:<dl>
 *
 * <dt>maxRequestsPerSec</dt>
 *                      <dd>the maximum number of requests from a connection per
 *                      second. Requests in excess of this are first delayed,
 *                      then throttled.</dd>
 *
 * <dt>delayMs</dt>
 *                      <dd>is the delay given to all requests over the rate limit,
 *                      before they are considered at all. -1 means just reject request,
 *                      0 means no delay, otherwise it is the delay.</dd>
 *
 * <dt>maxWaitMs</dt>
 *                      <dd>how long to blocking wait for the throttle semaphore.</dd>
 *
 * <dt>throttledRequests</dt>
 *                      <dd>is the number of requests over the rate limit able to be
 *                      considered at once.</dd>
 *
 * <dt>throttleMs</dt>
 *                      <dd>how long to async wait for semaphore.</dd>
 *
 * <dt>maxRequestMs</dt>
 *                      <dd>how long to allow this request to run.</dd>
 *
 * <dt>maxIdleTrackerMs</dt>
 *                      <dd>how long to keep track of request rates for a connection,
 *                      before deciding that the user has gone away, and discarding it</dd>
 *
 * <dt>insertHeaders</dt>
 *                      <dd>if true , insert the DoSFilter headers into the response. Defaults to true.</dd>
 *
 * <dt>trackSessions</dt>
 *                      <dd>if true, usage rate is tracked by session if a session exists. Defaults to true.</dd>
 *
 * <dt>remotePort</dt>
 *                      <dd>if true and session tracking is not used, then rate is tracked by IP+port (effectively connection). Defaults to false.</dd>
 *
 * <dt>ipWhitelist</dt>
 *                      <dd>a comma-separated list of IP addresses that will not be rate limited</dd>
 * 
 * <dt>managedAttr</dt>
 *                      <dd>if set to true, then this servlet is set as a {@link ServletContext} attribute with the 
 * filter name as the attribute name.  This allows context external mechanism (eg JMX via {@link ContextHandler#MANAGED_ATTRIBUTES}) to
 * manage the configuration of the filter.</dd>
 * </dl>
 * </p>
 */

public class DoSFilter implements Filter
{
    final static String __TRACKER = "DoSFilter.Tracker";
    final static String __THROTTLED = "DoSFilter.Throttled";

    final static int __DEFAULT_MAX_REQUESTS_PER_SEC = 25;
    final static int __DEFAULT_DELAY_MS = 100;
    final static int __DEFAULT_THROTTLE = 5;
    final static int __DEFAULT_WAIT_MS=50;
    final static long __DEFAULT_THROTTLE_MS = 30000L;
    final static long __DEFAULT_MAX_REQUEST_MS_INIT_PARAM=30000L;
    final static long __DEFAULT_MAX_IDLE_TRACKER_MS_INIT_PARAM=30000L;

    final static String MANAGED_ATTR_INIT_PARAM="managedAttr";
    final static String MAX_REQUESTS_PER_S_INIT_PARAM = "maxRequestsPerSec";
    final static String DELAY_MS_INIT_PARAM = "delayMs";
    final static String THROTTLED_REQUESTS_INIT_PARAM = "throttledRequests";
    final static String MAX_WAIT_INIT_PARAM="maxWaitMs";
    final static String THROTTLE_MS_INIT_PARAM = "throttleMs";
    final static String MAX_REQUEST_MS_INIT_PARAM="maxRequestMs";
    final static String MAX_IDLE_TRACKER_MS_INIT_PARAM="maxIdleTrackerMs";
    final static String INSERT_HEADERS_INIT_PARAM="insertHeaders";
    final static String TRACK_SESSIONS_INIT_PARAM="trackSessions";
    final static String REMOTE_PORT_INIT_PARAM="remotePort";
    final static String IP_WHITELIST_INIT_PARAM="ipWhitelist";

    final static int USER_AUTH = 2;
    final static int USER_SESSION = 2;
    final static int USER_IP = 1;
    final static int USER_UNKNOWN = 0;

    ServletContext _context;

    protected String _name;
    protected long _delayMs;
    protected long _throttleMs;
    protected long _maxWaitMs;
    protected long _maxRequestMs;
    protected long _maxIdleTrackerMs;
    protected boolean _insertHeaders;
    protected boolean _trackSessions;
    protected boolean _remotePort;
    protected int _throttledRequests;
    protected Semaphore _passes;
    protected Queue<Continuation>[] _queue;
    protected ContinuationListener[] _listener;

    protected int _maxRequestsPerSec;
    protected final ConcurrentHashMap<String, RateTracker> _rateTrackers=new ConcurrentHashMap<String, RateTracker>();
    protected String _whitelistStr;
    private final HashSet<String> _whitelist = new HashSet<String>();

    private final Timeout _requestTimeoutQ = new Timeout();
    private final Timeout _trackerTimeoutQ = new Timeout();

    private Thread _timerThread;
    private volatile boolean _running;

    public void init(FilterConfig filterConfig)
    {
        _context = filterConfig.getServletContext();

        _queue = new Queue[getMaxPriority() + 1];
        _listener = new ContinuationListener[getMaxPriority() + 1];
        for (int p = 0; p < _queue.length; p++)
        {
            _queue[p] = new ConcurrentLinkedQueue<Continuation>();

            final int priority=p;
            _listener[p] = new ContinuationListener()
            {
                public void onComplete(Continuation continuation)
                {
                }

                public void onTimeout(Continuation continuation)
                {
                    _queue[priority].remove(continuation);
                }
            };
        }

        _rateTrackers.clear();

        int baseRateLimit = __DEFAULT_MAX_REQUESTS_PER_SEC;
        if (filterConfig.getInitParameter(MAX_REQUESTS_PER_S_INIT_PARAM) != null)
            baseRateLimit = Integer.parseInt(filterConfig.getInitParameter(MAX_REQUESTS_PER_S_INIT_PARAM));
        _maxRequestsPerSec = baseRateLimit;

        long delay = __DEFAULT_DELAY_MS;
        if (filterConfig.getInitParameter(DELAY_MS_INIT_PARAM) != null)
            delay = Integer.parseInt(filterConfig.getInitParameter(DELAY_MS_INIT_PARAM));
        _delayMs = delay;

        int throttledRequests = __DEFAULT_THROTTLE;
        if (filterConfig.getInitParameter(THROTTLED_REQUESTS_INIT_PARAM) != null)
            throttledRequests = Integer.parseInt(filterConfig.getInitParameter(THROTTLED_REQUESTS_INIT_PARAM));
        _passes = new Semaphore(throttledRequests,true);
        _throttledRequests = throttledRequests;

        long wait = __DEFAULT_WAIT_MS;
        if (filterConfig.getInitParameter(MAX_WAIT_INIT_PARAM) != null)
            wait = Integer.parseInt(filterConfig.getInitParameter(MAX_WAIT_INIT_PARAM));
        _maxWaitMs = wait;

        long suspend = __DEFAULT_THROTTLE_MS;
        if (filterConfig.getInitParameter(THROTTLE_MS_INIT_PARAM) != null)
            suspend = Integer.parseInt(filterConfig.getInitParameter(THROTTLE_MS_INIT_PARAM));
        _throttleMs = suspend;

        long maxRequestMs = __DEFAULT_MAX_REQUEST_MS_INIT_PARAM;
        if (filterConfig.getInitParameter(MAX_REQUEST_MS_INIT_PARAM) != null )
            maxRequestMs = Long.parseLong(filterConfig.getInitParameter(MAX_REQUEST_MS_INIT_PARAM));
        _maxRequestMs = maxRequestMs;

        long maxIdleTrackerMs = __DEFAULT_MAX_IDLE_TRACKER_MS_INIT_PARAM;
        if (filterConfig.getInitParameter(MAX_IDLE_TRACKER_MS_INIT_PARAM) != null )
            maxIdleTrackerMs = Long.parseLong(filterConfig.getInitParameter(MAX_IDLE_TRACKER_MS_INIT_PARAM));
        _maxIdleTrackerMs = maxIdleTrackerMs;

        _whitelistStr = "";
        if (filterConfig.getInitParameter(IP_WHITELIST_INIT_PARAM) !=null )
            _whitelistStr = filterConfig.getInitParameter(IP_WHITELIST_INIT_PARAM);
        initWhitelist();

        String tmp = filterConfig.getInitParameter(INSERT_HEADERS_INIT_PARAM);
        _insertHeaders = tmp==null || Boolean.parseBoolean(tmp);

        tmp = filterConfig.getInitParameter(TRACK_SESSIONS_INIT_PARAM);
        _trackSessions = tmp==null || Boolean.parseBoolean(tmp);

        tmp = filterConfig.getInitParameter(REMOTE_PORT_INIT_PARAM);
        _remotePort = tmp!=null&& Boolean.parseBoolean(tmp);

        _requestTimeoutQ.setNow();
        _requestTimeoutQ.setDuration(_maxRequestMs);

        _trackerTimeoutQ.setNow();
        _trackerTimeoutQ.setDuration(_maxIdleTrackerMs);

        _running=true;
        _timerThread = (new Thread()
        {
            public void run()
            {
                try
                {
                    while (_running)
                    {
                        long now;
                        synchronized (_requestTimeoutQ)
                        {
                            now = _requestTimeoutQ.setNow();
                            _requestTimeoutQ.tick();
                        }
                        synchronized (_trackerTimeoutQ)
                        {
                            _trackerTimeoutQ.setNow(now);
                            _trackerTimeoutQ.tick();
                        }
                        try
                        {
                            Thread.sleep(100);
                        }
                        catch (InterruptedException e)
                        {
                            Log.ignore(e);
                        }
                    }
                }
                finally
                {
                    Log.info("DoSFilter timer exited");
                }
            }
        });
        _timerThread.start();

        if (_context!=null && Boolean.parseBoolean(filterConfig.getInitParameter(MANAGED_ATTR_INIT_PARAM)))
            _context.setAttribute(filterConfig.getFilterName(),this);
    }


    public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterchain) throws IOException, ServletException
    {
        final HttpServletRequest srequest = (HttpServletRequest)request;
        final HttpServletResponse sresponse = (HttpServletResponse)response;

        final long now=_requestTimeoutQ.getNow();

        // Look for the rate tracker for this request
        RateTracker tracker = (RateTracker)request.getAttribute(__TRACKER);

        if (tracker==null)
        {
            // This is the first time we have seen this request.

            // get a rate tracker associated with this request, and record one hit
            tracker = getRateTracker(request);

            // Calculate the rate and check it is over the allowed limit
            final boolean overRateLimit = tracker.isRateExceeded(now);

            // pass it through if  we are not currently over the rate limit
            if (!overRateLimit)
            {
                doFilterChain(filterchain,srequest,sresponse);
                return;
            }

            // We are over the limit.
            Log.warn("DOS ALERT: ip="+srequest.getRemoteAddr()+",session="+srequest.getRequestedSessionId()+",user="+srequest.getUserPrincipal());

            // So either reject it, delay it or throttle it
            switch((int)_delayMs)
            {
                case -1:
                {
                    // Reject this request
                    if (_insertHeaders)
                        ((HttpServletResponse)response).addHeader("DoSFilter","unavailable");
                    ((HttpServletResponse)response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    return;
                }
                case 0:
                {
                    // fall through to throttle code
                    request.setAttribute(__TRACKER,tracker);
                    break;
                }
                default:
                {
                    // insert a delay before throttling the request
                    if (_insertHeaders)
                        ((HttpServletResponse)response).addHeader("DoSFilter","delayed");
                    Continuation continuation = ContinuationSupport.getContinuation(request);
                    request.setAttribute(__TRACKER,tracker);
                    if (_delayMs > 0)
                        continuation.setTimeout(_delayMs);
                    continuation.suspend();
                    return;
                }
            }
        }

        // Throttle the request
        boolean accepted = false;
        try
        {
            // check if we can afford to accept another request at this time
            accepted = _passes.tryAcquire(_maxWaitMs,TimeUnit.MILLISECONDS);

            if (!accepted)
            {
                // we were not accepted, so either we suspend to wait,or if we were woken up we insist or we fail
                final Continuation continuation = ContinuationSupport.getContinuation(request);

                Boolean throttled = (Boolean)request.getAttribute(__THROTTLED);
                if (throttled!=Boolean.TRUE && _throttleMs>0)
                {
                    int priority = getPriority(request,tracker);
                    request.setAttribute(__THROTTLED,Boolean.TRUE);
                    if (_insertHeaders)
                        ((HttpServletResponse)response).addHeader("DoSFilter","throttled");
                    if (_throttleMs > 0)
                        continuation.setTimeout(_throttleMs);
                    continuation.suspend();

                    continuation.addContinuationListener(_listener[priority]);
                    _queue[priority].add(continuation);
                    return;
                }
                // else were we resumed?
                else if (request.getAttribute("javax.servlet.resumed")==Boolean.TRUE)
                {
                    // we were resumed and somebody stole our pass, so we wait for the next one.
                    _passes.acquire();
                    accepted = true;
                }
            }

            // if we were accepted (either immediately or after throttle)
            if (accepted)
                // call the chain
                doFilterChain(filterchain,srequest,sresponse);
            else
            {
                // fail the request
                if (_insertHeaders)
                    ((HttpServletResponse)response).addHeader("DoSFilter","unavailable");
                ((HttpServletResponse)response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            }
        }
        catch (InterruptedException e)
        {
            _context.log("DoS",e);
            ((HttpServletResponse)response).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        }
        finally
        {
            if (accepted)
            {
                // wake up the next highest priority request.
                for (int p = _queue.length; p-- > 0;)
                {
                    Continuation continuation = _queue[p].poll();
                    if (continuation != null && continuation.isSuspended())
                    {
                        continuation.resume();
                        break;
                    }
                }
                _passes.release();
            }
        }
    }

    /**
     * @param chain
     * @param request
     * @param response
     * @throws IOException
     * @throws ServletException
     */
    protected void doFilterChain(FilterChain chain, final HttpServletRequest request, final HttpServletResponse response)
        throws IOException, ServletException
    {
        final Thread thread=Thread.currentThread();

        final Timeout.Task requestTimeout = new Timeout.Task()
        {
            public void expired()
            {
                closeConnection(request, response, thread);
            }
        };

        try
        {
            synchronized (_requestTimeoutQ)
            {
                _requestTimeoutQ.schedule(requestTimeout);
            }
            chain.doFilter(request,response);
        }
        finally
        {
            synchronized (_requestTimeoutQ)
            {
                requestTimeout.cancel();
            }
        }
    }

    /**
     * Takes drastic measures to return this response and stop this thread.
     * Due to the way the connection is interrupted, may return mixed up headers.
     * @param request current request
     * @param response current response, which must be stopped
     * @param thread the handling thread
     */
    protected void closeConnection(HttpServletRequest request, HttpServletResponse response, Thread thread)
    {
        // take drastic measures to return this response and stop this thread.
        if( !response.isCommitted() )
        {
            response.setHeader("Connection", "close");
        }
        try
        {
            try
            {
                response.getWriter().close();
            }
            catch (IllegalStateException e)
            {
                response.getOutputStream().close();
            }
        }
        catch (IOException e)
        {
            Log.warn(e);
        }

        // interrupt the handling thread
        thread.interrupt();
    }

    /**
     * Get priority for this request, based on user type
     *
     * @param request
     * @param tracker
     * @return priority
     */
    protected int getPriority(ServletRequest request, RateTracker tracker)
    {
        if (extractUserId(request)!=null)
            return USER_AUTH;
        if (tracker!=null)
            return tracker.getType();
        return USER_UNKNOWN;
    }

    /**
     * @return the maximum priority that we can assign to a request
     */
    protected int getMaxPriority()
    {
        return USER_AUTH;
    }

    /**
     * Return a request rate tracker associated with this connection; keeps
     * track of this connection's request rate. If this is not the first request
     * from this connection, return the existing object with the stored stats.
     * If it is the first request, then create a new request tracker.
     *
     * Assumes that each connection has an identifying characteristic, and goes
     * through them in order, taking the first that matches: user id (logged
     * in), session id, client IP address. Unidentifiable connections are lumped
     * into one.
     *
     * When a session expires, its rate tracker is automatically deleted.
     *
     * @param request
     * @return the request rate tracker for the current connection
     */
    public RateTracker getRateTracker(ServletRequest request)
    {
        HttpServletRequest srequest = (HttpServletRequest)request;
        HttpSession session=srequest.getSession(false);

        String loadId = extractUserId(request);
        final int type;
        if (loadId != null)
        {
            type = USER_AUTH;
        }
        else
        {
            if (_trackSessions && session!=null && !session.isNew())
            {
                loadId=session.getId();
                type = USER_SESSION;
            }
            else
            {
                loadId = _remotePort?(request.getRemoteAddr()+request.getRemotePort()):request.getRemoteAddr();
                type = USER_IP;
            }
        }

        RateTracker tracker=_rateTrackers.get(loadId);

        if (tracker==null)
        {
            RateTracker t;
            if (_whitelist.contains(request.getRemoteAddr()))
            {
                t = new FixedRateTracker(loadId,type,_maxRequestsPerSec);
            }
            else
            {
                t = new RateTracker(loadId,type,_maxRequestsPerSec);
            }

            tracker=_rateTrackers.putIfAbsent(loadId,t);
            if (tracker==null)
                tracker=t;

            if (type == USER_IP)
            {
                // USER_IP expiration from _rateTrackers is handled by the _trackerTimeoutQ
                synchronized (_trackerTimeoutQ)
                {
                    _trackerTimeoutQ.schedule(tracker);
                }
            }
            else if (session!=null)
                // USER_SESSION expiration from _rateTrackers are handled by the HttpSessionBindingListener
                session.setAttribute(__TRACKER,tracker);
        }

        return tracker;
    }

    public void destroy()
    {
        _running=false;
        _timerThread.interrupt();
        synchronized (_requestTimeoutQ)
        {
            _requestTimeoutQ.cancelAll();
        }
        synchronized (_trackerTimeoutQ)
        {
            _trackerTimeoutQ.cancelAll();
        }
        _rateTrackers.clear();
        _whitelist.clear();
    }

    /**
     * Returns the user id, used to track this connection.
     * This SHOULD be overridden by subclasses.
     *
     * @param request
     * @return a unique user id, if logged in; otherwise null.
     */
    protected String extractUserId(ServletRequest request)
    {
        return null;
    }

    /* ------------------------------------------------------------ */
    /**
     * Initialize the IP address whitelist
     */
    protected void initWhitelist()
    {
        _whitelist.clear();
        StringTokenizer tokenizer = new StringTokenizer(_whitelistStr, ",");
        while (tokenizer.hasMoreTokens())
            _whitelist.add(tokenizer.nextToken().trim());

        Log.info("Whitelisted IP addresses: {}", _whitelist.toString());
    }
    
    /* ------------------------------------------------------------ */
    /** 
     * Get maximum number of requests from a connection per
     * second. Requests in excess of this are first delayed,
     * then throttled.
     * 
     * @return maximum number of requests
     */
    public int getMaxRequestsPerSec()
    {
        return _maxRequestsPerSec;
    }

    /* ------------------------------------------------------------ */
    /** 
     * Get maximum number of requests from a connection per
     * second. Requests in excess of this are first delayed,
     * then throttled.
     * 
     * @param value maximum number of requests
     */
    public void setMaxRequestsPerSec(int value)
    {
        _maxRequestsPerSec = value;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Get delay (in milliseconds) that is applied to all requests 
     * over the rate limit, before they are considered at all. 
     */
    public long getDelayMs()
    {
        return _delayMs;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set delay (in milliseconds) that is applied to all requests 
     * over the rate limit, before they are considered at all. 
     * 
     * @param value delay (in milliseconds), 0 - no delay, -1 - reject request
     */
    public void setDelayMs(long value)
    {
        _delayMs = value;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Get maximum amount of time (in milliseconds) the filter will
     * blocking wait for the throttle semaphore.
     * 
     * @return maximum wait time
     */
    public long getMaxWaitMs()
    {
        return _maxWaitMs;
    }

    /* ------------------------------------------------------------ */
    /** 
     * Set maximum amount of time (in milliseconds) the filter will
     * blocking wait for the throttle semaphore.
     * 
     * @param value maximum wait time
     */
    public void setMaxWaitMs(long value)
    {
        _maxWaitMs = value;
    }

    /* ------------------------------------------------------------ */
    /**
     * Get number of requests over the rate limit able to be
     * considered at once.
     * 
     * @return number of requests
     */
    public long getThrottledRequests()
    {
        return _throttledRequests;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set number of requests over the rate limit able to be
     * considered at once.
     * 
     * @param value number of requests
     */
    public void setThrottledRequests(int value)
    {
        _passes = new Semaphore((value-_throttledRequests+_passes.availablePermits()), true);
        _throttledRequests = value;
    }

    /* ------------------------------------------------------------ */
    /**
     * Get amount of time (in milliseconds) to async wait for semaphore.
     * 
     * @return wait time
     */
    public long getThrottleMs()
    {
        return _throttleMs;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set amount of time (in milliseconds) to async wait for semaphore.
     * 
     * @param value wait time
     */
    public void setThrottleMs(long value)
    {
        _throttleMs = value;
    }

    /* ------------------------------------------------------------ */
    /** 
     * Get maximum amount of time (in milliseconds) to allow 
     * the request to process.
     * 
     * @return maximum processing time
     */
    public long getMaxRequestMs()
    {
        return _maxRequestMs;
    }

    /* ------------------------------------------------------------ */
    /** 
     * Set maximum amount of time (in milliseconds) to allow 
     * the request to process.
     * 
     * @param value maximum processing time
     */
    public void setMaxRequestMs(long value)
    {
        _maxRequestMs = value;
    }

    /* ------------------------------------------------------------ */
    /**
     * Get maximum amount of time (in milliseconds) to keep track
     * of request rates for a connection, before deciding that 
     * the user has gone away, and discarding it.
     * 
     * @return maximum tracking time
     */
    public long getMaxIdleTrackerMs()
    {
        return _maxIdleTrackerMs;
    }

    /* ------------------------------------------------------------ */
    /** 
     * Set maximum amount of time (in milliseconds) to keep track
     * of request rates for a connection, before deciding that 
     * the user has gone away, and discarding it.
     * 
     * @param value maximum tracking time
     */
    public void setMaxIdleTrackerMs(long value)
    {
        _maxIdleTrackerMs = value;
    }

    /* ------------------------------------------------------------ */
    /** 
     * Check flag to insert the DoSFilter headers into the response.
     * 
     * @return value of the flag
     */
    public boolean isInsertHeaders()
    {
        return _insertHeaders;
    }

    /* ------------------------------------------------------------ */
    /** 
     * Set flag to insert the DoSFilter headers into the response.
     * 
     * @param value value of the flag
     */
    public void setInsertHeaders(boolean value)
    {
        _insertHeaders = value;
    }

    /* ------------------------------------------------------------ */
    /** 
     * Get flag to have usage rate tracked by session if a session exists.
     * 
     * @return value of the flag
     */
    public boolean isTrackSessions()
    {
        return _trackSessions;
    }

    /* ------------------------------------------------------------ */
    /** 
     * Set flag to have usage rate tracked by session if a session exists.
     * @param value value of the flag
     */
    public void setTrackSessions(boolean value)
    {
        _trackSessions = value;
    }

    /* ------------------------------------------------------------ */
    /** 
     * Get flag to have usage rate tracked by IP+port (effectively connection)
     * if session tracking is not used.
     * 
     * @return value of the flag
     */
    public boolean isRemotePort()
    {
        return _remotePort;
    }


    /* ------------------------------------------------------------ */
    /** 
     * Set flag to have usage rate tracked by IP+port (effectively connection)
     * if session tracking is not used.
     * 
     * @param value value of the flag
     */
    public void setRemotePort(boolean value)
    {
        _remotePort = value;
    }

    /* ------------------------------------------------------------ */
    /**
     * Get a list of IP addresses that will not be rate limited.
     * 
     * @return comma-separated whitelist
     */
    public String getWhitelist()
    {
        return _whitelistStr;
    }


    /* ------------------------------------------------------------ */
    /**
     * Set a list of IP addresses that will not be rate limited.
     * 
     * @param value comma-separated whitelist
     */
    public void setWhitelist(String value)
    {
        _whitelistStr = value;
        initWhitelist();
    }

    /**
     * A RateTracker is associated with a connection, and stores request rate
     * data.
     */
    class RateTracker extends Timeout.Task implements HttpSessionBindingListener
    {
        protected final String _id;
        protected final int _type;
        protected final long[] _timestamps;
        protected int _next;

        public RateTracker(String id, int type,int maxRequestsPerSecond)
        {
            _id = id;
            _type = type;
            _timestamps=new long[maxRequestsPerSecond];
            _next=0;
        }

        /**
         * @return the current calculated request rate over the last second
         */
        public boolean isRateExceeded(long now)
        {
            final long last;
            synchronized (this)
            {
                last=_timestamps[_next];
                _timestamps[_next]=now;
                _next= (_next+1)%_timestamps.length;
            }

            boolean exceeded=last!=0 && (now-last)<1000L;
            return exceeded;
        }


        public String getId()
        {
            return _id;
        }

        public int getType()
        {
            return _type;
        }


        public void valueBound(HttpSessionBindingEvent event)
        {
        }

        public void valueUnbound(HttpSessionBindingEvent event)
        {
            _rateTrackers.remove(_id);
        }

        public void expired()
        {
            long now = _trackerTimeoutQ.getNow();
            int latestIndex = _next == 0 ? 3 : (_next - 1 ) % _timestamps.length;
            long last=_timestamps[latestIndex];
            boolean hasRecentRequest = last != 0 && (now-last)<1000L;

            if (hasRecentRequest)
                reschedule();
            else
                _rateTrackers.remove(_id);
        }

        @Override
        public String toString()
        {
            return "RateTracker/"+_id+"/"+_type;
        }
    }

    class FixedRateTracker extends RateTracker
    {
        public FixedRateTracker(String id, int type, int numRecentRequestsTracked)
        {
            super(id,type,numRecentRequestsTracked);
        }

        @Override
        public boolean isRateExceeded(long now)
        {
            // rate limit is never exceeded, but we keep track of the request timestamps
            // so that we know whether there was recent activity on this tracker
            // and whether it should be expired
            synchronized (this)
            {
                _timestamps[_next]=now;
                _next= (_next+1)%_timestamps.length;
            }

            return false;
        }

        @Override
        public String toString()
        {
            return "Fixed"+super.toString();
        }
    }
}
