/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service;

//Core Java
import java.util.*;
import javax.jms.*;

//Log4j
import org.apache.log4j.*;

//JDOM
import org.jdom.Document;
import org.jdom.Element;

//OpenEAI Foundation
import org.openeai.config.*;
import org.openeai.jms.consumer.commands.*;
import org.openeai.layouts.EnterpriseLayoutException;
import org.openeai.moa.ActionableEnterpriseObject;

import edu.emory.awsaccount.service.provider.ProviderException;
import edu.emory.awsaccount.service.provider.StackProvider;
import edu.emory.awsaccount.service.provider.UserNotificationProvider;

/**
 * This command consumes a UserNotification message and passes it off to a
 * provider, which determines whether any other notification modalities such as
 * e-mail and text messages are desired.
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 4 July 2018
 * 
 */
public class UserNotificationSyncCommand extends AwsAccountSyncCommand implements SyncCommand {

    private boolean m_verbose = false;
    private UserNotificationProvider m_provider = null;
    private String LOGTAG = "[UserNotificationSyncCommand] ";
    private Category logger = org.openeai.OpenEaiObject.logger;

    /**
     * Constructor
     */
    public UserNotificationSyncCommand(CommandConfig cConfig) throws InstantiationException {

        super(cConfig);

        logger.info(LOGTAG + "Initializing...");
        logger.info(LOGTAG + ReleaseTag.getReleaseInfo());

        // Verify that the necessary message objects are in the AppConfig.
        // Get a UserNotification message object from AppConfig.
        com.amazon.aws.moa.jmsobjects.user.v1_0.UserNotification uNotification = new com.amazon.aws.moa.jmsobjects.user.v1_0.UserNotification();
        try {
            uNotification = (com.amazon.aws.moa.jmsobjects.user.v1_0.UserNotification) getAppConfig()
                    .getObjectByType(uNotification.getClass().getName());
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred getting an object from AppConfig. " + "The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        // Initialize a UserNotificationProvider
        String className = getProperties().getProperty("userNotificationProviderClassName");
        if (className == null || className.equals("")) {
            String errMsg = "No userNotificationProviderClassName property " + "specified. Can't continue.";
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }
        logger.info(LOGTAG + "userNotificationProviderClassName is: " + className);

        UserNotificationProvider provider = null;
        try {
            logger.info(LOGTAG + "Getting class for name: " + className);
            Class providerClass = Class.forName(className);
            if (providerClass == null)
                logger.info(LOGTAG + "providerClass is null.");
            else
                logger.info(LOGTAG + "providerClass is not null.");
            provider = (UserNotificationProvider) Class.forName(className).newInstance();
            logger.info(LOGTAG + "Initializing UserNotificationProvider: " + provider.getClass().getName());
            provider.init(getAppConfig());
            logger.info(LOGTAG + "UserNotificationProvider initialized.");
            setProvider(provider);
        } catch (ClassNotFoundException cnfe) {
            String errMsg = "Class named " + className + "not found on the " + "classpath.  The exception is: " + cnfe.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        } catch (IllegalAccessException iae) {
            String errMsg = "An error occurred getting a class for name: " + className + ". The exception is: " + iae.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        } catch (ProviderException pe) {
            String errMsg = "An error occurred initializing the UserNotificationProvider " + className + ". The exception is: "
                    + pe.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        logger.info(LOGTAG + "Initialization complete.");

    }

    /**
     * @param messageNumber
     * @param aMessage
     * 
     *            Parses the UserNotification message and passes it off to a
     *            provider for handling.
     */
    @Override
    public void execute(int messageNumber, Message aMessage) {
        String LOGTAG = "[UserNotificationSyncCommand.execute] ";
        logger.info(LOGTAG + "Handling sync message.");

        // Convert the JMS Message to an XML Document
        Document inDoc = null;

        try {
            inDoc = initializeInput(messageNumber, aMessage);
        } catch (JMSException jmse) {
            String errMsg = "Exception occurred processing input message in " + "org.openeai.jms.consumer.commands.Command.  Exception: "
                    + jmse.getMessage();
            logger.error(LOGTAG + errMsg);
        }

        // If verbose, write the message body to the log.
        if (getVerbose())
            logger.info("Message sent in is: \n" + getMessageBody(inDoc));

        // Retrieve text portion of message.
        TextMessage msg = (TextMessage) aMessage;
        try {
            // Clear the message body for the reply, so we do not
            // have to do it later.
            msg.clearBody();
        } catch (JMSException jmse) {
            String errMsg = "Error clearing the message body. The exception is: " + jmse.getMessage();
            logger.error(LOGTAG + errMsg);
        }

        // Verify that this is an UserNotification message.
        // Get the ControlArea from XML document.
        Element eControlArea = getControlArea(inDoc.getRootElement());

        // Get messageAction and messageObject attributes from the
        // ControlArea element.
        String msgAction = eControlArea.getAttribute("messageAction").getValue();
        String msgObject = eControlArea.getAttribute("messageObject").getValue();
        String msgRelease = eControlArea.getAttributeValue("messageRelease");

        // Verify that the message object we are dealing with is an
        // UserNotification object; if not, publish a Sync.Error-Sync.
        logger.debug(LOGTAG + "Message object is: " + msgObject);
        if (msgObject.equalsIgnoreCase("UserNotification") == false) {
            String errType = "application";
            String errCode = "OpenEAI-1001";
            String errDesc = "Unsupported message object: " + msgObject + ". This command expects 'UserNotification'.";
            logger.error(LOGTAG + errDesc);
            logger.error(LOGTAG + "Message sent in is: \n" + getMessageBody(inDoc));
            ArrayList errors = new ArrayList();
            errors.add(buildError(errType, errCode, errDesc));
            publishSyncError(eControlArea, errors);
            return;
        }

        // Verify that the message action is create.
        logger.debug(LOGTAG + "Message action is: " + msgAction);
        if (msgAction.equalsIgnoreCase("Create") == false) {
            String errType = "application";
            String errCode = "OpenEAI-1001";
            String errDesc = "Unsupported message action: " + msgAction + ". This command expects 'Create'.";
            logger.error(LOGTAG + errDesc);
            logger.error(LOGTAG + "Message sent in is: \n" + getMessageBody(inDoc));
            ArrayList errors = new ArrayList();
            errors.add(buildError(errType, errCode, errDesc));
            publishSyncError(eControlArea, errors);
            return;
        }

        // Verify that we are working with a supported version of the message.
        logger.debug(LOGTAG + "Message release is: " + msgRelease);
        if ((msgRelease.equalsIgnoreCase("1.0") == false)) {
            String errType = "application";
            String errCode = "OpenEAI-1001";
            String errDesc = "Unsupported message release: " + msgRelease + ". This command expects release 1.0.";
            logger.error(LOGTAG + errDesc);
            logger.error(LOGTAG + "Message sent in is: \n" + getMessageBody(inDoc));
            ArrayList errors = new ArrayList();
            errors.add(buildError(errType, errCode, errDesc));
            publishSyncError(eControlArea, errors);
            return;
        }

        // Get the UserNotification element from the message passed in.
        Element eDataArea = inDoc.getRootElement().getChild("DataArea");
        Element eNewData = null;
        Element eUserNotification = null;
        String missingElement = null;
        if (eDataArea != null) {
            eNewData = eDataArea.getChild("NewData");
            if (eNewData != null) {
                eUserNotification = eNewData.getChild("UserNotification");
            } else {
                missingElement = "UserNotification";
            }
        } else {
            missingElement = "NewData";
        }
        if (missingElement == null && eUserNotification == null) {
            missingElement = "UserNotification";
        }

        // If there is no UserNotification element, publish a Sync.Error-Sync
        if (missingElement != null || eUserNotification == null) {
            String errType = "application";
            String errCode = "AwsAccountService-8001";
            String errDesc = "An error occurred getting the UserNotification element " + "from the message passed in. Missing element: "
                    + missingElement;
            logger.error(LOGTAG + errDesc);
            ArrayList errors = new ArrayList();
            errors.add(buildError(errType, errCode, errDesc));
            publishSyncError(eControlArea, errors);
            return;
        }

        // Get a UserNotification message object from AppConfig.
        com.amazon.aws.moa.jmsobjects.user.v1_0.UserNotification uNotification = new com.amazon.aws.moa.jmsobjects.user.v1_0.UserNotification();
        try {
            uNotification = (com.amazon.aws.moa.jmsobjects.user.v1_0.UserNotification) getAppConfig()
                    .getObjectByType(uNotification.getClass().getName());
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred getting an object from AppConfig. " + "The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
        }

        // Build the UserNotification object from the element passed in.
        try {
            uNotification.buildObjectFromInput(eUserNotification);
        } catch (EnterpriseLayoutException ele) {
            String errType = "application";
            String errCode = "AwsAccountService-8002";
            String errDesc = "An error occurred building the UserNotification object"
                    + " from the UserNotification element passed in. The exception is: " + ele.getMessage();
            logger.error(LOGTAG + errDesc);
            ArrayList errors = new ArrayList();
            errors.add(buildError(errType, errCode, errDesc));
            publishSyncError(eControlArea, errors);
            return;
        }

        // Process any additional notification methods for this notification.
        try {
            logger.info(LOGTAG + "Process additional notifications for " + "UserNotification: " + uNotification.getUserNotificationId());
            long startTime = System.currentTimeMillis();
            getProvider().processAdditionalNotifications(uNotification);
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Processed additional notifications in " + time + "ms.");
        } catch (ProviderException pe) {
            String errMsg = "An error occurred processing additional " + "notifications. The exception is: " + pe.getMessage();
            logger.error(LOGTAG + errMsg);
            // TODO: publish a Sync.Error-Sync
        }

        return;
    }

    private void setProvider(UserNotificationProvider provider) {
        m_provider = provider;
    }

    private UserNotificationProvider getProvider() {
        return m_provider;
    }
}
