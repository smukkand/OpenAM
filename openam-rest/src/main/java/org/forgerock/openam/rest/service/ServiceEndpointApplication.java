/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2014-2015 ForgeRock AS.
 */

package org.forgerock.openam.rest.service;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.restlet.Application;
import org.restlet.Restlet;
import org.restlet.service.StatusService;

/**
 * Restlet Application for REST Service Endpoints.
 *
 * @since 12.0.0
 */
public abstract class ServiceEndpointApplication extends Application {

    final static String RESTLET_LOGGER_NAME = "org.restlet.Component.LogService";

    // OPENAM-3275
    static {
        Logger logger = Logger.getLogger(RESTLET_LOGGER_NAME);
        logger.setLevel(Level.OFF);
    }

    /**
     * Constructs a new ServiceEndpointApplication.
     * <br/>
     * Sets the StatusService to {@link RestStatusService}.
     */
    protected ServiceEndpointApplication(StatusService statusService) {
        setStatusService(statusService);
    }

    /**
     * Creates an inbound Restlet root for all registered REST Service endpoints.
     *
     * @return A Restlet for routing incoming REST Service endpoint requests.
     */
    @Override
    public Restlet createInboundRoot() {
        Restlet router = getRouter();
        router.setContext(getContext());
        return router;
    }

    /**
     * Obtain the router for this application from the RestEndpoints.
     * @return The required router.
     */
    protected abstract Restlet getRouter();
}
