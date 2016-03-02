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
* Copyright 2016 ForgeRock AS.
*/

package org.forgerock.openam.services.push;

import static org.forgerock.openam.services.push.PushNotificationConstants.*;

import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.iplanet.sso.SSOException;
import com.sun.identity.shared.debug.Debug;
import com.sun.identity.sm.DNMapper;
import com.sun.identity.sm.SMSException;
import com.sun.identity.sm.ServiceListener;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.inject.Inject;
import org.forgerock.guava.common.annotations.VisibleForTesting;

/**
 * The PushNotificationManager holds a map of instantiated PushNotificationDelegates to their realm in which they exist.
 * The PushNotificationManager produces PushNotificationDelegates in accordance with the PushNotificationDelegate
 * interface, using a provided PushNotificationDelegateFactory per realm's instantiated service.
 *
 * PushNotificationDelegateFactories are stored in a cache, so as to only load each factory class once.
 *
 * Creating a service config will not update the PushNotificationService unless it has been previously instantiated
 * by another component in the system. Therefore each time the service is called upon to perform a task such as
 * sending a message it checks that there exists a delegate to handle that request.
 *
 * If no delegate has been configured, the service will attempt to load the config for that realm before accessing
 * the delegate instance. Updating the service config via the service interface (after the service has been
 * instantiated) also causes the attempt to load the config & mint a delegate.
 *
 * Later changes in the config will update the delegateFactory and depending upon the delegate's implementation of
 * the isRequireNewDelegate(PushNotificationServiceConfig) method may require the generation of a new delegate. If
 * no new delegate is required, it may be appropriate to update the existing delegate's (non-connection-relevant)
 * configuration parameters. It may also be appropriate to leave the delegate exactly as it was if no configuration
 * option has been altered -- there's no point in tearing down the listeners and services in the case we're
 * re-creating the same delegate.
 */
@Singleton
public class PushNotificationService {

    /** Holds a map of the current PushNotificationDelegate for a given realm. */
    private final ConcurrentMap<String, PushNotificationDelegate> pushRealmMap;

    /** Holds a cache of all pushNotificationDelegateFactories we have used for quick loading. */
    private final ConcurrentMap<String, PushNotificationDelegateFactory> pushFactoryMap;

    private final Debug debug;

    private PushNotificationServiceConfigHelperFactory configHelperFactory;

    private final PushNotificationDelegateUpdater delegateUpdater;

    /**
     * Constructor (called by Guice), registers a listener for this class against all
     * PushNotificationService changes in a realm.
     * @param debug A debugger for logging.
     * @param configHelperFactory Factory used to produce config helpers, which in turn are used to generate
     *                            delegates.
     * @param pushRealmMap Map holding all delegates mapped to the realm in which they belong.
     * @param pushFactoryMap Map holding all factories registered during the lifetime of this service.
     */
    @Inject
    public PushNotificationService(@Named("frPush") Debug debug,
                                   PushNotificationServiceConfigHelperFactory configHelperFactory,
                                   ConcurrentMap<String, PushNotificationDelegate> pushRealmMap,
                                   ConcurrentMap<String, PushNotificationDelegateFactory> pushFactoryMap) {
        this.debug = debug;
        this.configHelperFactory = configHelperFactory;
        this.pushRealmMap = new ConcurrentHashMap<>(pushRealmMap); //just in case
        this.pushFactoryMap = new ConcurrentHashMap<>(pushFactoryMap);
        this.delegateUpdater = new PushNotificationDelegateUpdater();
        configHelperFactory.addListener(new PushNotificationServiceListener());
    }

    /**
     * Primary method of this class. Used to communicate via the appropriate delegate for this realm out
     * to a Push communication service such as GCM.
     *
     * @param message the message to transmit.
     * @param realm the realm from which to transmit the message.
     * @throws PushNotificationException if anything untoward occurred during the transmission.
     */
    public void send(PushMessage message, String realm) throws PushNotificationException {

        if (!pushRealmMap.containsKey(realm)) {

            synchronized (pushRealmMap) { //wait here for the thread with first access to update
                if (!pushRealmMap.containsKey(realm)) {
                    updatePreferences(realm);
                    if (!pushRealmMap.containsKey(realm)) {
                        debug.warning("No Push Notification Delegate configured for realm {}", realm);
                        throw new PushNotificationException("No Push Notification Delegate configured for this realm.");
                    }
                }
            }

        }

        getDelegateForRealm(realm).send(message);
    }

    /**
     * Get the current delegate for the provided realm.
     * @param realm Realm whose delegate you wish to retrieve.
     * @return The current mapping for that realm, or null if one does not exist.
     */
    private PushNotificationDelegate getDelegateForRealm(String realm) {
        return pushRealmMap.get(realm);
    }

    private void updatePreferences(String realm) throws PushNotificationException {
        PushNotificationServiceConfigHelper configHelper = getConfigHelper(realm);
        String factoryClass = configHelper.getFactoryClass();
        validateFactoryExists(factoryClass);
        PushNotificationServiceConfig config = configHelper.getConfig();
        PushNotificationDelegate pushNotificationDelegate = pushFactoryMap.get(factoryClass).produceDelegateFor(config);

        if (pushNotificationDelegate == null) {
            throw new PushNotificationException("PushNotificationFactory produced a null delete. Aborting update.");
        }

        delegateUpdater.replaceDelegate(realm, pushNotificationDelegate, config);
    }

    private void validateFactoryExists(String factoryClass) throws PushNotificationException {
        try {
            pushFactoryMap.putIfAbsent(factoryClass, createFactory(factoryClass));
        } catch (IllegalAccessException | InstantiationException | ClassNotFoundException e) {
            debug.error("Unable to instantiate PushNotificationDelegateFactory class.", e);
            throw new PushNotificationException("Unable to instantiate PushNotificationDelegateFactory class.", e);
        }

    }

    private PushNotificationServiceConfigHelper getConfigHelper(String realm) throws PushNotificationException {
        try {
            return configHelperFactory.getConfigHelperFor(realm);
        } catch (SSOException | SMSException e) {
            debug.warning("Unable to read config for PushNotificationServiceConfig in realm {}", realm);
            throw new PushNotificationException("Unable to find config for PushNotificationServiceConfig.", e);
        }

    }

    private PushNotificationDelegateFactory createFactory(String factoryClass)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        return (PushNotificationDelegateFactory) Class.forName(factoryClass).newInstance();
    }

    /**
     * Our service config change listener.
     */
    private final class PushNotificationServiceListener implements ServiceListener {

        /**
         * No-op for this impl.
         */
        @Override
        public void schemaChanged(String serviceName, String version) {
            //This section intentionally left blank
        }

        /**
         * No-op for this impl.
         */
        @Override
        public void globalConfigChanged(String serviceName, String version, String groupName,
                                        String serviceComponent, int type) {
            //This section intentionally left blank
        }

        @Override
        public void organizationConfigChanged(String serviceName, String version, String orgName, String groupName,
                                              String serviceComponent, int type) {
            try {
                if (SERVICE_NAME.equals(serviceName) && SERVICE_VERSION.equals(version)) {
                    //do update
                    synchronized (pushRealmMap) { //wait here for the thread with first access to update
                        updatePreferences(DNMapper.orgNameToRealmName(orgName));
                    }
                }
            } catch (PushNotificationException e) {
                debug.error("Unable to update preferences for organization {}", orgName, e);
            }
        }
    }

    /**
     * Our delegate updater.
     */
    @VisibleForTesting
    final class PushNotificationDelegateUpdater {

        void replaceDelegate(String realm, PushNotificationDelegate newDelegate,
                         PushNotificationServiceConfig config) throws PushNotificationException {

            try {
                PushNotificationDelegate oldDelegate = pushRealmMap.get(realm);

                if (oldDelegate == null) {
                    start(realm, newDelegate);
                } else {
                    if (oldDelegate.isRequireNewDelegate(config)) {
                        pushRealmMap.remove(realm);
                        try {
                            start(realm, newDelegate);
                        } finally {
                            oldDelegate.close();
                        }
                    } else {
                        oldDelegate.updateDelegate(config);
                    }
                }
            } catch (IOException e) {
                debug.error("Unable to call close on the old delegate having removed it from the realmPushMap.", e);
                throw new PushNotificationException("Error calling close on the previous delegate instance.", e);
            }
        }

        private void start(String realm, PushNotificationDelegate delegate) throws PushNotificationException {
            delegate.startServices();
            pushRealmMap.put(realm, delegate);
        }
    }

}
