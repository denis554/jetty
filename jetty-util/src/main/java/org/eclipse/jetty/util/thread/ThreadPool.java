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

package org.eclipse.jetty.util.thread;

import org.eclipse.jetty.util.component.LifeCycle;

/* ------------------------------------------------------------ */
/** ThreadPool.
 * 
 *
 */
public interface ThreadPool
{
    /* ------------------------------------------------------------ */
    public abstract boolean dispatch(Runnable job);

    /* ------------------------------------------------------------ */
    /**
     * Blocks until the thread pool is {@link LifeCycle#stop stopped}.
     */
    public void join() throws InterruptedException;

    /* ------------------------------------------------------------ */
    /**
     * @return The total number of threads currently in the pool
     */
    public int getThreads();

    /* ------------------------------------------------------------ */
    /**
     * @return The number of idle threads in the pool
     */
    public int getIdleThreads();
    
    /* ------------------------------------------------------------ */
    /**
     * @return True if the pool is low on threads
     */
    public boolean isLowOnThreads();
}
