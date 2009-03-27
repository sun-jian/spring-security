/* Copyright 2004, 2005, 2006 Acegi Technology Pty Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.security.web.concurrent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpSession;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.security.concurrent.SessionInformation;
import org.springframework.security.concurrent.SessionRegistry;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.util.Assert;

/**
 * Base implementation of {@link org.springframework.security.concurrent.SessionRegistry}
 * which also listens for {@link org.springframework.security.web.session.HttpSessionDestroyedEvent}s
 * published in the Spring application context.
 *
 * <p>
 * NB: It is important that you register the {@link org.springframework.security.web.session.HttpSessionEventPublisher} in
 * <code>web.xml</code> so that this class is notified of sessions that expire.
 * </p>
 *
 * @author Ben Alex
 * @version $Id$
 */
public class SessionRegistryImpl implements SessionRegistry, ApplicationListener {
    //~ Static fields/initializers =====================================================================================

    protected static final Log logger = LogFactory.getLog(SessionRegistryImpl.class);

    // ~ Instance fields ===============================================================================================

    /** <principal:Object,SessionIdSet> */
    private Map<Object,Set<String>> principals = Collections.synchronizedMap(new HashMap<Object,Set<String>>());
    /** <sessionId:Object,SessionInformation> */
    private Map<String, SessionInformation> sessionIds = Collections.synchronizedMap(new HashMap<String, SessionInformation>());

    // ~ Methods =======================================================================================================

    public Object[] getAllPrincipals() {
        return principals.keySet().toArray();
    }

    public SessionInformation[] getAllSessions(Object principal, boolean includeExpiredSessions) {
        Set<String> sessionsUsedByPrincipal = principals.get(principal);

        if (sessionsUsedByPrincipal == null) {
            return null;
        }

        List<SessionInformation> list = new ArrayList<SessionInformation>();

        synchronized (sessionsUsedByPrincipal) {
            for (String sessionId : sessionsUsedByPrincipal) {
                SessionInformation sessionInformation = getSessionInformation(sessionId);

                if (sessionInformation == null) {
                    continue;
                }

                if (includeExpiredSessions || !sessionInformation.isExpired()) {
                    list.add(sessionInformation);
                }
            }
        }

        return (SessionInformation[]) list.toArray(new SessionInformation[0]);
    }

    public SessionInformation getSessionInformation(String sessionId) {
        Assert.hasText(sessionId, "SessionId required as per interface contract");

        return (SessionInformation) sessionIds.get(sessionId);
    }

    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof HttpSessionDestroyedEvent) {
            String sessionId = ((HttpSession) event.getSource()).getId();
            removeSessionInformation(sessionId);
        }
    }

    public void refreshLastRequest(String sessionId) {
        Assert.hasText(sessionId, "SessionId required as per interface contract");

        SessionInformation info = getSessionInformation(sessionId);

        if (info != null) {
            info.refreshLastRequest();
        }
    }

    public synchronized void registerNewSession(String sessionId, Object principal) {
        Assert.hasText(sessionId, "SessionId required as per interface contract");
        Assert.notNull(principal, "Principal required as per interface contract");

        if (logger.isDebugEnabled()) {
            logger.debug("Registering session " + sessionId +", for principal " + principal);
        }

        if (getSessionInformation(sessionId) != null) {
            removeSessionInformation(sessionId);
        }

        sessionIds.put(sessionId, new SessionInformation(principal, sessionId, new Date()));

        Set<String> sessionsUsedByPrincipal = principals.get(principal);

        if (sessionsUsedByPrincipal == null) {
            sessionsUsedByPrincipal = Collections.synchronizedSet(new HashSet<String>(4));
            principals.put(principal, sessionsUsedByPrincipal);
        }

        sessionsUsedByPrincipal.add(sessionId);
    }

    public void removeSessionInformation(String sessionId) {
        Assert.hasText(sessionId, "SessionId required as per interface contract");

        SessionInformation info = getSessionInformation(sessionId);

        if (info == null) {
            return;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Removing session " + sessionId + " from set of registered sessions");
        }

        sessionIds.remove(sessionId);

        Set<String> sessionsUsedByPrincipal = principals.get(info.getPrincipal());

        if (sessionsUsedByPrincipal == null) {
            return;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Removing session " + sessionId + " from principal's set of registered sessions");
        }

        synchronized (sessionsUsedByPrincipal) {
            sessionsUsedByPrincipal.remove(sessionId);

            if (sessionsUsedByPrincipal.size() == 0) {
                // No need to keep object in principals Map anymore
                if (logger.isDebugEnabled()) {
                    logger.debug("Removing principal " + info.getPrincipal() + " from registry");
                }
                principals.remove(info.getPrincipal());
            }
        }
    }
}