// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.io.IOException;

import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Buffer;

/**
 * RedirectListener
 *
 * Detect and handle the redirect responses
 */
public class RedirectListener extends HttpEventListenerWrapper
{
    private HttpDestination _destination;
    private HttpExchange _exchange;
    private String _location;
    private int _attempts;
    private boolean _requestComplete;
    private boolean _responseComplete;
    private boolean _redirected;

    public RedirectListener(HttpDestination destination, HttpExchange ex)
    {
        // Start of sending events through to the wrapped listener
        // Next decision point is the onResponseStatus
        super(ex.getEventListener(),true);

        _destination = destination;
        _exchange = ex;
    }

    public void onResponseStatus( Buffer version, int status, Buffer reason )
        throws IOException
    {
        _redirected = ((status == HttpStatus.MOVED_PERMANENTLY_301 ||
                        status == HttpStatus.MOVED_TEMPORARILY_302) &&
                       _attempts < _destination.getHttpClient().maxRedirects());

        if (_redirected)
        {
            setDelegatingRequests(false);
            setDelegatingResponses(false);
        }

        super.onResponseStatus(version,status,reason);
    }


    public void onResponseHeader( Buffer name, Buffer value )
        throws IOException
    {
        if (_redirected)
        {
            int header = HttpHeaders.CACHE.getOrdinal(name);
            switch (header)
            {
                case HttpHeaders.LOCATION_ORDINAL:
                    _location = value.toString();
                    break;
            }
        }
        super.onResponseHeader(name,value);
    }

    public void onRequestComplete() throws IOException
    {
        _requestComplete = true;
        checkExchangeComplete();

        super.onRequestComplete();
    }

    public void onResponseComplete() throws IOException
    {
        _responseComplete = true;
        checkExchangeComplete();

        super.onResponseComplete();
    }

    public void checkExchangeComplete() throws IOException
    {
        if (_redirected && _requestComplete && _responseComplete)
        {
            setDelegatingRequests(true);
            setDelegatingResponses(true);

            if (_location != null)
            {
                if (_location.indexOf("://")>0)
                    _exchange.setURL(_location);
                else
                    _exchange.setURI(_location);

                _destination.resend(_exchange);
            }
            else
            {
                setDelegationResult(false);
            }
        }
    }

    public void onRetry()
    {
        _redirected=false;
        _attempts++;

        setDelegatingRequests(true);
        setDelegatingResponses(true);

        _requestComplete=false;
        _responseComplete=false;

        super.onRetry();
    }
}

